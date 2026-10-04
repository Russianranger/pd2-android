#!/usr/bin/env python3
"""Exercise the actual TouchpadView and Pointer against a recording X11 sink.

The Android UI stubs supply events and gate state; production motion rounding,
coordinate transforms, touch handling and pointer notifications execute unchanged.
This checks app routing, not PD2 consumption or Android device event generation.
"""
from pathlib import Path
import argparse
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}",
    "android/content/Context.java": "package android.content; public class Context {}",
    "android/graphics/Color.java": "package android.graphics; public class Color { public static final int TRANSPARENT=0; }",
    "android/graphics/drawable/ColorDrawable.java": "package android.graphics.drawable; public class ColorDrawable { public ColorDrawable(int c){} }",
    "android/graphics/drawable/StateListDrawable.java": "package android.graphics.drawable; public class StateListDrawable { public void addState(int[] s,ColorDrawable d){} }",
    "android/R.java": "package android; public class R { public static class attr { public static final int state_focused=1; } }",
    "android/util/Rational.java": "package android.util; public class Rational { public Rational(int n,int d){} }",
    "android/view/InputDevice.java": "package android.view; public class InputDevice { public static final int SOURCE_MOUSE=0x2002, SOURCE_MOUSE_RELATIVE=0x20004, SOURCE_TOUCHSCREEN=0x1002; }",
    "android/view/ViewGroup.java": "package android.view; public class ViewGroup { public static class LayoutParams { public static final int MATCH_PARENT=-1; public LayoutParams(int w,int h){} } }",
    "android/widget/FrameLayout.java": "package android.widget; public class FrameLayout { public static class LayoutParams extends android.view.ViewGroup.LayoutParams { public LayoutParams(int w,int h){super(w,h);} } }",
    "android/view/View.java": """package android.view;
import android.content.Context;
public class View {
 private boolean enabled=true;
 private static long now; private static final java.util.List<Task> pending=new java.util.ArrayList<>();
 private static final class Task { final long at; final Runnable run; Task(long at,Runnable run){this.at=at;this.run=run;} }
 public View(Context c){}
 public interface OnCapturedPointerListener { boolean onCapturedPointer(View v,MotionEvent e); }
 public interface OnClickListener { void onClick(View v); }
 public void setLayoutParams(Object p){} public void setBackground(Object b){}
 public void setClickable(boolean b){} public void setFocusable(boolean b){}
 public void setFocusableInTouchMode(boolean b){} public void setOnCapturedPointerListener(OnCapturedPointerListener l){}
 public void setOnClickListener(OnClickListener l){} public void requestPointerCapture(){}
 public void setEnabled(boolean value){enabled=value;} public boolean isEnabled(){return enabled;}
 protected void onSizeChanged(int w,int h,int oldw,int oldh){}
 public boolean onTouchEvent(MotionEvent e){return false;}
 public boolean postDelayed(Runnable r,long millis){pending.add(new Task(now+millis,r));return true;}
 public static void advance(long millis){now+=millis;for(int i=0;i<pending.size();){Task task=pending.get(i);if(task.at<=now){pending.remove(i);task.run.run();}else i++;}}
}
""",
    "android/view/MotionEvent.java": """package android.view;
public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,
 ACTION_POINTER_UP=6,ACTION_HOVER_MOVE=7,ACTION_SCROLL=8,ACTION_BUTTON_PRESS=11,ACTION_BUTTON_RELEASE=12;
 public static final int BUTTON_PRIMARY=1,BUTTON_SECONDARY=2,AXIS_VSCROLL=9;
 private int action,source,button,index; private float[] x,y; public float scroll;
 public MotionEvent(int action,int source,float x,float y){this(action,source,new float[]{x},new float[]{y});}
 public MotionEvent(int action,int source,float[] x,float[] y){this.action=action;this.source=source;this.x=x;this.y=y;}
 public MotionEvent button(int b){button=b;return this;}
 public MotionEvent index(int i){index=i;return this;}
 public int getAction(){return action;} public int getActionMasked(){return action;}
 public int getActionIndex(){return index;} public int getPointerId(int index){return index;}
 public int findPointerIndex(int id){return id<x.length?id:-1;}
 public float getX(){return x[0];} public float getY(){return y[0];}
 public float getX(int index){return x[index];} public float getY(int index){return y[index];}
 public boolean isFromSource(int s){return (source&s)==s;}
 public int getSource(){return source;} public void setSource(int s){source=s;}
 public int getActionButton(){return button;} public float getAxisValue(int axis){return scroll;}
}
""",
    "com/winlator/core/AppUtils.java": "package com.winlator.core; public class AppUtils { public static int getScreenWidth(){return 1280;} public static int getScreenHeight(){return 720;} }",
    "com/winlator/renderer/GLRenderer.java": "package com.winlator.renderer; public class GLRenderer { public boolean isFullscreen(){return false;} }",
    "com/winlator/pd2/Pd2ControllerDiagnostics.java": """package com.winlator.pd2;
import java.util.*;
public class Pd2ControllerDiagnostics {
 public String mode="native"; public final Map<String,Integer> routes=new HashMap<>();
 public void recordPointerRoute(String source,String kind){String key=mode+":"+source+":"+kind;routes.put(key,routes.getOrDefault(key,0)+1);}
 public int count(String source,String kind){return routes.getOrDefault(mode+":"+source+":"+kind,0);}
}
""",
    "com/winlator/winhandler/MouseEventFlags.java": "package com.winlator.winhandler; public class MouseEventFlags { public static final int MOVE=1; }",
    "com/winlator/winhandler/WinHandler.java": """package com.winlator.winhandler;
public class WinHandler {
 public final com.winlator.pd2.Pd2ControllerDiagnostics controllerDiagnostics=new com.winlator.pd2.Pd2ControllerDiagnostics();
 public int relativeMoves;
 public void mouseEvent(int flags,int dx,int dy,int wheel){relativeMoves++;}
}
""",
    "com/winlator/xserver/XServer.java": """package com.winlator.xserver;
import com.winlator.renderer.GLRenderer; import com.winlator.winhandler.WinHandler;
public class XServer {
 public static final class ScreenInfo { public short width=1280,height=720; }
 public final ScreenInfo screenInfo=new ScreenInfo(); public final Pointer pointer=new Pointer(this);
 public final WinHandler handler=new WinHandler(); public boolean relative;
 public int moves,presses,releases,rawMoves; public int lastDx,lastDy;
 public GLRenderer getRenderer(){return new GLRenderer();} public WinHandler getWinHandler(){return handler;}
 public boolean isRelativeMouseMovement(){return relative;}
 public void injectPointerMove(int x,int y){moves++;pointer.setPosition(x,y);}
 public void injectPointerMoveDelta(int dx,int dy){lastDx=dx;lastDy=dy;moves++;pointer.setPosition(pointer.getX()+dx,pointer.getY()+dy);rawMoves++;}
 public void injectPointerButtonPress(Pointer.Button b){presses++;pointer.setButton(b,true);}
 public void injectPointerButtonRelease(Pointer.Button b){releases++;pointer.setButton(b,false);}
}
""",
}

