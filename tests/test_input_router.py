#!/usr/bin/env python3
"""Execute the actual fallback router on a JVM with deterministic platform stubs.

This checks held-state/release behavior; it does not claim Android/Wine/PD2
gameplay qualification. Run with Python 3 and a JDK on PATH.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "android/os/Looper.java": """package android.os;
public class Looper { public static Looper getMainLooper() { return new Looper(); } }
""",
    "android/os/Handler.java": """package android.os;
import java.util.ArrayList;
public class Handler {
 public static final ArrayList<Runnable> queue = new ArrayList<>();
 public Handler(Looper l) {}
 public void post(Runnable r) { queue.add(r); }
 public void postDelayed(Runnable r, long delay) { queue.add(r); }
 public void removeCallbacks(Runnable r) { queue.removeIf(item -> item == r); }
 public static void tick() { if (!queue.isEmpty()) queue.remove(0).run(); }
}
""",
    "android/view/InputDevice.java": """package android.view;
import java.util.HashSet;
public class InputDevice {
 public final HashSet<Integer> axes = new HashSet<>();
 public static class MotionRange {}
 public MotionRange getMotionRange(int axis, int source) { return axes.contains(axis) ? new MotionRange() : null; }
}
""",
    "android/view/KeyEvent.java": """package android.view;
public class KeyEvent {
 public static final int ACTION_DOWN=0, ACTION_UP=1;
 public static final int KEYCODE_BUTTON_A=96, KEYCODE_BUTTON_B=97, KEYCODE_BUTTON_X=99, KEYCODE_BUTTON_Y=100,
 KEYCODE_BUTTON_L1=102, KEYCODE_BUTTON_R1=103, KEYCODE_BUTTON_L2=104, KEYCODE_BUTTON_R2=105,
 KEYCODE_BUTTON_THUMBL=106, KEYCODE_BUTTON_THUMBR=107, KEYCODE_BUTTON_START=108, KEYCODE_BUTTON_SELECT=109,
 KEYCODE_DPAD_UP=19, KEYCODE_DPAD_DOWN=20, KEYCODE_DPAD_LEFT=21, KEYCODE_DPAD_RIGHT=22;
 private final int deviceId, code, action;
 public KeyEvent(int id, int code, int action) { deviceId=id; this.code=code; this.action=action; }
 public int getRepeatCount() { return 0; }
 public int getAction() { return action; }
 public int getDeviceId() { return deviceId; }
 public int getKeyCode() { return code; }
 public InputDevice getDevice() { return new InputDevice(); }
}
""",
    "android/view/MotionEvent.java": """package android.view;
import java.util.HashMap;
public class MotionEvent {
 public static final int AXIS_X=0, AXIS_Y=1, AXIS_Z=11, AXIS_RZ=14, AXIS_RX=12, AXIS_RY=13,
 AXIS_HAT_X=15, AXIS_HAT_Y=16, AXIS_LTRIGGER=17, AXIS_RTRIGGER=18, AXIS_BRAKE=23, AXIS_GAS=22;
 public final HashMap<Integer, Float> values = new HashMap<>();
 private final InputDevice device = new InputDevice();
 public MotionEvent axis(int id, float value) { values.put(id,value); device.axes.add(id); return this; }
 public float getAxisValue(int id) { return values.getOrDefault(id,0f); }
 public int getDeviceId() { return 1; }
 public int getSource() { return 0; }
 public InputDevice getDevice() { return device; }
}
""",
    "com/winlator/inputcontrols/ExternalController.java": """package com.winlator.inputcontrols;
import android.view.*;
public class ExternalController {
 public static boolean isGameController(InputDevice d) { return d != null; }
 public static boolean isJoystickDevice(MotionEvent e) { return true; }
 public static float getCenteredAxis(MotionEvent e, int id, int history) { return e.getAxisValue(id); }
}
""",
    "com/winlator/xserver/Pointer.java": """package com.winlator.xserver;
public class Pointer { public enum Button { BUTTON_LEFT, BUTTON_RIGHT } }
""",
    "com/winlator/xserver/XServer.java": """package com.winlator.xserver;
