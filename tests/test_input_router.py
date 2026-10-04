#!/usr/bin/env python3
"""Execute the actual fallback/menu router on a JVM with deterministic platform stubs.

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
 public boolean gamepad = true;
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
 public boolean gamepad = true;
 public KeyEvent(int id, int code, int action) { deviceId=id; this.code=code; this.action=action; }
 public int getRepeatCount() { return 0; }
 public int getAction() { return action; }
 public int getDeviceId() { return deviceId; }
 public int getKeyCode() { return code; }
 public InputDevice getDevice() { InputDevice d = new InputDevice(); d.gamepad = gamepad; return d; }
}
""",
    "android/view/MotionEvent.java": """package android.view;
import java.util.HashMap;
public class MotionEvent {
 public static final int AXIS_X=0, AXIS_Y=1, AXIS_Z=11, AXIS_RZ=14, AXIS_RX=12, AXIS_RY=13,
 AXIS_HAT_X=15, AXIS_HAT_Y=16, AXIS_LTRIGGER=17, AXIS_RTRIGGER=18, AXIS_BRAKE=23, AXIS_GAS=22;
 public final HashMap<Integer, Float> values = new HashMap<>();
 public boolean joystick = true;
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
 public static boolean isGameController(InputDevice d) { return d != null && d.gamepad; }
 public static boolean isJoystickDevice(MotionEvent e) { return e.joystick; }
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
 public int pointerMoves, lastDeltaX, lastDeltaY;
 public void injectKeyPress(XKeycode k) { keys.add(k); events.add("+"+k); }
 public void injectKeyRelease(XKeycode k) { keys.remove(k); events.add("-"+k); }
 public void injectPointerButtonPress(Pointer.Button b) { buttons.add(b); events.add("+"+b); }
 public void injectPointerButtonRelease(Pointer.Button b) { buttons.remove(b); events.add("-"+b); }
 public void injectPointerMoveDelta(int x, int y) { pointerMoves++; lastDeltaX=x; lastDeltaY=y; }
}
""",
    "RouterTest.java": """import android.view.*;
import android.os.Handler;
import com.winlator.pd2.Pd2InputRouter;
import com.winlator.xserver.*;
public class RouterTest {
 static void check(boolean ok, String message) { if(!ok) throw new AssertionError(message); }
 static KeyEvent event(int device,int code,boolean down) { return new KeyEvent(device,code,down?0:1); }
 static void fallbackCompatibility() {
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
 }
 static void menuCursorEitherStickAndRightPriority() {
  XServer sink = new XServer(); Pd2InputRouter router = new Pd2InputRouter(sink);
  router.setMenuControls(true);
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_X,1).axis(MotionEvent.AXIS_Y,-1));
  Handler.tick();
  check(sink.lastDeltaX>0 && sink.lastDeltaY<0,"left-stick menu cursor missing");
  check(sink.keys.isEmpty(),"menu cursor sent WASD");
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_X,1).axis(MotionEvent.AXIS_Y,1)
    .axis(MotionEvent.AXIS_Z,-1).axis(MotionEvent.AXIS_RZ,0));
  Handler.tick();
  check(sink.lastDeltaX<0 && sink.lastDeltaY==0,"right-stick priority mixed two stick vectors");
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_X,1).axis(MotionEvent.AXIS_Y,-1)
    .axis(MotionEvent.AXIS_Z,0.1f).axis(MotionEvent.AXIS_RZ,0.1f));
  Handler.tick();
  check(sink.lastDeltaX>0 && sink.lastDeltaY<0,"right-stick drift blocked left-stick menu cursor");
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_RX,-1).axis(MotionEvent.AXIS_RY,1));
  Handler.tick();
  check(sink.lastDeltaX<0 && sink.lastDeltaY>0,"alternate right-stick menu axes failed");
  router.motion(new MotionEvent()); int moves = sink.pointerMoves; Handler.tick();
  check(sink.pointerMoves==moves && Handler.queue.isEmpty(),"centered menu sticks kept moving cursor");
  router.releaseAll();
 }
 static void menuClicksBackConfirmAndSharedSources() {
  XServer sink = new XServer(); Pd2InputRouter router = new Pd2InputRouter(sink);
  router.setMenuControls(true);
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_A,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_A,false));
  check(sink.events.contains("+BUTTON_LEFT") && sink.events.contains("-BUTTON_LEFT"),"menu A click missing");
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_B,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_SELECT,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_B,false));
  check(sink.keys.contains(XKeycode.KEY_ESC),"B release cancelled held Select back");
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_SELECT,false));
  check(!sink.keys.contains(XKeycode.KEY_ESC) && sink.buttons.isEmpty(),"menu back became right-click or stayed held");
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_START,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_THUMBR,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_START,false));
  check(sink.keys.contains(XKeycode.KEY_ENTER),"Start release cancelled held R3 confirm");
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_THUMBR,false));
  check(!sink.keys.contains(XKeycode.KEY_ENTER),"menu confirm stayed held");
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_THUMBL,true));
  check(sink.keys.contains(XKeycode.KEY_TAB),"menu L3 Tab missing");
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_THUMBL,false));
  check(sink.keys.isEmpty(),"menu Tab stayed held");
  check(!sink.events.contains("+BUTTON_RIGHT") && !sink.events.contains("+KEY_I"),"gameplay B/Start mapping leaked into menus");
  router.releaseAll();
 }
 static void menuDpadArrows() {
  XServer sink = new XServer(); Pd2InputRouter router = new Pd2InputRouter(sink);
  router.setMenuControls(true);
  int[] codes={KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT};
  XKeycode[] keys={XKeycode.KEY_UP,XKeycode.KEY_RIGHT,XKeycode.KEY_DOWN,XKeycode.KEY_LEFT};
  for(int i=0;i<codes.length;i++) {
   router.keyEvent(event(1,codes[i],true));
   check(sink.keys.size()==1 && sink.keys.contains(keys[i]),"digital menu D-pad arrow missing");
   router.keyEvent(event(1,codes[i],false));
  }
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_HAT_X,1).axis(MotionEvent.AXIS_HAT_Y,-1));
  check(sink.keys.size()==2 && sink.keys.contains(XKeycode.KEY_RIGHT) && sink.keys.contains(XKeycode.KEY_UP),"menu hat diagonal missing");
  router.keyEvent(event(1,KeyEvent.KEYCODE_DPAD_RIGHT,true));
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_HAT_X,-1).axis(MotionEvent.AXIS_HAT_Y,1));
  check(sink.keys.contains(XKeycode.KEY_RIGHT),"hat reversal cancelled digital D-pad hold");
  check(sink.keys.contains(XKeycode.KEY_LEFT) && sink.keys.contains(XKeycode.KEY_DOWN)
    && !sink.keys.contains(XKeycode.KEY_UP),"menu hat reversal left old arrow held");
  router.keyEvent(event(1,KeyEvent.KEYCODE_DPAD_RIGHT,false));
  router.motion(new MotionEvent());
  check(sink.keys.isEmpty(),"menu arrows stayed held after centering");
  router.releaseAll();
 }
 static void menuIgnoresGameplayButtonsAndTriggers() {
  XServer sink = new XServer(); Pd2InputRouter router = new Pd2InputRouter(sink);
  router.setMenuControls(true);
  int[] ignored={KeyEvent.KEYCODE_BUTTON_X,KeyEvent.KEYCODE_BUTTON_Y,KeyEvent.KEYCODE_BUTTON_L1,
    KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.KEYCODE_BUTTON_L2,KeyEvent.KEYCODE_BUTTON_R2};
  for(int code:ignored) {
   check(router.keyEvent(event(1,code,true)),"menu did not consume gameplay button");
   router.keyEvent(event(1,code,false));
  }
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_LTRIGGER,1).axis(MotionEvent.AXIS_RTRIGGER,1)
    .axis(MotionEvent.AXIS_BRAKE,1).axis(MotionEvent.AXIS_GAS,1));
  check(sink.events.isEmpty() && sink.keys.isEmpty() && sink.buttons.isEmpty(),"menu injected potion, skill, modifier, or click");
  check(Handler.queue.isEmpty(),"triggers started menu cursor timer");
  router.releaseAll();
 }
 static void modeChangesReleaseHeldInputAndTimers() {
  XServer sink = new XServer(); Pd2InputRouter router = new Pd2InputRouter(sink);
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_A,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_L2,true));
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_X,1).axis(MotionEvent.AXIS_Y,-1)
    .axis(MotionEvent.AXIS_Z,1).axis(MotionEvent.AXIS_LTRIGGER,1));
  Handler.tick();
  check(!sink.keys.isEmpty() && !sink.buttons.isEmpty() && !Handler.queue.isEmpty(),"mode-change fixture was not holding input");
  router.setMenuControls(true);
  check(sink.keys.isEmpty() && sink.buttons.isEmpty() && Handler.queue.isEmpty(),"entering menu layout left fallback input held");
  int moves=sink.pointerMoves; Handler.tick();
  check(sink.pointerMoves==moves,"old fallback timer injected after mode change");
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_B,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_START,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_A,true));
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_Y,1));
  Handler.tick();
  router.setMenuControls(false);
  check(sink.keys.isEmpty() && sink.buttons.isEmpty() && Handler.queue.isEmpty(),"leaving menu layout left navigation input or timer held");
  moves=sink.pointerMoves; Handler.tick();
  check(sink.pointerMoves==moves,"old menu timer injected after layout returned");
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_B,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_START,true));
  check(sink.buttons.contains(Pointer.Button.BUTTON_RIGHT) && sink.keys.contains(XKeycode.KEY_I),"full fallback layout did not return");
  router.releaseAll();
 }
 static void unchangedModeKeepsHeldInput() {
  XServer sink = new XServer(); Pd2InputRouter router = new Pd2InputRouter(sink);
  router.setMenuControls(true);
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_A,true));
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_X,1));
  int events=sink.events.size();
  router.setMenuControls(true);
  check(sink.buttons.contains(Pointer.Button.BUTTON_LEFT) && sink.events.size()==events,
    "idempotent menu flag released a held click");
  check(!Handler.queue.isEmpty(),"idempotent menu flag stopped an active cursor timer");
  router.releaseAll();
 }
 static void configurationAndRejectedEventsNeverInject() {
  XServer sink = new XServer(); Pd2InputRouter router = new Pd2InputRouter(sink);
  router.setMenuControls(false); router.setMenuControls(true); router.setMenuControls(false);
  router.setCursorSpeed(1); router.setDeadzone(0.18f);
  // Native events belong to the Activity/bridge, which does not forward them to this router.
  check(sink.events.isEmpty() && sink.pointerMoves==0 && Handler.queue.isEmpty(),"idle router injected without an owned event");
  router.setMenuControls(true);
  KeyEvent keyboard = event(1,KeyEvent.KEYCODE_BUTTON_A,true); keyboard.gamepad=false;
  MotionEvent mouse = new MotionEvent().axis(MotionEvent.AXIS_X,1); mouse.joystick=false;
  check(!router.keyEvent(keyboard) && !router.motion(mouse),"non-controller input was consumed");
  check(sink.events.isEmpty() && sink.pointerMoves==0 && Handler.queue.isEmpty(),"rejected input reached the game");
  router.releaseAll();
 }
 static void injectedMenuBackendKeepsFallbackSeparateAndBalancesOwner() {
  XServer sink = new XServer();
  class MenuBackend implements Pd2InputRouter.MenuInput {
   final java.util.ArrayList<String> events = new java.util.ArrayList<>();
   int moves;
   public void move(int x,int y) { moves++; }
   public void button(Pointer.Button button,boolean down) { events.add((down?"+":"-")+button); }
   public void key(XKeycode key,boolean down) { events.add((down?"+":"-")+key); }
  }
  MenuBackend backend = new MenuBackend(); Pd2InputRouter router = new Pd2InputRouter(sink,backend);
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_X,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_B,true));
  router.setMenuControls(true);
  check(sink.keys.isEmpty() && sink.buttons.isEmpty(),"menu change did not release original X11 owner");
  check(backend.events.isEmpty(),"X11 releases were sent to the new menu backend");
  sink.events.clear();
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_A,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_B,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_SELECT,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_B,false));
  check(!backend.events.contains("-KEY_ESC"),"menu backend lost shared Esc ownership");
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_START,true));
  router.motion(new MotionEvent().axis(MotionEvent.AXIS_X,1)); Handler.tick();
  check(backend.moves==1 && backend.events.contains("+BUTTON_LEFT")
    && backend.events.contains("+KEY_ESC") && backend.events.contains("+KEY_ENTER"),"menu events missed injected Windows backend");
  check(sink.events.isEmpty() && sink.pointerMoves==0,"menu backend leaked X11 mouse or key injection");
  router.setMenuControls(false);
  check(backend.events.contains("-BUTTON_LEFT") && backend.events.contains("-KEY_ESC")
    && backend.events.contains("-KEY_ENTER") && Handler.queue.isEmpty(),"mode change did not balance original menu backend/timer");
  check(sink.events.isEmpty(),"menu releases were sent to new fallback owner");
  int menuEvents=backend.events.size();
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_B,true));
  router.keyEvent(event(1,KeyEvent.KEYCODE_BUTTON_B,false));
  check(sink.events.contains("+BUTTON_RIGHT") && backend.events.size()==menuEvents,"injected menu backend changed full fallback");
  router.releaseAll();
 }
 public static void main(String[] args) {
  fallbackCompatibility();
  menuCursorEitherStickAndRightPriority();
  menuClicksBackConfirmAndSharedSources();
  menuDpadArrows();
  menuIgnoresGameplayButtonsAndTriggers();
  modeChangesReleaseHeldInputAndTimers();
  unchangedModeKeepsHeldInput();
  configurationAndRejectedEventsNeverInject();
  injectedMenuBackendKeepsFallbackSeparateAndBalancesOwner();
  System.out.println("PASS: 9 router scenarios: fallback regression, menu sticks, click/back/confirm, arrows, ignored gameplay controls, mode releases, unchanged mode, owner/rejected-event boundary, injected backend ownership");
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
