/*
 * Android virtual HID producer for the Wine 9.2 Unix Winebus ABI.
 * Report construction derives from Winlator's bus_winlator.c:
 * Copyright 2026 BrunoSX. Copyright 2026 PD2 Android contributors.
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#include "config.h"
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <arpa/inet.h>
#include <errno.h>
#include <pthread.h>
#include <time.h>
#include "ntstatus.h"
#define WIN32_NO_STATUS
#include "windef.h"
#include "winbase.h"
#include "winternl.h"
#include "hidusage.h"
#include "wine/debug.h"
#include "wine/hid.h"
#include "wine/unixlib.h"
#include "unix_private.h"
#include "pd2_protocol.h"

WINE_DEFAULT_DEBUG_CHANNEL(hid);

struct pd2_gamepad {
    struct unix_device device;
    BOOL started;
    BOOL connected;
    BOOL has_report;
    uint32_t uid, session_token;
    struct pd2_controls last_controls;
};

static pthread_mutex_t pd2_lock = PTHREAD_MUTEX_INITIALIZER;
static struct list pd2_events = LIST_INIT(pd2_events);
static struct pd2_gamepad *pd2_pad;
/* Non-owning: Wine's delivered-device / queued-event references keep this exact
   object alive until its stop callback. Shutdown clears it before queue cleanup. */
static struct pd2_gamepad *pd2_retiring_pad;
static BOOL pd2_retirement_stopped;
static struct pd2_controls pd2_latest = {.hat = 0xff};
static _Atomic bool pd2_running;
static int pd2_socket = -1;
static struct sockaddr_in pd2_client;
static uint64_t pd2_last_request, pd2_last_reply;
static uint32_t pd2_session_token, pd2_uid, pd2_backend_pid;
static uint64_t pd2_socket_inode;
static uint32_t pd2_created, pd2_removed, pd2_started, pd2_stopped;
static uint32_t pd2_states_received, pd2_reports_queued, pd2_invalid_packets;

