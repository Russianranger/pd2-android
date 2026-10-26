/* Deterministic host execution of the actual decoder and reader lifecycle.
 * Fake Win32 transport/scheduler outcomes, not a second state-machine model.
 * LGPL-2.1-or-later. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <pthread.h>
#include <stdlib.h>
#include <wchar.h>
typedef int BOOL; typedef uint32_t DWORD; typedef uint16_t WORD; typedef int16_t SHORT;
typedef uint8_t BYTE; typedef wchar_t WCHAR; typedef uintptr_t ULONG_PTR; typedef void *HMODULE;
typedef pthread_mutex_t CRITICAL_SECTION;
typedef struct { WORD wButtons; BYTE bLeftTrigger,bRightTrigger; SHORT sThumbLX,sThumbLY,sThumbRX,sThumbRY; } XINPUT_GAMEPAD;
typedef struct { DWORD dwPacketNumber; XINPUT_GAMEPAD Gamepad; } XINPUT_STATE;
typedef struct { int unused; } XINPUT_CAPABILITIES;
struct fake_handle { int type,signaled,alive; }; typedef struct fake_handle *HANDLE;
struct sockaddr { uint16_t family; char bytes[14]; };
struct sockaddr_in { uint16_t sin_family,sin_port; struct { uint32_t s_addr; } sin_addr; char zero[8]; };
#define TRUE 1
#define FALSE 0
#define WINAPI
#define INVALID_SOCKET -1
#define SOCKET_ERROR -1
#define AF_INET 2
#define INADDR_LOOPBACK 0x7f000001u
#define CLIENT_PORT 7947
#define BUFFER_SIZE 64
#define REQUEST_CODE_GET_GAMEPAD 8
#define REQUEST_CODE_GET_GAMEPAD_STATE 9
#define REQUEST_CODE_RELEASE_GAMEPAD 10
#define WSAEWOULDBLOCK 10035
#define WSAEINTR 10004
#define WSAECONNRESET 10054
#define WSAENOTSOCK 10038
#define WSAEMSGSIZE 10040
#define WAIT_OBJECT_0 0
#define WAIT_TIMEOUT 258
#define GET_MODULE_HANDLE_EX_FLAG_PIN 1
#define GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS 4
#define ERROR_SUCCESS 0
#define ERROR_DEVICE_NOT_CONNECTED 1167
#define ERROR_BAD_ARGUMENTS 160
#define XUSER_MAX_COUNT 4
#define XINPUT_GAMEPAD_DPAD_UP 1
#define XINPUT_GAMEPAD_DPAD_DOWN 2
#define XINPUT_GAMEPAD_DPAD_LEFT 4
#define XINPUT_GAMEPAD_DPAD_RIGHT 8
#define XINPUT_GAMEPAD_START 16
#define XINPUT_GAMEPAD_BACK 32
#define XINPUT_GAMEPAD_LEFT_THUMB 64
#define XINPUT_GAMEPAD_RIGHT_THUMB 128
#define XINPUT_GAMEPAD_LEFT_SHOULDER 256
#define XINPUT_GAMEPAD_RIGHT_SHOULDER 512
#define XINPUT_GAMEPAD_A 4096
#define XINPUT_GAMEPAD_B 8192
#define XINPUT_GAMEPAD_X 16384
#define XINPUT_GAMEPAD_Y 32768
#define IDX_BUTTON_A 0
#define IDX_BUTTON_B 1
#define IDX_BUTTON_X 2
#define IDX_BUTTON_Y 3
#define IDX_BUTTON_L1 4
#define IDX_BUTTON_R1 5
#define IDX_BUTTON_SELECT 6
#define IDX_BUTTON_START 7
#define IDX_BUTTON_L3 8
#define IDX_BUTTON_R3 9
#define IDX_BUTTON_L2 10
#define IDX_BUTTON_R2 11
static struct { CRITICAL_SECTION crit; XINPUT_CAPABILITIES caps; XINPUT_STATE state; XINPUT_GAMEPAD last_keystroke; BOOL enabled,connected; int id; } controller;
static int server_sock=INVALID_SOCKET;
static char xinput_min_index=3;
static DWORD now=100, heartbeat_at[256], wait_times[256];
static int mode,step,last_error,live_sockets,max_sockets,open_attempts,close_calls,requests,wait_calls;
static int event_fail,thread_fail,pin_fail,create_calls,event_calls,handle_closes,logs;
static _Thread_local int critical_depth;
static int shutdown_in_receive,shutdown_in_open,open_failures;
static struct fake_handle fake_event,fake_thread;
static DWORD GetTickCount(void) { return now; }
static DWORD GetCurrentProcessId(void) { return 236; }
static HANDLE GetCurrentThread(void) { return &fake_thread; }
static void pd2_set_thread_description(HANDLE thread,const WCHAR *name) { (void)thread;(void)name; }
static void EnterCriticalSection(CRITICAL_SECTION *c) { pthread_mutex_lock(c); __sync_add_and_fetch(&critical_depth,1); }
static void LeaveCriticalSection(CRITICAL_SECTION *c) { __sync_sub_and_fetch(&critical_depth,1); pthread_mutex_unlock(c); }
static uint16_t htons(uint16_t x) { return (uint16_t)((x<<8)|(x>>8)); }
static uint32_t htonl(uint32_t x) { return __builtin_bswap32(x); }
static void OutputDebugStringA(const char *line) { assert(strstr(line,"PD2_XINPUT_READER revision=wine9-xinput-reader-1")); logs++; }
static BOOL SetEvent(HANDLE h) { h->signaled=1; return TRUE; }
static BOOL ResetEvent(HANDLE h) { h->signaled=0; return TRUE; }
static HANDLE CreateEventA(void *a,BOOL manual,BOOL initial,const char *name) { (void)a;(void)name;assert(manual);event_calls++;if(event_fail-- > 0)return NULL;fake_event=(struct fake_handle){1,initial,1};return &fake_event; }
static BOOL GetModuleHandleExW(DWORD flags,const WCHAR *address,HMODULE *module) { (void)address;assert(flags==5 && critical_depth==0);if(pin_fail-- > 0)return FALSE;*module=(void*)1;return TRUE; }
static HANDLE CreateThread(void *a,size_t stack,DWORD (*proc)(void*),void *arg,DWORD flags,void *id) { (void)a;(void)stack;(void)proc;(void)arg;(void)flags;(void)id;create_calls++;if(thread_fail-- > 0)return NULL;fake_thread=(struct fake_handle){2,0,1};return &fake_thread; }
static BOOL CloseHandle(HANDLE h) { handle_closes++;h->alive=0;return TRUE; }
static DWORD WaitForSingleObject(HANDLE h,DWORD timeout);
static BOOL create_server_socket(void);
static void close_server_socket(void);
static BOOL get_gamepad_request(void);
static void release_gamepad_request(void) {}
static int recvfrom(int socket,char *buffer,int length,int flags,struct sockaddr *sender,int *sender_length);
static int WSAGetLastError(void) { return last_error; }
static void Sleep(DWORD ms);
static BOOL controller_check_caps(void) { return TRUE; }
/* PD2_DONOR_DECODER */
#include "reader_lifecycle.h"
/* PD2_DONOR_GET_STATE */
static struct sockaddr_in peer(void) { struct sockaddr_in p={0};p.sin_family=AF_INET;p.sin_port=htons(CLIENT_PORT);p.sin_addr.s_addr=htonl(INADDR_LOOPBACK);return p; }
static void device(char *p,int id) { memset(p,0,64);p[0]=8;memcpy(p+1,&id,4); }
static void state(char *p,short buttons,short x,short y) { int id=1;memset(p,0,64);p[0]=9;p[1]=1;memcpy(p+2,&id,4);memcpy(p+6,&buttons,2);p[8]=-1;memcpy(p+9,&x,2);memcpy(p+11,&y,2); }
static void receive(char *packet) { struct sockaddr_in p=peer(); assert(reader_receive(packet,64,&p)); }
static void seed(short buttons,short x,short y) { char p[64];device(p,1);receive(p);state(p,buttons,x,y);receive(p); }
static BOOL create_server_socket(void) {
 open_attempts++; assert(server_sock==INVALID_SOCKET);
 if(open_failures-- > 0)return FALSE;
 server_sock=open_attempts+100;live_sockets++;if(live_sockets>max_sockets)max_sockets=live_sockets;
 if(shutdown_in_open)controller_destroy();return TRUE;
}
static void close_server_socket(void) { if(server_sock!=INVALID_SOCKET){close_calls++;live_sockets--;server_sock=INVALID_SOCKET;} }
static BOOL get_gamepad_request(void) { assert(server_sock!=INVALID_SOCKET);heartbeat_at[requests++]=now;return TRUE; }
static DWORD WaitForSingleObject(HANDLE h,DWORD timeout) {
 if(h->type==2)return h->alive?WAIT_TIMEOUT:WAIT_OBJECT_0;
 assert(critical_depth==0);wait_times[__sync_fetch_and_add(&wait_calls,1)]=timeout;
 if(h->signaled)return WAIT_OBJECT_0;
 if(mode==4){char p[64];device(p,1);receive(p);assert(!h->signaled);mode=5;now+=500;start_read_thread();assert(h->signaled);return WAIT_OBJECT_0;}
 if(mode==5){char p[64];assert(timeout==1500);state(p,1,10,20);receive(p);assert(h->signaled);return WAIT_OBJECT_0;}
 if(mode==6){now+=timeout;return WAIT_TIMEOUT;}
 return WAIT_TIMEOUT;
}
static int recvfrom(int socket,char *buffer,int length,int flags,struct sockaddr *sender,int *sender_length) {
 struct sockaddr_in p=peer();(void)flags;assert(socket==server_sock && length==64);memcpy(sender,&p,sizeof(p));*sender_length=sizeof(p);
 if(shutdown_in_receive){controller_destroy();state(buffer,1,20,20);return 64;}
 if(mode==1){
  if(step==0 || step==3){device(buffer,1);step++;return 64;}
  if(step==1){state(buffer,(1<<10)|(1<<8)|(1<<5),1234,-2345);step++;return 64;}
  if(step==2){step++;last_error=WSAECONNRESET;return SOCKET_ERROR;}
  if(step==4){state(buffer,1,-3000,4000);step++;return 64;}
 }
 if(mode==2){state(buffer,(short)(step++ & 1),1234,0);now+=100;return 64;}
 if(mode==3 && step++==0){last_error=WSAEINTR;return SOCKET_ERROR;}
 last_error=WSAEWOULDBLOCK;return SOCKET_ERROR;
}
static void Sleep(DWORD ms) {
 XINPUT_STATE out;now+=ms;
 if(mode==1 && step==3){assert(reader_get_state(0,&out)==ERROR_DEVICE_NOT_CONNECTED);assert(!reader_has_state);}
 if(mode==1 && step>=5){assert(reader_get_state(0,&out)==0);assert(out.Gamepad.wButtons==XINPUT_GAMEPAD_A);assert(out.Gamepad.sThumbLX==-3000 && out.Gamepad.sThumbLY==-4000);controller_destroy();}
 if(mode==2 && step>=128)controller_destroy();
 if(mode==3){assert(reader_get_state(0,&out)==0);controller_destroy();}
 if(shutdown_in_receive || shutdown_in_open)assert(reader_get_state(0,&out)==ERROR_DEVICE_NOT_CONNECTED);
 if(open_attempts>=3 && open_failures<0 && mode==7)controller_destroy();
}
static void reset(void) {
 pthread_mutexattr_t attr;memset(&controller,0,sizeof(controller));pthread_mutexattr_init(&attr);pthread_mutexattr_settype(&attr,PTHREAD_MUTEX_RECURSIVE);pthread_mutex_init(&controller.crit,&attr);pthread_mutexattr_destroy(&attr);
 reader_thread=reader_ready=NULL;reader_stopping=reader_has_state=reader_module_pinned=reader_logged=reader_returned=FALSE;
 reader_last_state_at=reader_last_valid_at=reader_last_log_at=reader_start_at=0;
 reader_starts=reader_retries=reader_socket_opens=reader_valid_packets=reader_state_updates=reader_state_changes=reader_nonneutral_updates=0;
 reader_api_calls=reader_api_success=reader_api_disconnected=reader_returned_changes=0;
 server_sock=-1;xinput_min_index=3;now=100;mode=step=last_error=live_sockets=max_sockets=open_attempts=close_calls=requests=wait_calls=0;
 event_fail=thread_fail=pin_fail=create_calls=event_calls=handle_closes=logs=critical_depth=shutdown_in_receive=shutdown_in_open=open_failures=0;
 memset(&fake_event,0,sizeof(fake_event));memset(&fake_thread,0,sizeof(fake_thread));
}
static void done(void) { assert(!critical_depth);pthread_mutex_destroy(&controller.crit); }
static void *api_start(void *arg) { (void)arg;start_read_thread();return NULL; }
int main(void) {
 char p[64];XINPUT_STATE out;struct sockaddr_in sender;pthread_t threads[12];int i;
 reset();mode=1;controller_read_thread_proc(NULL);assert(open_attempts==2 && max_sockets==1 && live_sockets==0 && reader_retries==1);done();puts("PASS receive error invalidates cache and recovers changed state");
 reset();seed((1<<10)|(1<<8)|(1<<5),1234,-2345);assert(reader_get_state(0,&out)==0);assert(out.Gamepad.bLeftTrigger==255 && out.Gamepad.wButtons==(XINPUT_GAMEPAD_LEFT_THUMB|XINPUT_GAMEPAD_RIGHT_SHOULDER));assert(out.Gamepad.sThumbLX==1234 && out.Gamepad.sThumbLY==2345);done();puts("PASS exact donor digital-trigger/thumb/shoulder/Y codec");
 reset();seed(1,100,0);mode=2;controller_read_thread_proc(NULL);assert(requests>=6);for(i=1;i<requests;i++)assert((DWORD)(heartbeat_at[i]-heartbeat_at[i-1])<=2100);assert(max_sockets==1 && !live_sockets);done();puts("PASS heartbeat during continuous receive traffic");
 reset();seed(1,100,0);now+=5999;assert(reader_get_state(0,&out)==0);now++;assert(reader_get_state(0,&out)==1167);seed(1,100,0);assert(reader_get_state(0,&out)==0);done();puts("PASS six-second freshness expiry and unchanged held-state heartbeat recovery");
 reset();reader_ready=CreateEventA(NULL,TRUE,FALSE,NULL);device(p,1);receive(p);assert(!fake_event.signaled && !reader_has_state);state(p,1,2,3);receive(p);assert(fake_event.signaled);p[1]=0;receive(p);assert(reader_get_state(0,&out)==1167);done();puts("PASS readiness only after STATE and explicit absent STATE disconnect");
 reset();mode=4;start_read_thread();assert(wait_calls==2 && wait_times[0]==2000 && wait_times[1]==1500 && reader_get_state(0,&out)==0);done();puts("PASS concurrent startup callers share readiness and one deadline");
 reset();mode=6;start_read_thread();assert(wait_calls==1 && now==2100);for(i=0;i<20;i++)start_read_thread();assert(wait_calls==1 && reader_get_state(0,&out)==1167);done();puts("PASS failed peer startup does not stall every subsequent API call");
 reset();event_fail=1;start_read_thread();assert(!reader_thread);thread_fail=1;start_read_thread();assert(!reader_thread);mode=4;start_read_thread();assert(reader_thread && create_calls==2 && reader_starts==1 && reader_get_state(0,&out)==0);done();puts("PASS event/thread creation failure retries without INIT_ONCE trap");
 reset();for(i=0;i<12;i++)assert(!pthread_create(&threads[i],NULL,api_start,NULL));for(i=0;i<12;i++)pthread_join(threads[i],NULL);assert(create_calls==1 && reader_starts==1);done();puts("PASS concurrent API calls create one reader");
 reset();seed(1,100,0);mode=3;controller_read_thread_proc(NULL);assert(open_attempts==1 && reader_retries==0 && !live_sockets);done();puts("PASS interrupted receive does not kill reader");
 reset();shutdown_in_receive=1;controller_read_thread_proc(NULL);assert(!controller.connected && !reader_has_state && !live_sockets && max_sockets==1);start_read_thread();assert(create_calls==0);done();puts("PASS shutdown during receive rejects late state and cannot restart");
 reset();shutdown_in_open=1;controller_read_thread_proc(NULL);assert(!live_sockets && max_sockets==1);done();puts("PASS shutdown/open race retires the worker-owned socket");
 reset();open_failures=2;mode=7;controller_read_thread_proc(NULL);assert(reader_retries==2 && max_sockets==1 && !live_sockets);done();puts("PASS failed socket startup retries with bounded backoff");
 reset();seed(1,100,0);sender=peer();sender.sin_port=htons(1);state(p,2,200,0);assert(!reader_receive(p,64,&sender));sender=peer();assert(!reader_receive(p,63,&sender));p[8]=9;assert(!reader_receive(p,64,&sender));assert(reader_get_state(0,&out)==0 && out.Gamepad.wButtons==XINPUT_GAMEPAD_A);done();puts("PASS foreign/truncated/invalid packets cannot replace accepted controls");
 puts("PASS 14 actual source reader lifecycle scenarios");return 0;
}