HARNESS = """package com.winlator.widget;
import android.content.Context; import android.view.*; import com.winlator.xserver.*;
public final class CapturedPointerChecks {
 static MotionEvent move(float x,float y){return new MotionEvent(MotionEvent.ACTION_MOVE,InputDevice.SOURCE_MOUSE_RELATIVE,x,y);}
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static TouchpadView view(XServer server){return new TouchpadView(new Context(),server,true);}
 static void disabledCaptureCannotLeakToX11(){
  XServer s=new XServer();TouchpadView v=view(s);v.setEnabled(false);
  check(v.onCapturedPointer(v,move(5,-4)),"disabled capture must be consumed");
  check(s.moves==0&&s.rawMoves==0,"disabled captured move leaked to X11");
  check(v.onCapturedPointer(v,new MotionEvent(MotionEvent.ACTION_BUTTON_PRESS,InputDevice.SOURCE_MOUSE_RELATIVE,0,0).button(MotionEvent.BUTTON_PRIMARY)),"disabled captured button must be consumed");
  check(s.presses==0&&s.releases==0,"disabled captured button leaked to X11");
  check(s.handler.controllerDiagnostics.count("captured","disabled")==2,"disabled capture category missing");
 }
 static void idleCaptureCannotSelectMouseMode(){
  XServer s=new XServer();TouchpadView v=view(s);final int[] notifications={0};
  s.pointer.addOnPointerMotionListener(new Pointer.OnPointerMotionListener(){public void onPointerMove(short x,short y){notifications[0]++;}});
  for(int i=0;i<10;i++)check(v.onCapturedPointer(v,move(0,-0f)),"idle capture must be consumed");
  v.setSensitivity(0);v.onCapturedPointer(v,move(4,2));
  check(s.moves==0&&s.rawMoves==0&&notifications[0]==0,"idle or zero-scaled capture emitted pointer motion");
  check(s.handler.controllerDiagnostics.count("captured","zero")==11,"zero capture category missing");
 }
 static void legitimateCaptureKeepsMotionButtonsAndScroll(){
  XServer s=new XServer();TouchpadView v=view(s);v.onCapturedPointer(v,move(7,-8));
  check(s.moves==1&&s.lastDx==11&&s.lastDy==-12,"captured acceleration or rounding changed");
  v.onCapturedPointer(v,new MotionEvent(MotionEvent.ACTION_BUTTON_PRESS,InputDevice.SOURCE_MOUSE_RELATIVE,0,0).button(MotionEvent.BUTTON_PRIMARY));
  v.onCapturedPointer(v,new MotionEvent(MotionEvent.ACTION_BUTTON_RELEASE,InputDevice.SOURCE_MOUSE_RELATIVE,0,0).button(MotionEvent.BUTTON_PRIMARY));
  MotionEvent wheel=new MotionEvent(MotionEvent.ACTION_SCROLL,InputDevice.SOURCE_MOUSE_RELATIVE,0,0);wheel.scroll=-1;
  v.onCapturedPointer(v,wheel);
  check(s.presses==2&&s.releases==2,"legitimate captured clicks/scroll changed");
  check(s.handler.controllerDiagnostics.count("captured","move")==1,"captured move category missing");
  check(s.handler.controllerDiagnostics.count("captured","button")==2,"captured button category missing");
  check(s.handler.controllerDiagnostics.count("captured","scroll")==1,"captured scroll category missing");
  check(s.handler.controllerDiagnostics.count("external","button")==0,"captured events double-counted as external");
  s.handler.controllerDiagnostics.mode="menu_cursor";v.onCapturedPointer(v,move(1,0));
  check(s.handler.controllerDiagnostics.count("captured","move")==1,"mode attribution leaked");
 }
 static void physicalMouseAndTouchRemainUsableInNative(){
  XServer s=new XServer();TouchpadView v=view(s);
  check(v.onExternalMouseEvent(new MotionEvent(MotionEvent.ACTION_HOVER_MOVE,InputDevice.SOURCE_MOUSE,100,200)),"Native physical mouse blocked");
  v.onExternalMouseEvent(new MotionEvent(MotionEvent.ACTION_BUTTON_PRESS,InputDevice.SOURCE_MOUSE,0,0).button(MotionEvent.BUTTON_SECONDARY));
  v.onExternalMouseEvent(new MotionEvent(MotionEvent.ACTION_BUTTON_RELEASE,InputDevice.SOURCE_MOUSE,0,0).button(MotionEvent.BUTTON_SECONDARY));
  v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN,InputDevice.SOURCE_TOUCHSCREEN,100,200));
  v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_MOVE,InputDevice.SOURCE_TOUCHSCREEN,125,200));
  v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP,InputDevice.SOURCE_TOUCHSCREEN,125,200));
  check(s.moves==2&&s.presses==1&&s.releases==1,"Native touch/mouse emissions changed");
  check(s.handler.controllerDiagnostics.count("external","move")==1&&s.handler.controllerDiagnostics.count("external","button")==2,"external categories missing");
  check(s.handler.controllerDiagnostics.count("touch","move")==1,"touch category missing");
  s.relative=true;v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN,InputDevice.SOURCE_TOUCHSCREEN,0,0));
  v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_MOVE,InputDevice.SOURCE_TOUCHSCREEN,20,0));
  check(s.handler.relativeMoves==1,"relative touch route changed");
 }
 static void tap(TouchpadView v){v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN,InputDevice.SOURCE_TOUCHSCREEN,100,200));v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP,InputDevice.SOURCE_TOUCHSCREEN,100,200));}
 static void modalCleanupInvalidatesDelayedReleaseAcrossReenable(){
  XServer s=new XServer();TouchpadView v=view(s);tap(v);
  check(s.presses==1&&s.releases==0,"touch up must retain its short click hold");
  s.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);v.setEnabled(false);v.setEnabled(true);
  v.onExternalMouseEvent(new MotionEvent(MotionEvent.ACTION_BUTTON_PRESS,InputDevice.SOURCE_MOUSE,0,0).button(MotionEvent.BUTTON_PRIMARY));
  View.advance(30);
  check(s.releases==1&&s.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT),"old delayed release duplicated cleanup or released later physical press");
  v.onExternalMouseEvent(new MotionEvent(MotionEvent.ACTION_BUTTON_RELEASE,InputDevice.SOURCE_MOUSE,0,0).button(MotionEvent.BUTTON_PRIMARY));
 }
 static void fingerIdentityProtectsALaterTouchPress(){
  XServer s=new XServer();TouchpadView v=view(s);tap(v);View.advance(25);
  s.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);tap(v);View.advance(5);
  check(s.releases==1&&s.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT),"old finger released a newer touch press at the reused pointer index");
  View.advance(25);check(s.releases==2&&!s.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT),"current finger release was lost");
 }
 static void disablingBalancesTouchOwnershipAndSuppressesLateTapWarp(){
  XServer s=new XServer();TouchpadView v=view(s);tap(v);v.setEnabled(false);
  check(s.releases==1&&!s.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT),"disabling left touch left a held button");
  View.advance(30);check(s.releases==1,"disabled left callback duplicated release");
  XServer right=new XServer();TouchpadView rv=view(right);
  rv.onTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN,InputDevice.SOURCE_TOUCHSCREEN,100,200));
  rv.onTouchEvent(new MotionEvent(MotionEvent.ACTION_POINTER_DOWN,InputDevice.SOURCE_TOUCHSCREEN,new float[]{100,110},new float[]{200,210}).index(1));
  rv.onTouchEvent(new MotionEvent(MotionEvent.ACTION_POINTER_UP,InputDevice.SOURCE_TOUCHSCREEN,new float[]{100,110},new float[]{200,210}).index(1));
  check(right.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT),"two-finger click was not held");rv.setEnabled(false);
  View.advance(30);check(right.releases==1&&!right.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT),"disabled right callback duplicated or lost release");
  XServer warp=new XServer();TouchpadView wv=view(warp);wv.setMoveCursorToTouchpoint(true);
  wv.onTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN,InputDevice.SOURCE_TOUCHSCREEN,100,200));wv.setEnabled(false);
  wv.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP,InputDevice.SOURCE_TOUCHSCREEN,100,200));
  check(warp.moves==0&&warp.presses==0,"disabled touch up warped or clicked pointer");
 }
 public static void main(String[] args){
  if(args.length>0){if(args[0].equals("idle"))idleCaptureCannotSelectMouseMode();else if(args[0].equals("modal"))modalCleanupInvalidatesDelayedReleaseAcrossReenable();else throw new AssertionError("unknown case");return;}
  disabledCaptureCannotLeakToX11();idleCaptureCannotSelectMouseMode();legitimateCaptureKeepsMotionButtonsAndScroll();physicalMouseAndTouchRemainUsableInNative();modalCleanupInvalidatesDelayedReleaseAcrossReenable();fingerIdentityProtectsALaterTouchPress();disablingBalancesTouchOwnershipAndSuppressesLateTapWarp();System.out.println("captured-pointer and delayed-touch routing: 7 checks passed");
 }
}
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT / "app/src/main/java/com/winlator/widget/TouchpadView.java")
    parser.add_argument("--case", choices=["idle", "modal"])
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="pd2-captured-pointer-") as directory:
        work = Path(directory)
        for relative, contents in STUBS.items():
            target = work / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(contents)
        target = work / "com/winlator/widget/TouchpadView.java"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(args.source.read_text())
        (target.parent / "CapturedPointerChecks.java").write_text(HARNESS)
        production = [ROOT / "app/src/main/java" / path for path in [
            "com/winlator/math/Mathf.java", "com/winlator/math/XForm.java",
            "com/winlator/core/Bitmask.java", "com/winlator/renderer/ViewTransformation.java",
            "com/winlator/xserver/Pointer.java",
        ]]
        subprocess.run(["javac", "-d", str(work / "classes"), *map(str, work.rglob("*.java")), *map(str, production)], check=True)
        subprocess.run(["java", "-ea", "-cp", str(work / "classes"), "com.winlator.widget.CapturedPointerChecks", *([args.case] if args.case else [])], check=True)


if __name__ == "__main__":
    main()
