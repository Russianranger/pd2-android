/* Actual ARM64 libasound/plugin negotiation with a deterministic mock AServer.
 * The mock replaces socket syscalls only; ALSA and the shipped PCM are unmodified.
 * No Android audio device, Wine mix timing or audible quality is simulated.
 */
#define _GNU_SOURCE
#include <alsa/asoundlib.h>
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

#define MOCK_FD 27001
static unsigned checks, opens, closes, starts, stops, prepares, writes, queried_rate;
static unsigned rate, frame_bytes, buffer_frames, payload_bytes, position;
static int pending, shm_fd = -1;
static uint8_t *shm;
static size_t shm_size;
static int using_shm;
static unsigned data_type;
#define CHECK(x) do { checks++; if (!(x)) { fprintf(stderr, "check failed %d: %s\n", __LINE__, #x); exit(2); } } while (0)
static int32_t i32(const void *p) { int32_t v; memcpy(&v, p, 4); return v; }

int socket(int domain, int type, int protocol)
{
    CHECK(domain == AF_UNIX && type == SOCK_STREAM && protocol == 0);
    opens++;
    return MOCK_FD;
}

int connect(int fd, const struct sockaddr *address, socklen_t size)
{
    const struct sockaddr_un *unix_address = (const void *)address;
    CHECK(fd == MOCK_FD && size == sizeof(*unix_address));
    CHECK(unix_address->sun_family == AF_UNIX);
    CHECK(strcmp(unix_address->sun_path, "/pd2/mock-alsa") == 0);
    return 0;
}

int close(int fd)
{
    if (fd == MOCK_FD) { closes++; return 0; }
    return syscall(SYS_close, fd);
}

ssize_t write(int fd, const void *data, size_t size)
{
    if (fd != MOCK_FD) return syscall(SYS_write, fd, data, size);
    const uint8_t *bytes = data;
    if (pending == 5 && !using_shm) {
        CHECK(size == payload_bytes && size > 0 && size <= buffer_frames * frame_bytes);
        for (size_t i = 0; i < size; i++) CHECK(bytes[i] == 0x55);
        position += size / frame_bytes;
        pending = 0;
        writes++;
        return size;
    }
    CHECK(size >= 5);
    int length = i32(bytes + 1);
    switch (bytes[0]) {
    case 0: CHECK(length == 0 && size == 5); break;
    case 1: CHECK(length == 0 && size == 5); starts++; break;
    case 2: CHECK(length == 0 && size == 5); stops++; break;
    case 3: CHECK(length == 0 && size == 5); break;
    case 4:
        CHECK(length == 10 && size == 15);
        CHECK(bytes[5] == 2 && bytes[6] == data_type);
        rate = i32(bytes + 7);
        frame_bytes = data_type == 1 ? 4 : 8;
        buffer_frames = i32(bytes + 11);
        CHECK(rate == queried_rate && buffer_frames > 0 && buffer_frames < 65536);
        prepares++;
        position = 0;
        if (using_shm) {
            if (shm) { munmap(shm, shm_size); shm = NULL; }
            shm_size = buffer_frames * frame_bytes + 4;
            shm_fd = memfd_create("pd2-audio-probe", 0);
            CHECK(shm_fd >= 0 && ftruncate(shm_fd, shm_size) == 0);
            shm = mmap(NULL, shm_size, PROT_READ | PROT_WRITE, MAP_SHARED, shm_fd, 0);
            CHECK(shm != MAP_FAILED);
        }
        break;
    case 5:
        CHECK(size == 5 && length > 0 && (unsigned)length <= buffer_frames * frame_bytes);
        payload_bytes = length;
        pending = 5;
        if (using_shm) {
            for (int i = 0; i < length; i++) CHECK(shm[4 + i] == 0x55);
            position += length / frame_bytes;
            memcpy(shm, &position, 4);
            writes++;
        }
        break;
    case 6: CHECK(length == 0 && size == 5); break;
    case 7: CHECK(length == 0 && size == 5); pending = 7; break;
    case 8:
        CHECK(length == 6 && size == 11 && bytes[5] == 2 && bytes[6] == data_type);
        queried_rate = i32(bytes + 7);
        pending = 8;
        break;
    default: CHECK(0);
    }
    return size;
}

