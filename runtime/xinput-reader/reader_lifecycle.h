/*
 * Focused lifecycle repair for Wine 9.2 Custom's Android XInput reader.
 * Copyright 2026 PD2 Android contributors. LGPL-2.1-or-later.
 * Included after the donor's controller_init/controller_update_state functions.
 */
#define PD2_READER_REVISION "wine9-xinput-reader-1"
#define PD2_READER_HEARTBEAT_MS 2000u
#define PD2_READER_STALE_MS 6000u
#define PD2_READER_RETRY_MS 250u

static HANDLE reader_thread, reader_ready;
static BOOL reader_stopping, reader_has_state, reader_module_pinned;
static DWORD reader_last_state_at, reader_last_valid_at, reader_last_log_at, reader_start_at;
static BOOL reader_logged, reader_returned;
static XINPUT_GAMEPAD reader_last_returned;
static DWORD reader_starts, reader_retries, reader_socket_opens, reader_valid_packets;
static DWORD reader_state_updates, reader_state_changes, reader_nonneutral_updates;
static DWORD reader_api_calls, reader_api_success, reader_api_disconnected, reader_returned_changes;

/* controller.crit owns all lifecycle, freshness and telemetry fields. */
static void reader_log_locked(const char *event)
{
    char line[768];
    DWORD now = GetTickCount();
    if (reader_logged && (DWORD)(now - reader_last_log_at) < 2000u) return;
    reader_logged = TRUE;
    reader_last_log_at = now;
    snprintf(line, sizeof(line), "PD2_XINPUT_READER revision=%s event=%s pid=%u starts=%u retries=%u socket_opens=%u rx=%u updates=%u state_changes=%u nonneutral=%u api_calls=%u api_success=%u api_disconnected=%u returned_changes=%u packet=%u connected=%u fresh=%u age_ms=%u\n",
             PD2_READER_REVISION, event, (unsigned)GetCurrentProcessId(), (unsigned)reader_starts,
             (unsigned)reader_retries, (unsigned)reader_socket_opens, (unsigned)reader_valid_packets,
             (unsigned)reader_state_updates, (unsigned)reader_state_changes, (unsigned)reader_nonneutral_updates,
             (unsigned)reader_api_calls, (unsigned)reader_api_success, (unsigned)reader_api_disconnected,
             (unsigned)reader_returned_changes, (unsigned)controller.state.dwPacketNumber,
             (unsigned)controller.connected, (unsigned)reader_has_state,
             reader_has_state ? (unsigned)(now - reader_last_state_at) : 0u);
    OutputDebugStringA(line);
}

static void reader_lost_locked(void)
{
    controller.connected = FALSE;
    reader_has_state = FALSE;
    memset(&controller.state, 0, sizeof(controller.state));
}

static void controller_destroy(void)
{
    /* The worker owns its socket. Never join it under DllMain's loader lock or
     * close/reuse a SOCKET while it may be receiving on another thread. The
     * module is pinned before creating the worker, preventing live-code unload. */
    EnterCriticalSection(&controller.crit);
    reader_stopping = TRUE;
    reader_lost_locked();
    if (reader_ready) SetEvent(reader_ready);
    LeaveCriticalSection(&controller.crit);
}

