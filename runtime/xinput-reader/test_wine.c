/* Exact-Wine9 PE32 loader and public XInput ABI probe. Synthetic test controls only. */
#define WIN32_LEAN_AND_MEAN
#define WINVER 0x0a00
#define _WIN32_WINNT 0x0a00
#include <windows.h>
#include <xinput.h>
#include <stdio.h>
#include <string.h>

typedef DWORD (WINAPI *get_state_fn)(DWORD, XINPUT_STATE *);
typedef DWORD (WINAPI *get_caps_fn)(DWORD, DWORD, XINPUT_CAPABILITIES *);
typedef struct {
    XINPUT_CAPABILITIES caps;
    WORD vendor, product, version, unknown1;
    DWORD unknown2;
} caps_ex;
typedef DWORD (WINAPI *get_caps_ex_fn)(DWORD, DWORD, DWORD, caps_ex *);

static get_state_fn get_state, get_state_ex;
static unsigned checks;

static void require(BOOL condition, const char *detail)
{
    if (!condition) {
        fprintf(stderr, "PROBE FAIL: %s; win32=%lu\n", detail, GetLastError());
        ExitProcess(1);
    }
    checks++;
}

static void go(const char *expected)
{
    char line[64];
    require(fgets(line, sizeof(line), stdin) != NULL, "phase command missing");
    line[strcspn(line, "\r\n")] = 0;
    require(!strcmp(line, expected), "unexpected phase command");
}

static BOOL matches(const XINPUT_STATE *state, int pattern)
{
    const XINPUT_GAMEPAD *pad = &state->Gamepad;
    if (pattern == 0)
        return pad->wButtons == (XINPUT_GAMEPAD_A | XINPUT_GAMEPAD_LEFT_SHOULDER | XINPUT_GAMEPAD_DPAD_UP)
            && pad->bLeftTrigger == 255 && pad->bRightTrigger == 0
            && pad->sThumbLX == 12000 && pad->sThumbLY == 9000
            && pad->sThumbRX == -14000 && pad->sThumbRY == -7000;
    return pad->wButtons == (XINPUT_GAMEPAD_B | XINPUT_GAMEPAD_RIGHT_SHOULDER
                | XINPUT_GAMEPAD_LEFT_THUMB | XINPUT_GAMEPAD_DPAD_DOWN | XINPUT_GAMEPAD_DPAD_LEFT)
        && pad->bLeftTrigger == 0 && pad->bRightTrigger == 255
        && pad->sThumbLX == -17000 && pad->sThumbLY == -11000
        && pad->sThumbRX == 19000 && pad->sThumbRY == 15000;
}

static XINPUT_STATE wait_state(int pattern, DWORD timeout)
{
    XINPUT_STATE state;
    DWORD start = GetTickCount();
    do {
        if (get_state(0, &state) == ERROR_SUCCESS && matches(&state, pattern)) return state;
        Sleep(5);
    } while ((DWORD)(GetTickCount() - start) < timeout);
    require(FALSE, "fresh expected controls were not returned");
    memset(&state, 0, sizeof(state));
    return state;
}

static void wait_disconnected(DWORD timeout)
{
    DWORD start = GetTickCount(), result;
    XINPUT_STATE state;
    do {
        result = get_state(0, &state);
        require(result == ERROR_SUCCESS || result == ERROR_DEVICE_NOT_CONNECTED,
                "unexpected state status while expiring stale input");
        if (result == ERROR_DEVICE_NOT_CONNECTED) return;
        Sleep(10);
    } while ((DWORD)(GetTickCount() - start) < timeout);
    require(FALSE, "stale cached controls continued to report success");
}

