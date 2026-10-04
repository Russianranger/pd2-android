/* Production transport, HID serialization and lifecycle tests. LGPL-2.1-or-later. */
#include "pd2_bus.c"
#include <assert.h>
#include <stdio.h>
#include <signal.h>

/* Wine's logger is supplied by ntdll in production; tests need no running Wine. */
int __cdecl __wine_dbg_header(enum __wine_debug_class level, struct __wine_debug_channel *channel,
                             const char *function) { return -1; }
int __cdecl __wine_dbg_output(const char *text) { return 0; }

static void put16(unsigned char *data, int16_t value) {
    data[0] = (uint16_t)value & 0xff; data[1] = (uint16_t)value >> 8;
}
static int32_t read32(const BYTE *data) {
    return (int32_t)((uint32_t)data[0] | ((uint32_t)data[1] << 8)
                    | ((uint32_t)data[2] << 16) | ((uint32_t)data[3] << 24));
}
static void discovery(unsigned char *packet, BOOL present) {
    memset(packet, 0, PD2_PACKET_BYTES);
    packet[0] = PD2_GET_DEVICE; packet[1] = present;
    packet[2] = 10; packet[3] = 1; packet[9] = 4;
    memcpy(packet + 10, "Thor", 4);
}
static void neutral(unsigned char *packet) {
    memset(packet, 0, PD2_PACKET_BYTES); packet[0] = PD2_GET_STATE; packet[4] = 0xff;
}
static void send_frame(int socket, const void *data, size_t size) {
    struct sockaddr_in address = {.sin_family=AF_INET, .sin_port=htons(PD2_HID_PORT),
                                 .sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    assert(sendto(socket, data, size, 0, (struct sockaddr *)&address, sizeof(address)) == (ssize_t)size);
}

static void *blocked_wait(void *arg) {
    struct bus_event event={0};
    assert(pd2_bus_wait(&event)==STATUS_SUCCESS && event.type==BUS_EVENT_TYPE_NONE);
    return NULL;
}

/* Decode short HID descriptor items independently of Wine's descriptor builder. */
static size_t descriptor_input_bits(const BYTE *data, size_t length) {
    size_t offset=0, bits=0; unsigned int report_size=0, count=0, report=0;
    BOOL gamepad=FALSE; unsigned int page=0;
    while (offset < length) {
        unsigned int prefix=data[offset++], size=prefix & 3, value=0;
        if (size == 3) size=4;
        assert(prefix != 0xfe && size <= length-offset);
        for (unsigned int index=0; index<size; index++) value |= (unsigned int)data[offset+index] << (8*index);
        offset += size;
        switch (prefix & 0xfc) {
            case 0x04: page=value; break;
            case 0x08: if (page==1 && value==5) gamepad=TRUE; break;
            case 0x74: report_size=value; break;
            case 0x84: report=value; break;
            case 0x94: count=value; break;
            case 0x80: assert(report==1); bits += report_size*count; break;
        }
    }
    assert(gamepad);
    return bits;
}

static void parser_tests(void) {
    unsigned char frame[PD2_PACKET_BYTES+1]; struct pd2_packet parsed;
    discovery(frame, TRUE);
    assert(pd2_parse_packet(frame, 256, &parsed) && parsed.connected);
    for (size_t length=0; length<256; length++) assert(!pd2_parse_packet(frame,length,&parsed));
    assert(!pd2_parse_packet(frame,257,&parsed));
    frame[2]=12; assert(!pd2_parse_packet(frame,256,&parsed)); frame[2]=10;
    frame[3]=0; assert(!pd2_parse_packet(frame,256,&parsed)); frame[3]=1;
    frame[9]=49; assert(!pd2_parse_packet(frame,256,&parsed));
    neutral(frame); assert(pd2_parse_packet(frame,256,&parsed) && parsed.controls.hat==255);
    frame[1]=1; assert(!pd2_parse_packet(frame,256,&parsed)); frame[1]=0;
    frame[4]=8; assert(!pd2_parse_packet(frame,256,&parsed)); frame[4]=255;
    put16(frame+13,-1); assert(!pd2_parse_packet(frame,256,&parsed));
    neutral(frame); put16(frame+2,0x0c01); put16(frame+5,-32768); put16(frame+13,32767);
    assert(pd2_parse_packet(frame,256,&parsed));
    assert(parsed.controls.buttons==1 && parsed.controls.axes[0]==-32768 && parsed.controls.axes[4]==32767);
    assert(!pd2_parse_packet(NULL,256,&parsed)); assert(!pd2_parse_packet(frame,256,NULL));
}

int main(void) {
    alarm(15); parser_tests();
    int java=socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP);
    struct sockaddr_in address={.sin_family=AF_INET,.sin_port=htons(PD2_JAVA_PORT),.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    assert(java>=0 && !bind(java,(struct sockaddr*)&address,sizeof(address)));
    assert(pd2_bus_init(NULL)==STATUS_SUCCESS);
    assert(pd2_bus_init(NULL)==STATUS_DEVICE_BUSY);
    assert(pd2_bus_wait(NULL)==STATUS_INVALID_PARAMETER);
    unsigned char request[64], frame[256];
    assert(recv(java,request,sizeof(request),0)==64 && request[0]==PD2_GET_DEVICE);
    struct bus_event event={0};
    discovery(frame,TRUE); send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_CREATED);
    UINT64 first=event.device;
    assert(event.device_created.desc.vid==PD2_VENDOR_ID && event.device_created.desc.pid==PD2_PRODUCT_ID);
    assert(event.device_created.desc.is_gamepad);
    struct unix_device *device=(struct unix_device *)(UINT_PTR)first;
    BYTE descriptor[512]; UINT length=0;
    assert(device->vtbl->get_report_descriptor(device,descriptor,sizeof(descriptor),&length)==STATUS_SUCCESS);
    assert(length>0 && descriptor_input_bits(descriptor,length)==216);
    assert(device->hid_device_state.report_len==28);
    struct device_start_params start={.device=first};
    assert(__wine_unix_call_funcs[device_start](&start)==STATUS_SUCCESS);
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_INPUT_REPORT);
    assert(event.input_report.length==28 && event.input_report.buffer[0]==1);
    assert(event.input_report.buffer[25]==0 && event.input_report.buffer[26]==0);

    neutral(frame); put16(frame+2,3); frame[4]=1;
    put16(frame+5,-32768); put16(frame+7,-123); put16(frame+9,32767); put16(frame+11,42); put16(frame+13,32767);
    send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_INPUT_REPORT);
    assert(read32(event.input_report.buffer+1)==-32768 && read32(event.input_report.buffer+5)==-123);
    assert(read32(event.input_report.buffer+9)==32767 && read32(event.input_report.buffer+13)==42);
    assert(read32(event.input_report.buffer+17)==32767 && read32(event.input_report.buffer+21)==0);
    assert(event.input_report.buffer[25]==2 && event.input_report.buffer[26]==3);
    send_frame(java,frame,sizeof(frame)); /* Identical states must not create another report. */
    neutral(frame); send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_INPUT_REPORT);
    assert(read32(event.input_report.buffer+1)==0 && event.input_report.buffer[25]==0 && event.input_report.buffer[26]==0);
    discovery(frame,FALSE); send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_REMOVED);
    discovery(frame,TRUE); send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_CREATED);
    UINT64 second=event.device; assert(first!=second);
    struct device_remove_params removal={.device=first};
    assert(__wine_unix_call_funcs[device_remove](&removal)==STATUS_SUCCESS);
    assert(pd2_pad && (UINT64)(UINT_PTR)&pd2_pad->device==second);
    start.device=second; assert(__wine_unix_call_funcs[device_start](&start)==STATUS_SUCCESS);
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_INPUT_REPORT);
    /* A vanished Java endpoint must remove its HID instead of retaining input. */
    pd2_last_reply=monotonic_millis()-6001;
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_REMOVED);
    assert(pd2_bus_stop(NULL)==STATUS_SUCCESS);
    assert(pd2_bus_wait(&event)==STATUS_SUCCESS && event.type==BUS_EVENT_TYPE_NONE);
    removal.device=second; assert(__wine_unix_call_funcs[device_remove](&removal)==STATUS_SUCCESS);
    assert(recv(java,request,sizeof(request),0)==64 && request[0]==PD2_RELEASE_DEVICE);

    /* Stop before an advertised creation reaches PE: ASan checks creator cleanup. */
    assert(pd2_bus_init(NULL)==STATUS_SUCCESS);
    assert(recv(java,request,sizeof(request),0)==64 && request[0]==PD2_GET_DEVICE);
    struct pd2_packet packet={.type=PD2_DISCOVERY,.connected=true}; apply_packet(&packet);
    packet.connected=false; apply_packet(&packet);
    packet.connected=true; apply_packet(&packet);
    assert(pd2_bus_stop(NULL)==STATUS_SUCCESS && pd2_bus_wait(&event)==STATUS_SUCCESS);
    assert(recv(java,request,sizeof(request),0)==64 && request[0]==PD2_RELEASE_DEVICE);

    /* Stop wakes a thread already waiting with an empty event queue. */
    assert(pd2_bus_init(NULL)==STATUS_SUCCESS);
    assert(recv(java,request,sizeof(request),0)==64 && request[0]==PD2_GET_DEVICE);
    pthread_t thread; assert(!pthread_create(&thread,NULL,blocked_wait,NULL));
    const struct timespec brief={.tv_nsec=50000000}; nanosleep(&brief,NULL);
    assert(pd2_bus_stop(NULL)==STATUS_SUCCESS && !pthread_join(thread,NULL));
    assert(recv(java,request,sizeof(request),0)==64 && request[0]==PD2_RELEASE_DEVICE);
    close(java);
    puts("controller protocol, real UDP, 28-byte HID descriptor/report, release, replug and shutdown tests passed");
    return 0;
}