static BOOL reader_receive(const char *buffer, int length, const struct sockaddr_in *sender)
{
    int id;
    BOOL valid = FALSE;
    if (length != BUFFER_SIZE || sender->sin_family != AF_INET
        || sender->sin_port != htons(CLIENT_PORT) || sender->sin_addr.s_addr != htonl(INADDR_LOOPBACK)) return FALSE;
    EnterCriticalSection(&controller.crit);
    if (reader_stopping) goto done;
    if ((unsigned char)buffer[0] == REQUEST_CODE_GET_GAMEPAD)
    {
        memcpy(&id, buffer + 1, sizeof(id));
        if (id < 0) goto done;
        if (!id)
        {
            controller.id = 0;
            reader_lost_locked();
        }
        else
        {
            if (!controller.connected || controller.id != id)
            {
                controller_init();
                reader_has_state = FALSE;
            }
            controller.id = id;
        }
        if (!id && reader_ready) SetEvent(reader_ready);
        valid = TRUE;
    }
    else if ((unsigned char)buffer[0] == REQUEST_CODE_GET_GAMEPAD_STATE)
    {
        XINPUT_GAMEPAD previous;
        signed char hat = (signed char)buffer[8];
        memcpy(&id, buffer + 2, sizeof(id));
        if (buffer[1] == 0)
        {
            reader_lost_locked();
            if (reader_ready) SetEvent(reader_ready);
            valid = TRUE;
            goto received;
        }
        if (!controller.connected || buffer[1] != 1 || id != controller.id || hat < -1 || hat > 7) goto done;
        previous = controller.state.Gamepad;
        controller_update_state((char *)buffer);
        reader_has_state = TRUE;
        reader_last_state_at = GetTickCount();
        if (reader_ready) SetEvent(reader_ready);
        reader_state_updates++;
        if (memcmp(&previous, &controller.state.Gamepad, sizeof(previous))) reader_state_changes++;
        if (controller.state.Gamepad.wButtons || controller.state.Gamepad.bLeftTrigger || controller.state.Gamepad.bRightTrigger
            || controller.state.Gamepad.sThumbLX || controller.state.Gamepad.sThumbLY
            || controller.state.Gamepad.sThumbRX || controller.state.Gamepad.sThumbRY) reader_nonneutral_updates++;
        valid = TRUE;
    }
received:
    if (valid)
    {
        reader_last_valid_at = GetTickCount();
        reader_valid_packets++;
    }
done:
    LeaveCriticalSection(&controller.crit);
    return valid;
}

static DWORD WINAPI controller_read_thread_proc(void *param)
{
    DWORD last_request = 0;
    BOOL request_due = TRUE;
    (void)param;
    pd2_set_thread_description(GetCurrentThread(), L"wine_xinput_reader_pd2");
    for (;;)
    {
        DWORD now;
        BOOL stopping;
        int i;
        EnterCriticalSection(&controller.crit);
        stopping = reader_stopping;
        LeaveCriticalSection(&controller.crit);
        if (stopping) break;
        if (server_sock == INVALID_SOCKET)
        {
            if (!create_server_socket())
            {
                close_server_socket();
                EnterCriticalSection(&controller.crit);
                reader_lost_locked();
                reader_retries++;
                reader_log_locked("open_retry");
                LeaveCriticalSection(&controller.crit);
                Sleep(PD2_READER_RETRY_MS);
                continue;
            }
            EnterCriticalSection(&controller.crit);
            reader_socket_opens++;
            LeaveCriticalSection(&controller.crit);
            request_due = TRUE;
        }
        now = GetTickCount();
        /* Unlike the donor, heartbeat is independent of recv returning EWOULDBLOCK. */
        if (request_due || (DWORD)(now - last_request) >= PD2_READER_HEARTBEAT_MS)
        {
            if (!get_gamepad_request()) goto receive_error;
            last_request = now;
            request_due = FALSE;
        }
        for (i = 0; i < 64; i++)
        {
            char buffer[BUFFER_SIZE];
            struct sockaddr_in sender;
            int sender_length = sizeof(sender), length;
            now = GetTickCount();
            if ((DWORD)(now - last_request) >= PD2_READER_HEARTBEAT_MS)
            {
                if (!get_gamepad_request()) goto receive_error;
                last_request = now;
            }
            memset(&sender, 0, sizeof(sender));
            length = recvfrom(server_sock, buffer, sizeof(buffer), 0, (struct sockaddr *)&sender, &sender_length);
            if (length == SOCKET_ERROR)
            {
                int error = WSAGetLastError();
                if (error == WSAEWOULDBLOCK) break;
                if (error == WSAEINTR) continue;
                goto receive_error;
            }
            if (sender_length == sizeof(sender)) reader_receive(buffer, length, &sender);
            /* A zero-length or malformed UDP datagram is data, not a fatal socket error. */
        }
        EnterCriticalSection(&controller.crit);
        if ((controller.connected && (DWORD)(GetTickCount() - reader_last_valid_at) >= PD2_READER_STALE_MS)
            || (reader_has_state && (DWORD)(GetTickCount() - reader_last_state_at) >= PD2_READER_STALE_MS)) reader_lost_locked();
        reader_log_locked("sample");
        LeaveCriticalSection(&controller.crit);
        Sleep(16);
        continue;
receive_error:
        EnterCriticalSection(&controller.crit);
        reader_lost_locked();
        reader_retries++;
        reader_log_locked("receive_retry");
        LeaveCriticalSection(&controller.crit);
        close_server_socket();
        request_due = TRUE;
        Sleep(PD2_READER_RETRY_MS);
    }
    release_gamepad_request();
    close_server_socket();
    EnterCriticalSection(&controller.crit);
    reader_lost_locked();
    if (reader_ready) SetEvent(reader_ready);
    LeaveCriticalSection(&controller.crit);
    return 0;
}