int main(int argc, char **argv)
{
    HMODULE module;
    get_caps_fn get_caps;
    get_caps_ex_fn get_caps_ex;
    XINPUT_STATE initial, current, extended;
    XINPUT_CAPABILITIES caps;
    caps_ex full_caps;
    char path[MAX_PATH];
    DWORD start;
    BOOL donor = argc > 1 && !strcmp(argv[1], "donor");
    const char *exports[] = {"XInputGetState", "XInputSetState", "XInputGetCapabilities",
        "XInputEnable", "XInputGetBatteryInformation", "XInputGetKeystroke",
        "XInputGetAudioDeviceIds", "XInputGetStateEx", "XInputGetCapabilitiesEx"};
    unsigned i;

    setvbuf(stdout, NULL, _IONBF, 0);
    module = LoadLibraryA("xinput1_4.dll");
    require(module != NULL, "LoadLibraryA xinput1_4.dll");
    require(GetModuleFileNameA(module, path, sizeof(path)) != 0, "module filename");
    printf("PROBE MODULE %s\n", path);
    for (i = 0; i < sizeof(exports) / sizeof(exports[0]); i++)
        require(GetProcAddress(module, exports[i]) != NULL, exports[i]);
    get_state = (get_state_fn)GetProcAddress(module, "XInputGetState");
    get_state_ex = (get_state_fn)GetProcAddress(module, (LPCSTR)100);
    get_caps = (get_caps_fn)GetProcAddress(module, "XInputGetCapabilities");
    get_caps_ex = (get_caps_ex_fn)GetProcAddress(module, (LPCSTR)108);
    require((FARPROC)get_state == GetProcAddress(module, (LPCSTR)2), "GetState ordinal 2");
    require((FARPROC)get_state_ex == GetProcAddress(module, "XInputGetStateEx"), "GetStateEx ordinal 100");
    require((FARPROC)get_caps_ex == GetProcAddress(module, "XInputGetCapabilitiesEx"), "CapabilitiesEx ordinal 108");
    require(sizeof(XINPUT_STATE) == 16 && sizeof(XINPUT_CAPABILITIES) == 20 && sizeof(caps_ex) == 32,
            "Win32 public structure sizes");
    initial = wait_state(0, 12000);
    require(get_state(0, NULL) == ERROR_BAD_ARGUMENTS, "null state argument");
    require(get_state(4, &current) == ERROR_BAD_ARGUMENTS, "index 4 invalid");
    require(get_state(1, &current) == ERROR_DEVICE_NOT_CONNECTED, "only slot zero connected");
    require(get_caps(0, XINPUT_FLAG_GAMEPAD, &caps) == ERROR_SUCCESS, "GetCapabilities");
    require(caps.Type == XINPUT_DEVTYPE_GAMEPAD && caps.SubType == XINPUT_DEVSUBTYPE_GAMEPAD,
            "gamepad capability type");
    require(get_caps_ex(1, 0, 0, &full_caps) == ERROR_SUCCESS, "CapabilitiesEx public ABI");
    require(full_caps.vendor == 0x045e && full_caps.product == 0x02a1, "retained Xbox identity");
    require(get_state_ex(0, &extended) == ERROR_SUCCESS && matches(&extended, 0), "GetStateEx controls");
    printf("PROBE INITIAL packet=%lu checks=%u\n", initial.dwPacketNumber, checks);

    go("fault");
    if (donor) {
        Sleep(1000);
        require(get_state(0, &current) == ERROR_SUCCESS && matches(&current, 0),
                "donor receive failure must reproduce cached-success defect");
        Sleep(1500);
        require(get_state(0, &extended) == ERROR_SUCCESS && matches(&extended, 0)
                    && extended.dwPacketNumber == current.dwPacketNumber,
                "donor cached packet must stay frozen while new controls are sent");
        printf("PROBE DONOR_STALE_REPRODUCED checks=%u\n", checks);
        return 0;
    }
    current = wait_state(1, 5000);
    require(get_state_ex(0, &extended) == ERROR_SUCCESS && matches(&extended, 1), "recovered extended state");
    printf("PROBE FAULT_RECOVERED packet=%lu checks=%u\n", current.dwPacketNumber, checks);

    go("hold");
    start = GetTickCount();
    do {
        require(get_state(0, &current) == ERROR_SUCCESS && matches(&current, 1),
                "steady held controls expired despite fresh received states");
        Sleep(10);
    } while ((DWORD)(GetTickCount() - start) < 6500);
    require(FreeLibrary(module), "FreeLibrary on active pinned module");
    require(get_state(0, &current) == ERROR_SUCCESS && matches(&current, 1),
            "active reader module was unloaded");
    module = LoadLibraryA("xinput1_4.dll");
    require(module != NULL && (FARPROC)get_state == GetProcAddress(module, "XInputGetState"),
            "reload must retain the single pinned module");
    printf("PROBE HELD checks=%u\n", checks);

    go("stale");
    wait_disconnected(9000);
    printf("PROBE STALE_EXPIRED checks=%u\n", checks);

    go("resume");
    current = wait_state(0, 5000);
    require(get_state_ex(0, &extended) == ERROR_SUCCESS && matches(&extended, 0), "fresh recovery after timeout");
    printf("PROBE PASS packet=%lu checks=%u\n", current.dwPacketNumber, checks);
    return 0;
}
