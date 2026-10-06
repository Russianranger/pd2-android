/* Production transport, HID serialization and lifecycle tests. LGPL-2.1-or-later. */
#include "pd2_bus.c"
#include <assert.h>
#include <stdio.h>
#include <signal.h>
#include <poll.h>

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
static uint64_t read64(const BYTE *data) {
    return pd2_read_u32(data) | ((uint64_t)pd2_read_u32(data + 4) << 32);
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
static void tag(unsigned char *frame, uint32_t token, uint32_t uid) {
    pd2_write_u32(frame + PD2_EXTENSION_OFFSET, PD2_EXTENSION_MAGIC);
    frame[245] = PD2_EXTENSION_VERSION;
    pd2_write_u32(frame + 246, uid);
    pd2_write_u32(frame + 250, token);
}
static void receive_code(int socket, unsigned char code, unsigned char *frame) {
    struct pollfd waiting = {.fd = socket, .events = POLLIN};
    for (unsigned int index = 0; index < 32; index++) {
        assert(poll(&waiting, 1, 1000) == 1);
        assert(recv(socket, frame, PD2_ACK_BYTES, 0) == PD2_ACK_BYTES);
        if (frame[0] == code) return;
    }
    assert(!"Expected bounded backend packet was not received");
}

static void drain_acks(int socket, unsigned char *latest) {
    struct pollfd waiting = {.fd = socket, .events = POLLIN};
    while (poll(&waiting, 1, 0) == 1) {
        unsigned char frame[PD2_ACK_BYTES];
        assert(recv(socket, frame, sizeof(frame), 0) == PD2_ACK_BYTES);
        assert(frame[0] == PD2_BACKEND_ACK && frame[1] == PD2_EXTENSION_VERSION);
        assert(pd2_read_u32(frame + 4) == PD2_EXTENSION_MAGIC);
        memcpy(latest, frame, sizeof(frame));
    }
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
    discovery(frame, TRUE); tag(frame, 1234, 0);
    assert(pd2_parse_packet(frame,256,&parsed) && parsed.extended && parsed.session_token==1234 && !parsed.uid);
    tag(frame, 1234, 2); assert(pd2_parse_packet(frame,256,&parsed) && parsed.uid==2);
    frame[241] ^= 1; assert(!pd2_parse_packet(frame,256,&parsed)); frame[241] ^= 1;
    frame[245] = 2; assert(!pd2_parse_packet(frame,256,&parsed)); frame[245] = 1;
    frame[254] = 1; assert(!pd2_parse_packet(frame,256,&parsed)); frame[254] = 0;
    frame[255] = 1; assert(!pd2_parse_packet(frame,256,&parsed)); frame[255] = 0;
    tag(frame, 0, 2); assert(!pd2_parse_packet(frame,256,&parsed));
    tag(frame, UINT32_C(0x80000000), 2); assert(!pd2_parse_packet(frame,256,&parsed));
    tag(frame, 1234, UINT32_C(0x80000000)); assert(!pd2_parse_packet(frame,256,&parsed));
    tag(frame, INT32_MAX, INT32_MAX); assert(pd2_parse_packet(frame,256,&parsed));
    neutral(frame); tag(frame, 1234, 2);
    assert(pd2_parse_packet(frame,256,&parsed) && parsed.type==PD2_STATE && parsed.uid==2);
}

static void fresh_identity_tests(int java) {
    unsigned char frame[PD2_PACKET_BYTES], ack[PD2_ACK_BYTES] = {0};
    struct pd2_packet packet;
    struct bus_event event = {0};
    const uint32_t token = 0x1234abcd;
    assert(pd2_bus_init(NULL)==STATUS_SUCCESS);
    receive_code(java, PD2_GET_DEVICE, ack);
    /* Controls cannot claim session ownership before the discovery handshake. */
    neutral(frame); tag(frame, token, 0); assert(pd2_parse_packet(frame,256,&packet));
    assert(!apply_packet(&packet) && !pd2_session_token);
    discovery(frame, TRUE); tag(frame, token, 0); send_frame(java, frame, sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_CREATED);
    UINT64 first = event.device;
    assert(event.device_created.desc.uid==0);
    drain_acks(java, ack);
    assert(pd2_read_u32(ack+8)==token && pd2_read_u32(ack+12)==0);
    assert(pd2_read_u32(ack+16)==(uint32_t)getpid() && read64(ack+48)==pd2_socket_inode && pd2_socket_inode);
    assert(pd2_read_u32(ack+20)==1 && ack[2]==1 && ack[3]==PD2_ACK_DISCOVERY);
    struct device_start_params start = {.device=first};
    assert(__wine_unix_call_funcs[device_start](&start)==STATUS_SUCCESS);
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_INPUT_REPORT);
    drain_acks(java, ack);
    assert(ack[3]==PD2_ACK_DEVICE_START && ack[2]==3 && pd2_read_u32(ack+28)==1);
    /* Same identity is idempotent; future UID cannot replace a connected pad. */
    discovery(frame, TRUE); tag(frame, token, 0); assert(pd2_parse_packet(frame,256,&packet));
    assert(apply_packet(&packet) && pd2_created==1);
    tag(frame, token, 1); assert(pd2_parse_packet(frame,256,&packet));
    assert(!apply_packet(&packet) && pd2_uid==0 && pd2_created==1);
    discovery(frame, FALSE); tag(frame, token, 0); send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_REMOVED);
    drain_acks(java, ack);
    assert(ack[2]==0 && pd2_read_u32(ack+12)==0 && pd2_read_u32(ack+24)==1);
    assert(pd2_read_u32(ack+32)==0); /* Queued absence does not claim the stop callback ran. */
    discovery(frame, TRUE); tag(frame, token, 1); assert(pd2_parse_packet(frame,256,&packet));
    assert(!apply_packet(&packet) && !pd2_uid && !pd2_retirement_stopped);
    struct device_remove_params removal = {.device=first};
    assert(__wine_unix_call_funcs[device_remove](&removal)==STATUS_SUCCESS);
    drain_acks(java, ack);
    assert(ack[3]==PD2_ACK_DEVICE_STOP && ack[2]==PD2_ACK_REMOVAL_STOP_OBSERVED);
    assert(pd2_read_u32(ack+24)==1 && pd2_read_u32(ack+32)==1);
    discovery(frame, TRUE); tag(frame, token, 1); send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_CREATED);
    UINT64 second = event.device;
    assert(event.device_created.desc.uid==1 && pd2_created==2 && pd2_removed==1);
    assert(pd2_pad && pd2_pad->uid==1 && pd2_stopped==1);
    start.device=second; assert(__wine_unix_call_funcs[device_start](&start)==STATUS_SUCCESS);
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_INPUT_REPORT);
    drain_acks(java, ack);
    assert(ack[2]==3 && ack[3]==PD2_ACK_DEVICE_START && pd2_read_u32(ack+12)==1);
    assert(pd2_read_u32(ack+20)==2 && pd2_read_u32(ack+24)==1 && pd2_read_u32(ack+28)==2 && pd2_read_u32(ack+32)==1);
    /* Wrong session, stale identity and untagged controls cannot replay after recovery. */
    neutral(frame); put16(frame+2,1); tag(frame, token+1, 1); assert(pd2_parse_packet(frame,256,&packet));
    assert(!apply_packet(&packet));
    tag(frame, token, 0); assert(pd2_parse_packet(frame,256,&packet)); assert(!apply_packet(&packet));
    neutral(frame); put16(frame+2,1); assert(pd2_parse_packet(frame,256,&packet)); assert(!apply_packet(&packet));
    discovery(frame, FALSE); tag(frame, token, 0); assert(pd2_parse_packet(frame,256,&packet));
    assert(!apply_packet(&packet) && pd2_pad->connected);
    neutral(frame); put16(frame+2,1); tag(frame, token, 1); send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_INPUT_REPORT);
    const BYTE *received_report=(const BYTE *)&event + offsetof(struct bus_event, input_report.buffer);
    assert(event.input_report.length==28 && received_report[26]==1 && pd2_states_received==1);
    uint32_t reports=pd2_reports_queued;
    assert(pd2_parse_packet(frame,256,&packet) && apply_packet(&packet));
    assert(pd2_reports_queued==reports && pd2_states_received==2);
    discovery(frame, TRUE); tag(frame, token, 1); assert(pd2_parse_packet(frame,256,&packet));
    assert(apply_packet(&packet) && pd2_created==2);
    drain_acks(java, ack);
    assert(ack[2]==3 && pd2_read_u32(ack+44)>=5 && pd2_read_u32(ack+36)==2);
    assert(pd2_bus_stop(NULL)==STATUS_SUCCESS && pd2_bus_wait(&event)==STATUS_SUCCESS);
    receive_code(java, PD2_RELEASE_DEVICE, ack);
    removal.device=second; assert(__wine_unix_call_funcs[device_remove](&removal)==STATUS_SUCCESS);
    /* A subsequent Wine bus starts baseline UID/token again, with a new socket. */
    uint64_t previous_inode=pd2_socket_inode;
    assert(pd2_bus_init(NULL)==STATUS_SUCCESS);
    receive_code(java, PD2_GET_DEVICE, ack);
    assert(!pd2_uid && !pd2_session_token && !pd2_created && !pd2_started && pd2_socket_inode!=previous_inode);
    assert(pd2_bus_stop(NULL)==STATUS_SUCCESS && pd2_bus_wait(&event)==STATUS_SUCCESS);
    receive_code(java, PD2_RELEASE_DEVICE, ack);
}

