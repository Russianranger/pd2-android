#!/usr/bin/env python3
"""Run real ExternalController event handling with deterministic Android stubs.

The test exercises input-source fidelity before Wine transport. It does not
qualify Android hardware or Project Diablo 2 gameplay.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
    "android/util/Rational.java": "package android.util; public class Rational { public Rational(int a,int b) {} }",
    "org/json/JSONException.java": "package org.json; public class JSONException extends Exception {}",
    "org/json/JSONObject.java": "package org.json; public class JSONObject { public JSONObject put(String k,Object v) throws JSONException { return this; } }",
    "org/json/JSONArray.java": "package org.json; public class JSONArray { public JSONArray put(Object v) { return this; } }",
    "com/winlator/core/ArrayUtils.java": "package com.winlator.core; public class ArrayUtils { public static boolean contains(int[] a,int x) { for(int y:a) if(x==y) return true; return false; } }",
    "com/winlator/inputcontrols/ControlElement.java": "package com.winlator.inputcontrols; public class ControlElement { public static final float STICK_DEAD_ZONE=0.15f; }",
    "com/winlator/inputcontrols/ExternalControllerBinding.java": "package com.winlator.inputcontrols; public class ExternalControllerBinding { public int getKeyCodeForAxis() { return 0; } public org.json.JSONObject toJSONObject() { return null; } }",
    "com/winlator/inputcontrols/GamepadVibration.java": "package com.winlator.inputcontrols; public class GamepadVibration { public GamepadVibration(String id) {} }",
    "android/view/InputDevice.java": """package android.view;
import java.util.HashMap;
public class InputDevice {
 public static final int SOURCE_GAMEPAD=0x401, SOURCE_JOYSTICK=0x1000010;
 private final HashMap<Integer,MotionRange> ranges=new HashMap<>();
 public static class MotionRange { public float getFlat() { return 0.05f; } }
 public InputDevice axes(int... axes) { for(int axis:axes) ranges.put(axis,new MotionRange()); return this; }
 public MotionRange getMotionRange(int axis,int source) { return ranges.get(axis); }
 public static int[] getDeviceIds() { return new int[0]; }
 public static InputDevice getDevice(int id) { return null; }
 public String getDescriptor() { return "fixture"; }
 public String getName() { return "fixture"; }
 public int getVendorId() { return 1; }
 public int getProductId() { return 1; }
 public boolean isVirtual() { return false; }
 public int getSources() { return SOURCE_GAMEPAD|SOURCE_JOYSTICK; }
 public boolean[] hasKeys(int... keys) { boolean[] result=new boolean[keys.length]; java.util.Arrays.fill(result,true); return result; }
}
""",
    "android/view/KeyEvent.java": """package android.view;
public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1;
 public static final int KEYCODE_BUTTON_A=96,KEYCODE_BUTTON_B=97,KEYCODE_BUTTON_X=99,KEYCODE_BUTTON_Y=100,
 KEYCODE_BUTTON_L1=102,KEYCODE_BUTTON_R1=103,KEYCODE_BUTTON_L2=104,KEYCODE_BUTTON_R2=105,
 KEYCODE_BUTTON_THUMBL=106,KEYCODE_BUTTON_THUMBR=107,KEYCODE_BUTTON_START=108,KEYCODE_BUTTON_SELECT=109,
 KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_LEFT=21,KEYCODE_DPAD_RIGHT=22;
 private final InputDevice device; private final int code,action;
 public KeyEvent(InputDevice device,int code,boolean down) { this.device=device; this.code=code; action=down?0:1; }
 public int getAction() { return action; }
 public int getKeyCode() { return code; }
 public InputDevice getDevice() { return device; }
}
""",
    "android/view/MotionEvent.java": """package android.view;
import java.util.HashMap;
public class MotionEvent {
 public static final int ACTION_MOVE=2;
 public static final int AXIS_X=0,AXIS_Y=1,AXIS_Z=11,AXIS_RZ=14,
 AXIS_HAT_X=15,AXIS_HAT_Y=16,AXIS_LTRIGGER=17,AXIS_RTRIGGER=18,AXIS_BRAKE=23,AXIS_GAS=22;
 private final InputDevice device; private final HashMap<Integer,Float> axes=new HashMap<>();
 public MotionEvent(InputDevice device) { this.device=device; }
 public MotionEvent axis(int code,float value) { axes.put(code,value); return this; }
 public InputDevice getDevice() { return device; }
 public int getSource() { return InputDevice.SOURCE_JOYSTICK; }
 public int getAction() { return ACTION_MOVE; }
 public int getHistorySize() { return 0; }
 public float getHistoricalAxisValue(int code,int history) { return getAxisValue(code); }
 public float getAxisValue(int code) { return axes.getOrDefault(code,0f); }
}
""",
    "NativeControllerInputTest.java": """import android.view.*;
