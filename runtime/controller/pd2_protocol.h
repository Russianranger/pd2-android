/* PD2 Android controller framing. SPDX-License-Identifier: LGPL-2.1-or-later */
#ifndef PD2_CONTROLLER_PROTOCOL_H
#define PD2_CONTROLLER_PROTOCOL_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define PD2_HID_PORT 7950
#define PD2_JAVA_PORT 7947
#define PD2_PACKET_BYTES 256
#define PD2_GET_DEVICE 8
#define PD2_GET_STATE 9
#define PD2_RELEASE_DEVICE 10
#define PD2_VENDOR_ID 0x045e
#define PD2_PRODUCT_ID 0x02a1
#define PD2_EXTENSION_OFFSET 241
#define PD2_EXTENSION_MAGIC UINT32_C(0x32524450) /* PDR2, little endian */
#define PD2_EXTENSION_VERSION 1
#define PD2_BACKEND_ACK 0x50
#define PD2_ACK_BYTES 64
#define PD2_ACK_REMOVAL_STOP_OBSERVED 4

enum pd2_ack_stage {
    PD2_ACK_HEARTBEAT, PD2_ACK_DISCOVERY, PD2_ACK_CREATE_QUEUED,
    PD2_ACK_REMOVE_QUEUED, PD2_ACK_DEVICE_START, PD2_ACK_DEVICE_STOP
};

struct pd2_controls {
    uint16_t buttons;
    uint8_t hat;
    int16_t axes[6];
};

enum pd2_packet_type { PD2_INVALID, PD2_DISCOVERY, PD2_STATE };
struct pd2_packet {
    enum pd2_packet_type type;
    bool connected;
    bool extended;
    uint32_t uid, session_token;
    struct pd2_controls controls;
};

static inline uint16_t pd2_read_u16(const uint8_t *data) {
    return (uint16_t)data[0] | ((uint16_t)data[1] << 8);
}

static inline uint32_t pd2_read_u32(const uint8_t *data) {
    return (uint32_t)data[0] | ((uint32_t)data[1] << 8)
        | ((uint32_t)data[2] << 16) | ((uint32_t)data[3] << 24);
}

static inline void pd2_write_u32(uint8_t *data, uint32_t value) {
    for (size_t index = 0; index < 4; index++) data[index] = value >> (index * 8);
}

static inline void pd2_write_u64(uint8_t *data, uint64_t value) {
    for (size_t index = 0; index < 8; index++) data[index] = value >> (index * 8);
}

/* Old producer packets have a completely empty suffix. A tagged session cannot
   later accept those packets as fresh controls; the receiver enforces ownership. */
static inline bool pd2_parse_extension(const uint8_t *data, struct pd2_packet *out) {
    bool empty = true;
    for (size_t index = PD2_EXTENSION_OFFSET; index < PD2_PACKET_BYTES; index++) empty &= !data[index];
    if (empty) return true;
    if (pd2_read_u32(data + PD2_EXTENSION_OFFSET) != PD2_EXTENSION_MAGIC
        || data[245] != PD2_EXTENSION_VERSION || data[254] || data[255]) return false;
    out->uid = pd2_read_u32(data + 246);
    out->session_token = pd2_read_u32(data + 250);
    /* The Android producer owns positive Java int tokens and nonnegative UIDs. */
    if (!out->session_token || out->session_token > INT32_MAX || out->uid > INT32_MAX) return false;
    out->extended = true;
    return true;
}

/* Java's dedicated producer channel always sends complete, zero-filled records. */
static inline bool pd2_parse_packet(const uint8_t *data, size_t length, struct pd2_packet *out) {
    size_t index;
    if (!data || !out || length != PD2_PACKET_BYTES) return false;
    *out = (struct pd2_packet){0};
    if (!pd2_parse_extension(data, out)) return false;
    if (data[0] == PD2_GET_DEVICE) {
        if (data[1] > 1) return false;
        out->type = PD2_DISCOVERY;
        out->connected = data[1] == 1;
        if (out->connected && (data[2] != 10 || data[3] != 1 || data[9] > 48)) return false;
        return true;
    }
    if (data[0] != PD2_GET_STATE || data[1] != 0) return false;
    if (data[4] != 0xff && data[4] > 7) return false;
    out->type = PD2_STATE;
    out->controls.buttons = pd2_read_u16(data + 2) & 0x03ff;
    out->controls.hat = data[4];
    for (index = 0; index < 6; index++) out->controls.axes[index] = (int16_t)pd2_read_u16(data + 5 + index * 2);
    if (out->controls.axes[4] < 0 || out->controls.axes[5] < 0) return false;
    return true;
}
#endif