static void start_read_thread(void)
{
    HANDLE ready = NULL;
    DWORD wait_ms = 0;
    EnterCriticalSection(&controller.crit);
    if (reader_stopping) goto done;
    if (!reader_module_pinned)
    {
        HMODULE module;
        BOOL pinned;
        /* Loader APIs must not run with controller.crit held: DllMain uses
         * that same lock. Multiple first callers may pin idempotently. */
        LeaveCriticalSection(&controller.crit);
        pinned = GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_PIN,
                                   (const WCHAR *)(ULONG_PTR)start_read_thread, &module);
        EnterCriticalSection(&controller.crit);
        if (pinned) reader_module_pinned = TRUE;
        else { reader_retries++; goto done; }
        if (reader_stopping) goto done;
    }
    if (reader_thread)
    {
        DWORD status = WaitForSingleObject(reader_thread, 0);
        if (status != WAIT_OBJECT_0) { ready = reader_ready; goto done; }
        CloseHandle(reader_thread);
        reader_thread = NULL;
        reader_lost_locked();
    }
    if (!reader_ready) reader_ready = CreateEventA(NULL, TRUE, FALSE, NULL);
    if (!reader_ready) { reader_retries++; goto done; }
    ResetEvent(reader_ready);
    reader_lost_locked();
    reader_start_at = GetTickCount();
    reader_thread = CreateThread(NULL, 0, controller_read_thread_proc, NULL, 0, NULL);
    if (!reader_thread) { reader_retries++; goto done; }
    reader_starts++;
    reader_log_locked("start");
    ready = reader_ready;
done:
    if (ready)
    {
        DWORD elapsed = GetTickCount() - reader_start_at;
        if (elapsed < 2000u) wait_ms = 2000u - elapsed;
    }
    LeaveCriticalSection(&controller.crit);
    /* All initial callers share one deadline. Failed peer startup cannot turn
     * every subsequent GetState into another two-second stall. */
    if (ready && wait_ms) WaitForSingleObject(ready, wait_ms);
}

static BOOL controller_is_connected(DWORD index)
{
    BOOL connected;
    EnterCriticalSection(&controller.crit);
    connected = index == 0 && controller.connected && !reader_stopping;
    LeaveCriticalSection(&controller.crit);
    return connected;
}

static DWORD reader_get_state(DWORD index, XINPUT_STATE *state)
{
    DWORD result = ERROR_DEVICE_NOT_CONNECTED;
    EnterCriticalSection(&controller.crit);
    reader_api_calls++;
    if (index < xinput_min_index) xinput_min_index = index;
    if (index == xinput_min_index) index = 0;
    if (reader_has_state && (DWORD)(GetTickCount() - reader_last_state_at) >= PD2_READER_STALE_MS) reader_lost_locked();
    if (index == 0 && controller.connected && reader_has_state && !reader_stopping)
    {
        *state = controller.state;
        if (!reader_returned || memcmp(&reader_last_returned, &state->Gamepad, sizeof(reader_last_returned))) reader_returned_changes++;
        reader_last_returned = state->Gamepad;
        reader_returned = TRUE;
        reader_api_success++;
        result = ERROR_SUCCESS;
    }
    else reader_api_disconnected++;
    reader_log_locked("api");
    LeaveCriticalSection(&controller.crit);
    return result;
}