static uint64_t monotonic_millis(void) {
    struct timespec time;
    if (clock_gettime(CLOCK_MONOTONIC, &time)) return 0;
    return (uint64_t)time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

static void send_request(unsigned char code) {
    unsigned char packet[64] = {0};
    packet[0] = code;
    if (pd2_socket >= 0)
        sendto(pd2_socket, packet, sizeof(packet), 0, (struct sockaddr *)&pd2_client, sizeof(pd2_client));
}

static void increment_count(uint32_t *count) {
    if (*count != UINT32_MAX) ++*count;
}

/* Called with pd2_lock held. These bounded observations contain no input values.
   Device-start means the Unix callback ran, not completed HID enumeration. */
static void send_ack(enum pd2_ack_stage stage, uint32_t uid) {
    unsigned char packet[PD2_ACK_BYTES] = {0};
    if (!pd2_session_token || pd2_socket < 0) return;
    packet[0] = PD2_BACKEND_ACK;
    packet[1] = PD2_EXTENSION_VERSION;
    if (pd2_pad && pd2_pad->uid == uid) {
        packet[2] = (pd2_pad->connected ? 1 : 0) | (pd2_pad->started ? 2 : 0);
    }
    if (!pd2_pad && uid == pd2_uid && pd2_retirement_stopped)
        packet[2] |= PD2_ACK_REMOVAL_STOP_OBSERVED;
    packet[3] = stage;
    pd2_write_u32(packet + 4, PD2_EXTENSION_MAGIC);
    pd2_write_u32(packet + 8, pd2_session_token);
    pd2_write_u32(packet + 12, uid);
    pd2_write_u32(packet + 16, pd2_backend_pid);
    pd2_write_u32(packet + 20, pd2_created);
    pd2_write_u32(packet + 24, pd2_removed);
    pd2_write_u32(packet + 28, pd2_started);
    pd2_write_u32(packet + 32, pd2_stopped);
    pd2_write_u32(packet + 36, pd2_states_received);
    pd2_write_u32(packet + 40, pd2_reports_queued);
    pd2_write_u32(packet + 44, pd2_invalid_packets);
    pd2_write_u64(packet + 48, pd2_socket_inode);
    pd2_write_u64(packet + 56, monotonic_millis());
    sendto(pd2_socket, packet, sizeof(packet), 0, (struct sockaddr *)&pd2_client, sizeof(pd2_client));
}

/* Called with pd2_lock held; Wine queues copy the completed report. */
static void queue_controls(struct pd2_gamepad *pad) {
    LONG x = 0, y = 0;
    struct unix_device *device = &pad->device;
    struct hid_device_state *state = &device->hid_device_state;
    unsigned int index;
    if (!pad->started || !pad->connected || !state->report_buf || !state->report_len) return;
    BOOL changed = !pad->has_report || pad->last_controls.buttons != pd2_latest.buttons
        || pad->last_controls.hat != pd2_latest.hat;
    for (index = 0; index < 6; index++) changed |= pad->last_controls.axes[index] != pd2_latest.axes[index];
    if (!changed) return;
    for (index = 0; index < 10; index++) hid_device_set_button(device, index, !!(pd2_latest.buttons & (1u << index)));
    switch (pd2_latest.hat) {
        case 0: y = -1; break;
        case 1: x = 1; y = -1; break;
        case 2: x = 1; break;
        case 3: x = 1; y = 1; break;
        case 4: y = 1; break;
        case 5: x = -1; y = 1; break;
        case 6: x = -1; break;
        case 7: x = -1; y = -1; break;
    }
    hid_device_set_hatswitch_x(device, 0, x);
    hid_device_set_hatswitch_y(device, 0, y);
    for (index = 0; index < 6; index++) hid_device_set_abs_axis(device, index, pd2_latest.axes[index]);
    if (!bus_event_queue_input_report(&pd2_events, device, state->report_buf, state->report_len))
        ERR("PD2 HID report allocation failed.\n");
    else {
        pad->last_controls = pd2_latest; pad->has_report = TRUE;
        increment_count(&pd2_reports_queued);
    }
}

static void pd2_destroy(struct unix_device *device) {
    /* Generic Wine HID destruction owns the descriptor/report buffers and object. */
}

static NTSTATUS pd2_start(struct unix_device *device) {
    struct pd2_gamepad *pad = CONTAINING_RECORD(device, struct pd2_gamepad, device);
    pthread_mutex_lock(&pd2_lock);
    if (!pad->started) {
        pad->started = TRUE;
        if (pad == pd2_pad) {
            queue_controls(pad);
            increment_count(&pd2_started);
            send_ack(PD2_ACK_DEVICE_START, pad->uid);
        }
    }
    pthread_mutex_unlock(&pd2_lock);
    return STATUS_SUCCESS;
}

static void pd2_stop_device(struct unix_device *device) {
    struct pd2_gamepad *pad = CONTAINING_RECORD(device, struct pd2_gamepad, device);
    pthread_mutex_lock(&pd2_lock);
    BOOL owned = pd2_pad == pad, retiring = pd2_retiring_pad == pad;
    pad->started = pad->connected = FALSE;
    /* A previous PnP removal must never erase a newly connected instance. */
    if (owned) { pd2_pad = NULL; pd2_retirement_stopped = FALSE; }
    if (retiring) { pd2_retiring_pad = NULL; pd2_retirement_stopped = TRUE; }
    /* Same-UID replug objects are distinct too. A stale callback cannot confirm
       a later object's removal, even if its UID and session token are identical. */
    if (pad->session_token == pd2_session_token && (owned || retiring)) {
        increment_count(&pd2_stopped);
        send_ack(PD2_ACK_DEVICE_STOP, pad->uid);
    }
    pthread_mutex_unlock(&pd2_lock);
}

static NTSTATUS pd2_haptics_start(struct unix_device *device, UINT duration, USHORT rumble,
                                USHORT buzz, USHORT left, USHORT right) { return STATUS_NOT_SUPPORTED; }
static NTSTATUS pd2_haptics_stop(struct unix_device *device) { return STATUS_NOT_SUPPORTED; }
static NTSTATUS pd2_physical_control(struct unix_device *device, USAGE control) { return STATUS_NOT_SUPPORTED; }
static NTSTATUS pd2_physical_gain(struct unix_device *device, BYTE percent) { return STATUS_NOT_SUPPORTED; }
static NTSTATUS pd2_effect_control(struct unix_device *device, BYTE index, USAGE control,
                                  BYTE iterations) { return STATUS_NOT_SUPPORTED; }
static NTSTATUS pd2_effect_update(struct unix_device *device, BYTE index,
                                 struct effect_params *params) { return STATUS_NOT_SUPPORTED; }

static const struct hid_device_vtbl pd2_vtable = {
    pd2_destroy, pd2_start, pd2_stop_device, pd2_haptics_start, pd2_haptics_stop,
    pd2_physical_control, pd2_physical_gain, pd2_effect_control, pd2_effect_update
};

static BOOL build_descriptor(struct unix_device *device) {
    const USAGE_AND_PAGE gamepad = {.UsagePage = HID_USAGE_PAGE_GENERIC, .Usage = HID_USAGE_GENERIC_GAMEPAD};
    const USAGE left[] = {HID_USAGE_GENERIC_X, HID_USAGE_GENERIC_Y};
    const USAGE right[] = {HID_USAGE_GENERIC_RX, HID_USAGE_GENERIC_RY};
    const USAGE triggers[] = {HID_USAGE_GENERIC_Z, HID_USAGE_GENERIC_RZ};
    return hid_device_begin_report_descriptor(device, &gamepad)
        && hid_device_begin_input_report(device, &gamepad)
        && hid_device_add_axes(device, 2, HID_USAGE_PAGE_GENERIC, left, FALSE, -32768, 32767)
        && hid_device_add_axes(device, 2, HID_USAGE_PAGE_GENERIC, right, FALSE, -32768, 32767)
        && hid_device_add_axes(device, 2, HID_USAGE_PAGE_GENERIC, triggers, FALSE, 0, 32767)
        && hid_device_add_hatswitch(device, 1)
        && hid_device_add_buttons(device, HID_USAGE_PAGE_BUTTON, 1, 10)
        && hid_device_end_input_report(device)
        && hid_device_end_report_descriptor(device);
}

/* Called with pd2_lock held. Only slot zero is exposed, matching legacy XInput. */
static void discover(BOOL connected) {
    struct pd2_gamepad *pad;
    struct device_desc description = {
        .vid = PD2_VENDOR_ID, .pid = PD2_PRODUCT_ID, .uid = pd2_uid,
        .input = (UINT)-1, .is_gamepad = TRUE,
        .manufacturer = {'P','D','2',' ','A','n','d','r','o','i','d',0},
        .product = {'X','b','o','x',' ','3','6','0',' ','C','o','n','t','r','o','l','l','e','r',0},
        .serialnumber = {'p','d','2','-','x','b','o','x','-','s','l','o','t','0',0}
    };
    if (!connected) {
        pd2_latest = (struct pd2_controls){.hat = 0xff};
        if (pd2_pad) {
            pd2_pad->connected = FALSE;
            if (bus_event_queue_device_removed(&pd2_events, &pd2_pad->device)) {
                pd2_retiring_pad = pd2_pad;
                pd2_retirement_stopped = FALSE;
                pd2_pad = NULL;
                increment_count(&pd2_removed);
                send_ack(PD2_ACK_REMOVE_QUEUED, pd2_uid);
            }
            else ERR("PD2 HID removal allocation failed.\n");
        }
        return;
    }
    if (pd2_pad) { pd2_pad->connected = TRUE; return; }
    if (!(pad = hid_device_create(&pd2_vtable, sizeof(*pad)))) return;
    pad->connected = TRUE;
    pad->uid = pd2_uid;
    pad->session_token = pd2_session_token;
    if (!build_descriptor(&pad->device)
        || !bus_event_queue_device_created(&pd2_events, &pad->device, &description)) {
        pad->device.vtbl->destroy(&pad->device);
        free(pad);
        ERR("PD2 HID device allocation failed.\n");
        return;
    }
    pd2_pad = pad;
    pd2_retirement_stopped = FALSE;
    increment_count(&pd2_created);
    send_ack(PD2_ACK_CREATE_QUEUED, pad->uid);
    TRACE("PD2 Android virtual Xbox connected on UDP %u, VID %04x PID %04x.\n",
          PD2_HID_PORT, PD2_VENDOR_ID, PD2_PRODUCT_ID);
}

static BOOL apply_packet(const struct pd2_packet *packet) {
    BOOL accepted = FALSE;
    pthread_mutex_lock(&pd2_lock);
    if (packet->extended) {
        if (!pd2_session_token) {
            /* A session handshake must precede controls; UID zero preserves the
               accepted initial Windows identity. Never adopt an unsolicited reset. */
            if (packet->type != PD2_DISCOVERY || packet->uid) goto rejected;
            pd2_session_token = packet->session_token;
            if (pd2_pad) pd2_pad->session_token = pd2_session_token;
        }
        if (packet->session_token != pd2_session_token || packet->uid < pd2_uid) goto rejected;
        if (packet->uid > pd2_uid) {
            /* Removal must be queued before creating a different instance. A
               repeated discovery of the current UID is idempotent. */
            if (packet->type != PD2_DISCOVERY || !packet->connected || pd2_pad
                || !pd2_retirement_stopped) goto rejected;
            pd2_uid = packet->uid;
            pd2_retirement_stopped = FALSE;
        }
    }
    else if (pd2_session_token) goto rejected;
    if (packet->type == PD2_DISCOVERY) {
        discover(packet->connected);
        send_ack(PD2_ACK_DISCOVERY, pd2_uid);
    }
    else if (packet->type == PD2_STATE && pd2_pad && pd2_pad->connected) {
        increment_count(&pd2_states_received);
        pd2_latest = packet->controls;
        queue_controls(pd2_pad);
    }
    accepted = TRUE;
    goto done;
rejected:
    increment_count(&pd2_invalid_packets);
done:
    pthread_mutex_unlock(&pd2_lock);
    return accepted;
}

/* Replaces the unused SDL backend's three existing ABI slots, never its PE driver. */
NTSTATUS pd2_bus_init(void *ignored) {
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK),
                                  .sin_port = htons(PD2_HID_PORT)};
    if (atomic_exchange(&pd2_running, true)) return STATUS_DEVICE_BUSY;
    pd2_socket = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, IPPROTO_UDP);
    if (pd2_socket < 0 || bind(pd2_socket, (struct sockaddr *)&address, sizeof(address))) {
        if (pd2_socket >= 0) close(pd2_socket);
        pd2_socket = -1;
        atomic_store(&pd2_running, false);
        ERR("PD2 HID could not bind loopback port %u.\n", PD2_HID_PORT);
        return STATUS_UNSUCCESSFUL;
    }
    pd2_client = (struct sockaddr_in){.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK),
                                    .sin_port = htons(PD2_JAVA_PORT)};
    pthread_mutex_lock(&pd2_lock);
    pd2_retiring_pad = NULL;
    pd2_retirement_stopped = FALSE;
    pd2_session_token = pd2_uid = 0;
    pd2_created = pd2_removed = pd2_started = pd2_stopped = 0;
    pd2_states_received = pd2_reports_queued = pd2_invalid_packets = 0;
    pd2_backend_pid = (uint32_t)getpid();
    struct stat socket_stat;
    pd2_socket_inode = !fstat(pd2_socket, &socket_stat) ? (uint64_t)socket_stat.st_ino : 0;
    pthread_mutex_unlock(&pd2_lock);
    pd2_last_reply = pd2_last_request = monotonic_millis();
    send_request(PD2_GET_DEVICE);
    return STATUS_SUCCESS;
}

