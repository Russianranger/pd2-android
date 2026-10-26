/* Wine's private header additions needed by the unmodified donor API code. */
#ifndef PD2_XINPUT_COMPAT_H
#define PD2_XINPUT_COMPAT_H
#define WIN32_LEAN_AND_MEAN
#define WINVER 0x0a00
#define _WIN32_WINNT 0x0a00
#include <winsock2.h>
#include <windows.h>
#include <xinput.h>
#include <stdint.h>
#include <stdio.h>
#ifndef DECLSPEC_HOTPATCH
#define DECLSPEC_HOTPATCH
#endif
#ifndef ARRAY_SIZE
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#endif
typedef struct {
    XINPUT_CAPABILITIES Capabilities;
    WORD VendorId;
    WORD ProductId;
    WORD VersionNumber;
    WORD Unknown1;
    DWORD Unknown2;
} XINPUT_CAPABILITIES_EX;
DWORD WINAPI XInputGetCapabilitiesEx(DWORD,DWORD,DWORD,XINPUT_CAPABILITIES_EX *);
static void pd2_set_thread_description(HANDLE thread, const WCHAR *name)
{
    typedef HRESULT (WINAPI *describe_fn)(HANDLE, const WCHAR *);
    describe_fn describe = (describe_fn)(ULONG_PTR)GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "SetThreadDescription");
    if (describe) describe(thread, name);
}
#endif
