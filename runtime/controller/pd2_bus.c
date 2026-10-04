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
    struct pd2_controls last_controls;
};

static pthread_mutex_t pd2_lock = PTHREAD_MUTEX_INITIALIZER;
static struct list pd2_events = LIST_INIT(pd2_events);
static struct pd2_gamepad *pd2_pad;
static struct pd2_controls pd2_latest = {.hat = 0xff};
static _Atomic bool pd2_running;
static int pd2_socket = -1;
static struct sockaddr_in pd2_client;
static uint64_t pd2_last_request, pd2_last_reply;

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
    else { pad->last_controls = pd2_latest; pad->has_report = TRUE; }
}

static void pd2_destroy(struct unix_device *device) {
    /* Generic Wine HID destruction owns the descriptor/report buffers and object. */
}

static NTSTATUS pd2_start(struct unix_device *device) {
    struct pd2_gamepad *pad = CONTAINING_RECORD(device, struct pd2_gamepad, device);
    pthread_mutex_lock(&pd2_lock);
    if (!pad->started) {
        pad->started = TRUE;
        if (pad == pd2_pad) queue_controls(pad);
    }
    pthread_mutex_unlock(&pd2_lock);
    return STATUS_SUCCESS;
}

static void pd2_stop_device(struct unix_device *device) {
    struct pd2_gamepad *pad = CONTAINING_RECORD(device, struct pd2_gamepad, device);
    pthread_mutex_lock(&pd2_lock);
    pad->started = pad->connected = FALSE;
    /* A previous PnP removal must never erase a newly connected instance. */
    if (pd2_pad == pad) pd2_pad = NULL;
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
        .vid = PD2_VENDOR_ID, .pid = PD2_PRODUCT_ID, .input = (UINT)-1, .is_gamepad = TRUE,
        .manufacturer = {'P','D','2',' ','A','n','d','r','o','i','d',0},
        .product = {'X','b','o','x',' ','3','6','0',' ','C','o','n','t','r','o','l','l','e','r',0},
        .serialnumber = {'p','d','2','-','x','b','o','x','-','s','l','o','t','0',0}
    };
    if (!connected) {
        pd2_latest = (struct pd2_controls){.hat = 0xff};
        if (pd2_pad) {
            pd2_pad->connected = FALSE;
            if (bus_event_queue_device_removed(&pd2_events, &pd2_pad->device)) pd2_pad = NULL;
            else ERR("PD2 HID removal allocation failed.\n");
        }
        return;
    }
    if (pd2_pad) { pd2_pad->connected = TRUE; return; }
    if (!(pad = hid_device_create(&pd2_vtable, sizeof(*pad)))) return;
    pad->connected = TRUE;
    if (!build_descriptor(&pad->device)
        || !bus_event_queue_device_created(&pd2_events, &pad->device, &description)) {
        pad->device.vtbl->destroy(&pad->device);
        free(pad);
        ERR("PD2 HID device allocation failed.\n");
        return;
    }
    pd2_pad = pad;
    TRACE("PD2 Android virtual Xbox connected on UDP %u, VID %04x PID %04x.\n",
          PD2_HID_PORT, PD2_VENDOR_ID, PD2_PRODUCT_ID);
}

static void apply_packet(const struct pd2_packet *packet) {
    pthread_mutex_lock(&pd2_lock);
    if (packet->type == PD2_DISCOVERY) discover(packet->connected);
    else if (packet->type == PD2_STATE && pd2_pad && pd2_pad->connected) {
        pd2_latest = packet->controls;
        queue_controls(pd2_pad);
    }
    pthread_mutex_unlock(&pd2_lock);
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
                pd2_last_reply = monotonic_millis();
                apply_packet(&packet);
            }
        } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
            ERR("PD2 HID receive failed, errno %d.\n", errno);
            atomic_store(&pd2_running, false);
            break;
        }
        uint64_t now = monotonic_millis();
        if (now - pd2_last_request >= 2000) { send_request(PD2_GET_DEVICE); pd2_last_request = now; }
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
