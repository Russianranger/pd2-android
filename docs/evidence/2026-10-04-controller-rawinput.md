# 0.1.5 controller input reached Java; PD2 activation remained absent

Summary of the support ZIP named `pd2-support-20261003-193852.zip`. No full logs,
game binaries, saves, or private installation paths are included.

Source ZIP SHA-256: `1c557f6726d4e4e78385674026a632dd63989b62b8afb942e8b9efc5e949a21d`.

| Stage | Recorded evidence |
| --- | --- |
| Accepted setup | Wine 9.2, rootfs 24, prefix `wine-9.2-pd2-1`, Turnip/Zink, Stability, exact GameNative arguments |
| Android pad | Accepted `Xbox Wireless Controller`, device ID 92 |
| Android input | 736/736 motion events and 16/16 key events handled |
| Legacy bridge | 58 XInput discovery requests/replies and 859 state replies, with no recorded reply failures |
| Wine client | Built-in `XINPUT1_4.dll` loaded |
| PD2 raw-input registration | Usage page 1, usages 4/5, flags `0x2100` |
| XInput polling trace | Four initial GetState calls for indices 0–3; no later calls recorded |

The matching controller report establishes Android discovery/input handling and
Java reply activity for this attempt. Its availability/socket flags describe
snapshot time; they do not show those states throughout the attempt or negate
the accumulated counters.
Replies do not prove PD2 consumed the input or activated controller mode.

The Wine 9 legacy XInput bridge lacks the HID device/input notification producer
used by the proposed path. The registration plus absent continued polling is
consistent with an activation gap; it does not establish a complete device-level
causal chain. Title startup remains accepted, and native controller gameplay is
still unqualified.

## 0.1.6 comparison selected: device result pending

The targeted Wine 9 Unix backend `wine9-hid-1` adds HID arrival/input reports
through Java's modern 256-byte port-7950 producer. The port-7949 legacy bridge
remains in place for the same selected pad. The backend is installed before Play
with verified backup/replacement; disabling notifications restores the original
on a fresh launch. PlugPlay/RpcSs start settings are restored for that path.

Wine version, rootfs version, prefix, and imported PD2 files/saves are retained.
There is no PD2 DLL patch or re-import/preparation. All 76 Java framework tests,
three native host tests, and source/artifact/ABI checks passed. The harness does
not establish complete Wine/HID/PD2 integration; the Thor result remains pending,
and no confirmed device fix follows from the source change alone.