/* Ordinary reconnect may reuse UID0 while its old PnP callback is still pending.
   Exercise that callback on both sides of the current removal boundary. */
static void same_uid_retirement_tests(int java, BOOL old_stop_before_current_removal) {
    const uint32_t token = 42;
    unsigned char frame[PD2_PACKET_BYTES], ack[PD2_ACK_BYTES]={0};
    struct pd2_packet packet;
    struct bus_event event={0};
    assert(pd2_bus_init(NULL)==STATUS_SUCCESS);
    receive_code(java,PD2_GET_DEVICE,ack);
    discovery(frame,TRUE);tag(frame,token,0);send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_CREATED);
    UINT64 old=event.device;
    struct device_start_params start={.device=old};
    assert(__wine_unix_call_funcs[device_start](&start)==STATUS_SUCCESS);
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_INPUT_REPORT);
    discovery(frame,FALSE);tag(frame,token,0);send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_REMOVED);
    discovery(frame,TRUE);tag(frame,token,0);send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_CREATED);
    UINT64 current=event.device;assert(current!=old && event.device_created.desc.uid==0);
    start.device=current;assert(__wine_unix_call_funcs[device_start](&start)==STATUS_SUCCESS);
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_INPUT_REPORT);
    drain_acks(java,ack);assert(ack[2]==3);
    uint32_t stop_baseline=pd2_stopped, remove_baseline=pd2_removed;
    struct device_remove_params removal={.device=old};
    if(old_stop_before_current_removal) {
        assert(__wine_unix_call_funcs[device_remove](&removal)==STATUS_SUCCESS);
        assert(pd2_pad && pd2_stopped==stop_baseline+1);
        drain_acks(java,ack);assert(ack[2]==3);
    }
    discovery(frame,FALSE);tag(frame,token,0);send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_REMOVED);
    drain_acks(java,ack);
    assert(ack[2]==0 && pd2_removed==remove_baseline+1 && !pd2_retirement_stopped);
    if(!old_stop_before_current_removal) {
        assert(__wine_unix_call_funcs[device_remove](&removal)==STATUS_SUCCESS);
        assert(pd2_stopped==stop_baseline && !pd2_retirement_stopped);
    }
    /* Counts from a historical stop alone cannot authorize this fresh identity. */
    discovery(frame,TRUE);tag(frame,token,1);assert(pd2_parse_packet(frame,256,&packet));
    assert(!apply_packet(&packet) && !pd2_uid && pd2_created==2);
    send_ack(PD2_ACK_HEARTBEAT,0);drain_acks(java,ack);
    assert(ack[2]==0);
    if(old_stop_before_current_removal) assert(pd2_read_u32(ack+32)>stop_baseline);
    removal.device=current;assert(__wine_unix_call_funcs[device_remove](&removal)==STATUS_SUCCESS);
    drain_acks(java,ack);
    assert(ack[2]==PD2_ACK_REMOVAL_STOP_OBSERVED && ack[3]==PD2_ACK_DEVICE_STOP);
    assert(pd2_read_u32(ack+24)>remove_baseline && pd2_read_u32(ack+32)>stop_baseline);
    send_ack(PD2_ACK_HEARTBEAT,0);drain_acks(java,ack);
    assert(ack[2]==PD2_ACK_REMOVAL_STOP_OBSERVED && ack[3]==PD2_ACK_HEARTBEAT);
    discovery(frame,FALSE);tag(frame,token,0);assert(pd2_parse_packet(frame,256,&packet));
    assert(apply_packet(&packet));drain_acks(java,ack);
    assert(ack[2]==PD2_ACK_REMOVAL_STOP_OBSERVED); /* Duplicate absence retains proof. */
    discovery(frame,TRUE);tag(frame,token,1);send_frame(java,frame,sizeof(frame));
    assert(pd2_bus_wait(&event)==STATUS_PENDING && event.type==BUS_EVENT_TYPE_DEVICE_CREATED);
    UINT64 fresh=event.device;
    assert(event.device_created.desc.uid==1 && !pd2_retirement_stopped);
    drain_acks(java,ack);assert(ack[2]==1);
    assert(pd2_bus_stop(NULL)==STATUS_SUCCESS && pd2_bus_wait(&event)==STATUS_SUCCESS);
    receive_code(java,PD2_RELEASE_DEVICE,ack);
    removal.device=fresh;assert(__wine_unix_call_funcs[device_remove](&removal)==STATUS_SUCCESS);
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
    fresh_identity_tests(java);
    same_uid_retirement_tests(java,FALSE);
    same_uid_retirement_tests(java,TRUE);
    close(java);
    puts("controller protocol, real UDP, 28-byte HID descriptor/report, release, replug and shutdown tests passed");
    return 0;
}