import com.winlator.inputcontrols.*;
public class NativeControllerInputTest {
 static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
 static void value(float expected,float actual,String message) { check(Math.abs(expected-actual)<0.0001f,message+": expected="+expected+" actual="+actual); }
 static InputDevice pad(int... axes) { return new InputDevice().axes(axes).axes(MotionEvent.AXIS_X,MotionEvent.AXIS_Y,MotionEvent.AXIS_Z,MotionEvent.AXIS_RZ); }
 static void key(ExternalController c,InputDevice d,int code,boolean down) { check(c.updateStateFromKeyEvent(new KeyEvent(d,code,down)),"key not handled"); }
 static void motion(ExternalController c,MotionEvent e) { check(c.updateStateFromMotionEvent(e),"motion not handled"); }
 static void mixedTriggerKeysNeverDisableEitherAnalogTrigger() {
  InputDevice d=pad(MotionEvent.AXIS_LTRIGGER,MotionEvent.AXIS_RTRIGGER);
  ExternalController c=new ExternalController();
  motion(c,new MotionEvent(d).axis(MotionEvent.AXIS_LTRIGGER,1));
  key(c,d,KeyEvent.KEYCODE_BUTTON_L2,true); key(c,d,KeyEvent.KEYCODE_BUTTON_L2,false);
  motion(c,new MotionEvent(d).axis(MotionEvent.AXIS_LTRIGGER,0.65f).axis(MotionEvent.AXIS_RTRIGGER,0.75f));
  value(0.65f,c.getGamepadState().triggerL,"LT axis disappeared after its digital trigger event");
  value(0.75f,c.getGamepadState().triggerR,"LT key disabled the other trigger axis");
  motion(c,new MotionEvent(d));
  value(0,c.getGamepadState().triggerL,"LT survived axis release");
  value(0,c.getGamepadState().triggerR,"RT survived axis release");
 }
 static void mixedTriggerDigitalReleasePreservesAnalogPartialHold() {
  InputDevice d=pad(MotionEvent.AXIS_LTRIGGER,MotionEvent.AXIS_RTRIGGER);
  ExternalController c=new ExternalController();
  motion(c,new MotionEvent(d).axis(MotionEvent.AXIS_LTRIGGER,1));
  key(c,d,KeyEvent.KEYCODE_BUTTON_L2,true);
  motion(c,new MotionEvent(d).axis(MotionEvent.AXIS_LTRIGGER,0.7f));
  key(c,d,KeyEvent.KEYCODE_BUTTON_L2,false);
  value(0.7f,c.getGamepadState().triggerL,"digital full-press release cancelled a partial analog hold");
  key(c,d,KeyEvent.KEYCODE_BUTTON_THUMBL,true);
  check(c.getGamepadState().isPressed(ExternalController.IDX_BUTTON_L3),"L3 state lost");
  value(0.7f,c.getGamepadState().triggerL,"L3 removed simultaneous LT hold");
 }
 static void oneDigitalTriggerDoesNotDisableTheOtherAnalogTrigger() {
  InputDevice d=pad(MotionEvent.AXIS_RTRIGGER);
  ExternalController c=new ExternalController();
  key(c,d,KeyEvent.KEYCODE_BUTTON_L2,true);
  motion(c,new MotionEvent(d).axis(MotionEvent.AXIS_RTRIGGER,0.8f).axis(MotionEvent.AXIS_X,0.5f));
  value(1,c.getGamepadState().triggerL,"unrelated joystick event released digital LT");
  value(0.8f,c.getGamepadState().triggerR,"digital LT disabled analog RT");
  key(c,d,KeyEvent.KEYCODE_BUTTON_L2,false);
  value(0,c.getGamepadState().triggerL,"digital LT did not release");
 }
 static void digitalTriggersSurviveStickMovementAndReleaseIndependently() {
  InputDevice d=pad(); ExternalController c=new ExternalController();
  key(c,d,KeyEvent.KEYCODE_BUTTON_L2,true); key(c,d,KeyEvent.KEYCODE_BUTTON_R2,true);
  motion(c,new MotionEvent(d).axis(MotionEvent.AXIS_X,0.7f));
  value(1,c.getGamepadState().triggerL,"stick event released digital LT");
  value(1,c.getGamepadState().triggerR,"stick event released digital RT");
  key(c,d,KeyEvent.KEYCODE_BUTTON_L2,false);
  value(0,c.getGamepadState().triggerL,"LT release missing"); value(1,c.getGamepadState().triggerR,"LT release cancelled RT");
  key(c,d,KeyEvent.KEYCODE_BUTTON_R2,false); value(0,c.getGamepadState().triggerR,"RT release missing");
 }
 static void brakeAndGasAliasesAndAnalogOnlyTriggers() {
  InputDevice d=pad(MotionEvent.AXIS_BRAKE,MotionEvent.AXIS_GAS); ExternalController c=new ExternalController();
  motion(c,new MotionEvent(d).axis(MotionEvent.AXIS_BRAKE,0.6f).axis(MotionEvent.AXIS_GAS,0.9f));
  value(0.6f,c.getGamepadState().triggerL,"BRAKE alias missing"); value(0.9f,c.getGamepadState().triggerR,"GAS alias missing");
  motion(c,new MotionEvent(d)); value(0,c.getGamepadState().triggerL,"BRAKE release missing"); value(0,c.getGamepadState().triggerR,"GAS release missing");
 }
 static void shoulderTapsAndOverlapHaveSeparateEdges() {
  InputDevice d=pad(); ExternalController c=new ExternalController();
  for(int code:new int[]{KeyEvent.KEYCODE_BUTTON_L1,KeyEvent.KEYCODE_BUTTON_R1}) {
   int index=ExternalController.getButtonIdxByKeyCode(code);
   for(int i=0;i<4;i++) {
    key(c,d,code,true); check(c.getGamepadState().isPressed(index),"shoulder down missing");
    motion(c,new MotionEvent(d).axis(MotionEvent.AXIS_X,0.3f)); check(c.getGamepadState().isPressed(index),"motion removed held shoulder");
    key(c,d,code,false); check(!c.getGamepadState().isPressed(index),"shoulder release missing");
   }
  }
  key(c,d,KeyEvent.KEYCODE_BUTTON_L1,true); key(c,d,KeyEvent.KEYCODE_BUTTON_R1,true);
  key(c,d,KeyEvent.KEYCODE_BUTTON_L1,false);
  check(c.getGamepadState().isPressed(ExternalController.IDX_BUTTON_R1),"LB release cancelled RB");
  key(c,d,KeyEvent.KEYCODE_BUTTON_R1,false); check(c.getGamepadState().buttons==0,"shoulder stuck");
 }
 public static void main(String[] args) {
  mixedTriggerKeysNeverDisableEitherAnalogTrigger(); mixedTriggerDigitalReleasePreservesAnalogPartialHold();
  oneDigitalTriggerDoesNotDisableTheOtherAnalogTrigger(); digitalTriggersSurviveStickMovementAndReleaseIndependently();
  brakeAndGasAliasesAndAnalogOnlyTriggers(); shoulderTapsAndOverlapHaveSeparateEdges();
  System.out.println("PASS: 6 native Android input scenarios: mixed triggers, partial hold, asymmetric sources, digital triggers, aliases, shoulder edges");
 }
}
""",
}

with tempfile.TemporaryDirectory(prefix="pd2-native-controller-test-") as temporary:
    directory = Path(temporary)
    files = []
    for name, source in STUBS.items():
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source)
        files.append(str(target))
    files.extend(str(ROOT / "app/src/main/java" / name) for name in (
        "com/winlator/inputcontrols/ExternalController.java",
        "com/winlator/inputcontrols/GamepadState.java",
        "com/winlator/inputcontrols/GamepadSlot.java",
        "com/winlator/math/Mathf.java"))
    subprocess.run(["javac", "-d", str(directory), *files], check=True)
    subprocess.run(["java", "-cp", str(directory), "NativeControllerInputTest"], check=True)