NTSTATUS pd2_bus_wait(void *args) {
    struct bus_event *event = args;
    const struct timespec idle = {.tv_nsec = 16000000};
    if (!event) return STATUS_INVALID_PARAMETER;
    bus_event_cleanup(event);
    event->type = BUS_EVENT_TYPE_NONE;
    while (atomic_load(&pd2_running)) {
        unsigned char buffer[PD2_PACKET_BYTES + 1];
        struct sockaddr_in sender = {0};
        socklen_t sender_length = sizeof(sender);
        struct pd2_packet packet;
        ssize_t length;
        pthread_mutex_lock(&pd2_lock);
        BOOL pending = bus_event_queue_pop(&pd2_events, event);
        pthread_mutex_unlock(&pd2_lock);
        if (pending) return STATUS_PENDING;
        length = recvfrom(pd2_socket, buffer, sizeof(buffer), 0, (struct sockaddr *)&sender, &sender_length);
        if (length >= 0) {
            if (sender_length == sizeof(sender) && sender.sin_family == AF_INET
                && sender.sin_addr.s_addr == htonl(INADDR_LOOPBACK) && sender.sin_port == htons(PD2_JAVA_PORT)
                && pd2_parse_packet(buffer, (size_t)length, &packet)) {
                if (apply_packet(&packet)) pd2_last_reply = monotonic_millis();
            } else {
                pthread_mutex_lock(&pd2_lock);
                increment_count(&pd2_invalid_packets);
                pthread_mutex_unlock(&pd2_lock);
            }
        } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
            ERR("PD2 HID receive failed, errno %d.\n", errno);
            atomic_store(&pd2_running, false);
            break;
        }
        uint64_t now = monotonic_millis();
        if (now - pd2_last_request >= 2000) {
            send_request(PD2_GET_DEVICE); pd2_last_request = now;
            pthread_mutex_lock(&pd2_lock);
            send_ack(PD2_ACK_HEARTBEAT, pd2_uid);
            pthread_mutex_unlock(&pd2_lock);
        }
        if (now - pd2_last_reply >= 6000) {
            pthread_mutex_lock(&pd2_lock);
            discover(FALSE);
            pthread_mutex_unlock(&pd2_lock);
        }
        if (length < 0) nanosleep(&idle, NULL);
    }
    send_request(PD2_RELEASE_DEVICE);
    if (pd2_socket >= 0) close(pd2_socket);
    pd2_socket = -1;
    pthread_mutex_lock(&pd2_lock);
    if (pd2_pad) pd2_pad->connected = FALSE;
    pd2_pad = NULL;
    pd2_retiring_pad = NULL;
    pd2_retirement_stopped = FALSE;
    pd2_latest = (struct pd2_controls){.hat = 0xff};
    /* A queued creation has not transferred its initial reference to the PE
       driver yet. Drop that creator reference as well as each queued reference.
       Already-delivered devices remain owned by PE until device_remove. */
    struct bus_event pending;
    while (bus_event_queue_pop(&pd2_events, &pending)) {
        BOOL unadvertised = pending.type == BUS_EVENT_TYPE_DEVICE_CREATED;
        bus_event_cleanup(&pending);
        if (unadvertised) bus_event_cleanup(&pending);
    }
    pthread_mutex_unlock(&pd2_lock);
    return STATUS_SUCCESS;
}

NTSTATUS pd2_bus_stop(void *ignored) {
    /* Wait owns the descriptor; never close/reuse its fd from a competing thread. */
    atomic_store(&pd2_running, false);
    return STATUS_SUCCESS;
}