ssize_t read(int fd, void *data, size_t size)
{
    if (fd != MOCK_FD) return syscall(SYS_read, fd, data, size);
    if (pending == 8) {
        CHECK(size == 4);
        int bytes = ((queried_rate * 32 / 1000 + 255) / 256) * 256 * (data_type == 1 ? 4 : 8);
        memcpy(data, &bytes, 4);
    } else if (pending == 7) {
        CHECK(size == 4); memcpy(data, &position, 4);
    } else if (pending == 5 && using_shm) {
        CHECK(size == 1); *(uint8_t *)data = 1;
    } else CHECK(0);
    pending = 0;
    return size;
}

ssize_t recvmsg(int fd, struct msghdr *message, int flags)
{
    CHECK(fd == MOCK_FD && flags == 0 && using_shm && shm_fd >= 0);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(message);
    CHECK(cmsg != NULL && message->msg_iovlen == 1 && message->msg_iov[0].iov_len == 1);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &shm_fd, sizeof(int));
    *(uint8_t *)message->msg_iov[0].iov_base = 0;
    shm_fd = -1;
    return 1;
}

int main(int argc, char **argv)
{
    CHECK(argc == 5);
    unsigned expected_min = atoi(argv[1]), expected_max = atoi(argv[2]);
    using_shm = atoi(argv[3]);
    data_type = atoi(argv[4]);
    CHECK(data_type == 1 || data_type == 3);
    CHECK(setenv("ANDROID_ALSA_SERVER", "/pd2/mock-alsa", 1) == 0);
    CHECK(setenv("ANDROID_ASERVER_USE_SHM", using_shm ? "true" : "false", 1) == 0);
    snd_pcm_t *pcm;
    CHECK(snd_pcm_open(&pcm, "default", SND_PCM_STREAM_CAPTURE, SND_PCM_NONBLOCK) == -ENOTSUP);
    int result = snd_pcm_open(&pcm, "default", SND_PCM_STREAM_PLAYBACK, SND_PCM_NONBLOCK);
    if (result < 0) fprintf(stderr, "open: %s\n", snd_strerror(result));
    CHECK(result == 0);
    snd_pcm_hw_params_t *params;
    snd_pcm_hw_params_alloca(&params);
    CHECK(snd_pcm_hw_params_any(pcm, params) >= 0);
    unsigned low = 0, high = 0;
    CHECK(snd_pcm_hw_params_get_rate_min(params, &low, NULL) == 0 && low == expected_min);
    CHECK(snd_pcm_hw_params_get_rate_max(params, &high, NULL) == 0 && high == expected_max);
    CHECK(snd_pcm_hw_params_test_rate(pcm, params, expected_max, 0) == 0);
    CHECK((snd_pcm_hw_params_test_rate(pcm, params, 48000, 0) == 0) == (expected_max == 48000));
    unsigned requested_rate = 48000;
    CHECK(snd_pcm_hw_params_set_rate_near(pcm, params, &requested_rate, NULL) == 0);
    CHECK(requested_rate == expected_max);
    result = snd_pcm_set_params(pcm, data_type == 1 ? SND_PCM_FORMAT_S16_LE : SND_PCM_FORMAT_FLOAT_LE, SND_PCM_ACCESS_RW_INTERLEAVED,
                               2, expected_max, 0, 32000);
    if (result < 0) fprintf(stderr, "set_params: %s\n", snd_strerror(result));
    CHECK(result == 0);
    CHECK(prepares > 0 && rate == expected_max);
    uint8_t samples[256];
    memset(samples, 0x55, sizeof(samples));
    CHECK(snd_pcm_writei(pcm, samples, sizeof(samples) / frame_bytes) == (snd_pcm_sframes_t)(sizeof(samples) / frame_bytes));
    CHECK(writes == 1 && position == sizeof(samples) / frame_bytes);
    CHECK(snd_pcm_start(pcm) == 0 && starts == 1);
    CHECK(snd_pcm_drop(pcm) == 0);
    CHECK(stops > 0);
    CHECK(snd_pcm_close(pcm) == 0);
    CHECK(opens == 1 && closes == 1);
    if (shm) munmap(shm, shm_size);
    printf("PASS rate=%u..%u negotiated=%u type=%u shm=%d writes=%u prepares=%u checks=%u\n",
           low, high, rate, data_type, using_shm, writes, prepares, checks);
    return 0;
}