import java.util.ArrayList;
import java.util.HashSet;
public class XServer {
 public final HashSet<XKeycode> keys = new HashSet<>();
 public final HashSet<Pointer.Button> buttons = new HashSet<>();
 public final ArrayList<String> events = new ArrayList<>();
 public int pointerMoves;
 public void injectKeyPress(XKeycode k) { keys.add(k); events.add("+"+k); }
 public void injectKeyRelease(XKeycode k) { keys.remove(k); events.add("-"+k); }
 public void injectPointerButtonPress(Pointer.Button b) { buttons.add(b); events.add("+"+b); }
 public void injectPointerButtonRelease(Pointer.Button b) { buttons.remove(b); events.add("-"+b); }
 public void injectPointerMoveDelta(int x, int y) { pointerMoves++; }
}
""",
    "RouterTest.java": """import android.view.*;
import android.os.Handler;
import com.winlator.pd2.Pd2InputRouter;
import com.winlator.xserver.*;
public class RouterTest {
 static void check(boolean ok, String message) { if(!ok) throw new AssertionError(message); }
 static KeyEvent event(int device,int code,boolean down) { return new KeyEvent(device,code,down?0:1); }
 public static void main(String[] args) {
  XServer sink = new XServer(); Pd2InputRouter router = new Pd2InputRouter(sink);
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_A,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_A,true));
  check(sink.events.size()==1,"repeat produced duplicate pointer press");
  router.releaseAll(); check(sink.buttons.isEmpty(),"mouse button survived releaseAll");
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_X,true));
  router.keyEvent(event(2,KeyEvent.KEYCODE_BUTTON_X,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_X,false));
  check(sink.keys.contains(XKeycode.KEY_SHIFT_L),"one source released another source's Shift");
  router.keyEvent(event(2,KeyEvent.KEYCODE_BUTTON_X,false));
  check(sink.keys.isEmpty(),"shared Shift remained held");
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_X,1).axis(MotionEvent.AXIS_Y,-1));
  check(sink.keys.contains(XKeycode.KEY_D)&&sink.keys.contains(XKeycode.KEY_W),"diagonal move missing");
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_X,-1).axis(MotionEvent.AXIS_Y,1));
  check(!sink.keys.contains(XKeycode.KEY_D)&&!sink.keys.contains(XKeycode.KEY_W),"opposite movement remained held");
  check(sink.keys.contains(XKeycode.KEY_A)&&sink.keys.contains(XKeycode.KEY_S),"reverse move missing");
  router.releaseAll(); check(sink.keys.isEmpty(),"movement survived menu/mode release");
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_L2,true));
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_LTRIGGER,1));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_L2,false));
  check(sink.keys.contains(XKeycode.KEY_1),"digital trigger release cancelled held analog trigger");
  router.motion(new MotionEvent()); check(sink.keys.isEmpty(),"trigger remained held");
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_Z,0.1f).axis(MotionEvent.AXIS_RZ,0.1f));
  check(Handler.queue.isEmpty(),"drift scheduled mouse motion");
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_RX,0.8f).axis(MotionEvent.AXIS_RY,-0.5f));
  Handler.tick(); check(sink.pointerMoves==1,"RX/RY controller cursor path failed");
  router.releaseAll(); check(Handler.queue.isEmpty(),"cursor timer survived releaseAll");
  router.setCursorSpeed(100); check(router.getCursorSpeed()==3,"cursor speed limit failed");
  router.setDeadzone(-1); check(router.getDeadzone()==0.05f,"deadzone limit failed");
  System.out.println("PASS: pointer/key release, shared input, diagonal reversal, analog/digital triggers, drift, alternate axes, timer cancellation");
 }
}
""",
}

with tempfile.TemporaryDirectory(prefix="pd2-router-test-") as temporary:
    directory = Path(temporary)
    files = []
    for name, source in STUBS.items():
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source)
        files.append(str(target))
    files.extend(str(ROOT / "app/src/main/java" / name) for name in (
        "com/winlator/pd2/Pd2InputRouter.java", "com/winlator/xserver/XKeycode.java"))
    subprocess.run(["javac", "-d", str(directory), *files], check=True)
    subprocess.run(["java", "-cp", str(directory), "RouterTest"], check=True)
