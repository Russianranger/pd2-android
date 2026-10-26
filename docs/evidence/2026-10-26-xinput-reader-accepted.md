# XInput reader repair accepted on the Thor

The user reports that **0.1.21 fixes the Save/Quit controller failure**, and that
the cursor fix still works. The reader repair is now the supported default.
This is physical acceptance on the tested AYN Thor, rather than a claim about
every controller or Android device.

## Capture identity and applied runtime

- Export: `pd2-support-20261026-181042.zip`.
- SHA-256: `ddf98eb7da2558db6a4c51890ac05768355a792f2f8cbfe529fd255620f81372`.
- Current launch: `4a616102-d7ba-4be2-822e-6d8c95596093`.
- App **0.1.21**, Android 13, AYN Thor, Xbox Wireless Controller; Native input,
  Turnip/Zink, `-3dfx -dxnocompatmodefix`, Stability.
- `launch.json` records successful prelaunch and after-Stop cleanup, the original
  Wine HID driver, and `xinputReaderRuntime.appliedForLaunch=true` with
  `loadOverride=xinput1_4=b`.
- The installed i386 `xinput1_4.dll` matches reader revision
  `wine9-xinput-reader-1`, SHA-256
  `5a05fabaaf028749c306b76103451f72215ce69c0605e3876f9beb981f417ce0`.
  The verified original backup remains available. No runtime artifact changes
  are needed to promote the working path.

## Execution and freshness evidence

`runtime.log` contains **51 actual `OutputDebugStringA` reader emissions**.
Wine's SEH logging repeats each message, yielding 153 matching lines; those
duplicates must not be counted as additional samples. The revision and Windows
PID 232 establish execution of the repaired module. Its final retained sample
reports:

| Counter | Value |
| --- | ---: |
| Reader starts / receive retries / socket opens | 1 / 2 / 3 |
| Valid received packets / state updates | 3,438 / 3,364 |
| Decoded control changes / nonneutral updates | 2,841 / 3,030 |
| API calls / successful responses / disconnected responses | 2,840 / 2,837 / 3 |
| Changes in controls returned by successful API calls | 1,096 |
| Connected / fresh / last state age | 1 / 1 / 364 ms |

This establishes changing state returned through XInput, including fresh
successful responses after socket recovery. `controller.json` also records
74 XInput discoveries from that PID, 3,030 successful nonneutral XInput sends,
zero reply failures, and no manual reconnect or identity-recovery requests.
Only Native input was used in this session.

The rotating log retains a head and tail, and does not annotate exact Save/Quit
boundaries. The user report establishes the successful gameplay result; the
capture corroborates module execution, recovery and API freshness. It does not
independently time a Save/Quit transition or prove the precise cause of the old
failure. This Native-only session also does not re-exercise menu cursor motion;
continued cursor acceptance comes from the user's physical report.

## Default and fallback

New installations and existing preferences adopt reader repair once. The mode
and migration marker are written together, without changing graphics, gameplay
input mode, driver, notifications or manual trace preferences. Subsequent
explicit selections persist, including **Standard Native** to restore the
verified original module on the next fully stopped launch. The older SDL hint
experiment remains a separate manual compatibility choice.
