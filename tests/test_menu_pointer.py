#!/usr/bin/env python3
"""Run the actual menu pointer helper with a clock and queued Windows-input sink.

The fake window tree follows XServer's signed geometry and bottom-to-top child
order. These checks establish routing/geometry behavior, not PD2 gameplay.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "android/os/SystemClock.java": """package android.os;
public final class SystemClock {
 public static long now=1000;
 public static long uptimeMillis() { return now; }
 public static long elapsedRealtime() { return now; }
}
""",
    "com/winlator/pd2/Pd2InputRouter.java": """package com.winlator.pd2;
import com.winlator.xserver.*;
public final class Pd2InputRouter {
 public interface MenuInput {
  void move(int dx,int dy);
  void button(Pointer.Button button,boolean down);
  void key(XKeycode key,boolean down);
 }
}
""",
    "com/winlator/xserver/XLock.java": """package com.winlator.xserver;
public interface XLock extends AutoCloseable { void close(); }
""",
    "com/winlator/xserver/ScreenInfo.java": """package com.winlator.xserver;
public final class ScreenInfo {
 public final short width,height;
 public ScreenInfo(int width,int height) { this.width=(short)width;this.height=(short)height; }
}
""",
    "com/winlator/xserver/Pointer.java": """package com.winlator.xserver;
public final class Pointer {
 public enum Button { BUTTON_LEFT,BUTTON_MIDDLE,BUTTON_RIGHT,BUTTON_SCROLL_UP,BUTTON_SCROLL_DOWN,
  BUTTON_SCROLL_CLICK_LEFT,BUTTON_SCROLL_CLICK_RIGHT }
 private short x,y;
 public short maxX=32767,maxY=32767;
 public short getX() { return x; }
 public short getY() { return y; }
 public short getClampedX() { return (short)Math.max(0,Math.min(maxX,x)); }
 public short getClampedY() { return (short)Math.max(0,Math.min(maxY,y)); }
 public void setX(int value) { x=(short)value; }
 public void setY(int value) { y=(short)value; }
 public void setPosition(int x,int y) { setX(x);setY(y); }
}
""",
    "com/winlator/xserver/Cursor.java": """package com.winlator.xserver;
public final class Cursor {
 public boolean visible=true;
 public boolean isVisible() { return visible; }
}
""",
    "com/winlator/xserver/WindowAttributes.java": """package com.winlator.xserver;
public final class WindowAttributes {
 public Cursor cursor;
 public Cursor getCursor() { return cursor; }
}
""",
    "com/winlator/xserver/Window.java": """package com.winlator.xserver;
import java.util.*;
public class Window {
 public enum MapState { UNMAPPED,UNVIEWABLE,VIEWABLE }
 public final int id;
 public final WindowAttributes attributes=new WindowAttributes();
 public String name,className;
 public int pid;
 public long handle;
 public short x,y,width,height;
 public boolean mapped=true,inputOutput=true,surface=false,desktop=false;
 private Window parent;
 private final ArrayList<Window> children=new ArrayList<>();
 public Window(int id,String name,String className,int x,int y,int width,int height) {
  this.id=id;this.name=name;this.className=className;
  this.x=(short)x;this.y=(short)y;this.width=(short)width;this.height=(short)height;
  this.pid=id+100;this.handle=id+1000;
 }
 public String getName() { return name; }
 public String getClassName() { return className; }
 public int getProcessId() { return pid; }
 public long getHandle() { return handle; }
 public short getX() { return x; }
 public short getY() { return y; }
 public short getRootX() { return (short)(x+(parent==null?0:parent.getRootX())); }
 public short getRootY() { return (short)(y+(parent==null?0:parent.getRootY())); }
 public short getRootX(boolean transform) { return getRootX(); }
 public short getRootY(boolean transform) { return getRootY(); }
 public short getWidth() { return width; }
 public short getHeight() { return height; }
 public Window getParent() { return parent; }
 public List<Window> getChildren() { return Collections.unmodifiableList(children); }
 public void addChild(Window child) { child.parent=this;children.add(child); }
 public boolean isInputOutput() { return inputOutput; }
 public boolean isSurface() { return surface; }
 public boolean isDesktopWindow() { return desktop || "explorer.exe".equals(className); }
 public boolean isRenderable() { return mapped && width>1 && height>1 && inputOutput; }
 public MapState getMapState() {
  if(!mapped) return MapState.UNMAPPED;
  for(Window p=parent;p!=null;p=p.parent) if(!p.mapped) return MapState.UNVIEWABLE;
  return MapState.VIEWABLE;
 }
}
""",
    "com/winlator/xserver/WindowManager.java": """package com.winlator.xserver;
import java.util.List;
public final class WindowManager {
 public final Window rootWindow;
 public Window focused;
 public WindowManager(ScreenInfo screen) {
  rootWindow=new Window(1,"PRIVATE_ROOT_TITLE","",0,0,screen.width,screen.height);
  rootWindow.pid=0;rootWindow.handle=0;
 }
 public Window getFocusedWindow() { return focused; }
 public Window findPointWindow(short x,short y,boolean transform) { return at(rootWindow,x,y); }
 private Window at(Window window,int x,int y) {
  if(!window.mapped || x<window.getRootX() || y<window.getRootY()
   || x>=window.getRootX()+window.getWidth() || y>=window.getRootY()+window.getHeight()) return null;
  List<Window> children=window.getChildren();
  for(int i=children.size()-1;i>=0;i--) {
   Window result=at(children.get(i),x,y);
   if(result!=null) return result;
  }
  return window;
 }
}
""",
    "com/winlator/xserver/InputDeviceManager.java": """package com.winlator.xserver;
public final class InputDeviceManager {
 public Window point;
 public Window getPointWindow() { return point; }
}
""",
    "com/winlator/xserver/GrabManager.java": """package com.winlator.xserver;
public final class GrabManager {
 public Window window;
 public Window getWindow() { return window; }
}
""",
    "com/winlator/xserver/XServer.java": """package com.winlator.xserver;
import com.winlator.renderer.GLRenderer;
public final class XServer {
 public enum Lockable { WINDOW_MANAGER,INPUT_DEVICE }
 public final ScreenInfo screenInfo;
 public final Pointer pointer=new Pointer();
 public final WindowManager windowManager;
 public final InputDeviceManager inputDeviceManager=new InputDeviceManager();
 public final GrabManager grabManager=new GrabManager();
 public boolean relative;
 public GLRenderer renderer;
 public XServer(int width,int height) {
  screenInfo=new ScreenInfo(width,height);windowManager=new WindowManager(screenInfo);
  pointer.maxX=(short)(width-1);pointer.maxY=(short)(height-1);
  inputDeviceManager.point=windowManager.rootWindow;
 }
 public XLock lock(Lockable... resources) { return () -> {}; }
 public boolean isRelativeMouseMovement() { return relative; }
 public GLRenderer getRenderer() { return renderer; }
 public void injectKeyPress(XKeycode key) { throw new AssertionError("Menu used XServer key press"); }
 public void injectKeyRelease(XKeycode key) { throw new AssertionError("Menu used XServer key release"); }
 public void injectPointerButtonPress(Pointer.Button button) { throw new AssertionError("Menu used XServer button press"); }
 public void injectPointerButtonRelease(Pointer.Button button) { throw new AssertionError("Menu used XServer button release"); }
 public void injectPointerMoveDelta(int dx,int dy) { throw new AssertionError("Menu used XServer pointer move"); }
}
""",
    "com/winlator/renderer/GLRenderer.java": """package com.winlator.renderer;
public final class GLRenderer {
 public boolean forceRoot;
 public boolean isForceRootCursor() { return forceRoot; }
}
""",
    "com/winlator/pd2/Pd2ControllerDiagnostics.java": """package com.winlator.pd2;
public final class Pd2ControllerDiagnostics { public void recordPointerOutput(String kind) {} }
""",
    "com/winlator/winhandler/WinHandler.java": """package com.winlator.winhandler;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.winlator.pd2.Pd2ControllerDiagnostics;
public final class WinHandler {
 public static final class Event {
  public final boolean mouse;
  public final int flags,dx,dy,wheel,vkey;
  final BooleanSupplier allowed;
  Event(boolean mouse,int flags,int dx,int dy,int wheel,int vkey,BooleanSupplier allowed) {
   this.mouse=mouse;this.flags=flags;this.dx=dx;this.dy=dy;this.wheel=wheel;this.vkey=vkey;this.allowed=allowed;
  }
 }
 public final Pd2ControllerDiagnostics controllerDiagnostics=new Pd2ControllerDiagnostics();
 public boolean ready=true;
 public final ArrayList<Event> queued=new ArrayList<>(),delivered=new ArrayList<>();
 public boolean isInputReady() { return ready; }
 public void bringToFront(String name,long handle) {}
 public void mouseEvent(int flags,int dx,int dy,int wheel) { mouseEvent(flags,dx,dy,wheel,() -> true); }
 public void mouseEvent(int flags,int dx,int dy,int wheel,BooleanSupplier allowed) {
  queued.add(new Event(true,flags,dx,dy,wheel,0,allowed));
 }
 public void keyboardEvent(byte vkey,int flags) { keyboardEvent(vkey,flags,() -> true); }
 public void keyboardEvent(byte vkey,int flags,BooleanSupplier allowed) {
  queued.add(new Event(false,flags,0,0,0,vkey&255,allowed));
 }
 public void drain() { for(Event event:queued) if(event.allowed.getAsBoolean()) delivered.add(event);queued.clear(); }
 public void clear() { queued.clear();delivered.clear(); }
}
""",
    "MenuPointerTest.java": """import android.os.SystemClock;
import com.winlator.pd2.Pd2MenuPointer;
import com.winlator.winhandler.*;
import com.winlator.xserver.*;
import java.util.*;
public final class MenuPointerTest {
 static int checks;
 static void check(boolean ok,String message) { checks++;if(!ok) throw new AssertionError(message); }
 static final class Fixture {
  final XServer x;
  final WinHandler win=new WinHandler();
  final Pd2MenuPointer pointer;
  Fixture(int width,int height) { SystemClock.now=1000;x=new XServer(width,height);pointer=new Pd2MenuPointer(x,win); }
  Window game(int id,int x,int y,int width,int height) {
   Window window=new Window(id,"Diablo II PRIVATE_GAME_TITLE","Game.exe",x,y,width,height);
   this.x.windowManager.rootWindow.addChild(window);return window;
  }
  void feedback(int x,int y) { this.x.pointer.setPosition(x,y);pointer.onCursorFeedback(x,y); }
  WinHandler.Event last() { win.drain();check(!win.delivered.isEmpty(),"Missing Windows input");return win.delivered.get(win.delivered.size()-1); }
  void activateAt(int x,int y) { pointer.activate();win.drain();feedback(x,y);win.drain();feedback(x,y);win.clear(); }
 }
 static void move(WinHandler.Event event,int dx,int dy,String message) {
  check(event.mouse && event.flags==1 && event.dx==dx && event.dy==dy && event.wheel==0,message);
 }
 static void activationRecentersOnceAndNeverUsesXServerInput() {
  Fixture f=new Fixture(1280,720);f.game(2,0,0,801,601);f.x.pointer.setPosition(90,80);
  f.pointer.activate();move(f.last(),0,0,"Activation trusted stale XServer position instead of querying Windows");
  f.pointer.button(Pointer.Button.BUTTON_LEFT,true);check(f.win.queued.isEmpty(),"Click was allowed before initial feedback");
  f.pointer.onCursorFeedback(100,200);move(f.last(),300,100,"Recenter ignored fresh Windows feedback");
  f.pointer.button(Pointer.Button.BUTTON_LEFT,true);check(f.win.queued.isEmpty(),"Click was allowed while recenter was pending");
  f.pointer.activate();f.win.drain();check(f.win.delivered.size()==2,"Repeated activate recentered again");
  f.feedback(400,300);f.win.clear();f.pointer.move(10,-20);
  move(f.last(),10,-20,"Menu MOVE did not use Windows relative backend");
 }
 static void missingInitialFeedbackRetriesQueryWithoutReplayingMovement() {
  Fixture f=new Fixture(1280,720);f.game(2,0,0,801,601);f.x.pointer.setPosition(1068,700);
  f.pointer.activate();move(f.last(),0,0,"Initial sync was not a zero-delta query");
  f.pointer.move(99,99);check(f.win.queued.isEmpty(),"Initial sync did not limit in-flight movement");
  SystemClock.now+=125;f.pointer.move(42,42);move(f.last(),0,0,"Lost initial query retried accumulated stick deltas");
  f.pointer.onCursorFeedback(500,300);move(f.last(),-100,0,"Initial query did not recenter from actual feedback");
  f.feedback(400,300);f.win.clear();f.pointer.move(2,-2);move(f.last(),2,-2,"Normal motion failed after initial sync recovered");
 }
 static void feedbackGuardTimeoutAndDroppedBursts() {
  Fixture f=new Fixture(1280,720);f.game(2,0,0,1280,720);f.activateAt(640,360);
  f.pointer.move(10,5);move(f.last(),10,5,"First move missing");
  f.pointer.move(100,100);f.pointer.move(100,100);
  check(f.win.queued.isEmpty(),"Unacknowledged moves accumulated a burst");
  SystemClock.now+=124;f.pointer.move(99,99);check(f.win.queued.isEmpty(),"Timeout fired before125ms");
  SystemClock.now++;f.pointer.move(7,-4);move(f.last(),7,-4,"Timeout failed or replayed skipped moves");
  f.feedback(647,356);f.win.clear();f.pointer.move(3,-2);
  move(f.last(),3,-2,"Feedback failed to allow the next isolated move");
 }
 static void liveWindowShrinkCorrectsOutsideFeedbackPosition() {
  Fixture f=new Fixture(1280,720);Window game=f.game(2,0,0,1280,720);f.activateAt(640,360);
  f.pointer.move(428,0);f.win.drain();f.feedback(1068,360);f.win.clear();
  game.width=800;f.pointer.move(14,0);
  move(f.last(),-269,0,"Resize800 retained stale1068 or1280 pointer bounds");
  f.feedback(799,360);f.win.clear();f.pointer.move(14,0);
  check(f.win.queued.isEmpty(),"Clipped edge emitted an empty or out-of-range MOVE");
  f.pointer.move(-40,0);move(f.last(),-40,0,"Pointer could not return from resized right edge");
  f.feedback(759,360);f.win.clear();game.width=600;f.pointer.move(40,0);
  move(f.last(),-160,0,"Second live shrink failed to correct actual pointer position");
 }
 static void negativeCoordinatesAndSurfaceAreClippedToAncestorsAndScreen() {
  Fixture f=new Fixture(1280,720);Window game=f.game(2,-100,50,1001,601);
  Window surface=new Window(3,"PRIVATE_SURFACE_TITLE","",50,20,801,501);surface.surface=true;surface.pid=game.pid;game.addChild(surface);
  f.pointer.activate();move(f.last(),0,0,"Surface activation skipped Windows sync");
  f.feedback(0,0);move(f.last(),375,320,"Surface child center ignored parent/desktop clipping");
  f.feedback(375,320);f.win.clear();f.pointer.move(-5000,-5000);
  move(f.last(),-375,-250,"Negative origin was unsigned or child escaped visible left/top bounds");
  f.feedback(0,70);f.win.clear();f.pointer.move(5000,5000);
  move(f.last(),750,500,"Surface child escaped clipped right/bottom bounds");
 }
 static void wrongFocusUsesTopmostGameAndEligibleFocusWins() {
  Fixture f=new Fixture(1280,720);Window older=f.game(2,20,40,401,301);
  Window newer=f.game(3,700,100,501,401);newer.className="DiabloII";
  Window explorer=new Window(4,"PRIVATE_DESKTOP_TITLE","explorer.exe",0,0,1280,720);
  f.x.windowManager.rootWindow.addChild(explorer);f.x.windowManager.focused=explorer;
  Window inputOnly=f.game(5,0,0,1200,700);inputOnly.inputOutput=false;
  Window unmapped=f.game(6,0,0,1200,700);unmapped.mapped=false;
  Window hiddenParent=new Window(7,"PRIVATE_HIDDEN_TITLE","unknown.exe",0,0,1280,720);hiddenParent.mapped=false;
  Window hiddenChild=new Window(8,"Diablo II PRIVATE_HIDDEN_GAME","Game.exe",0,0,1200,700);
  hiddenParent.addChild(hiddenChild);f.x.windowManager.rootWindow.addChild(hiddenParent);
  f.pointer.activate();move(f.last(),0,0,"Topmost-game activation skipped Windows sync");
  f.feedback(0,0);move(f.last(),950,300,"Wrong focus/topmost excluded windows hid the live game");
  f.pointer.deactivate();f.win.drain();f.win.clear();f.x.pointer.setPosition(0,0);f.x.windowManager.focused=older;
  f.pointer.activate();f.win.drain();f.feedback(0,0);move(f.last(),220,190,"Eligible focused game did not override the topmost candidate");
 }
 static void screenFallbackDoesNotTreatRootOrExplorerAsGame() {
  Fixture f=new Fixture(1025,769);
  Window explorer=new Window(2,"PRIVATE_DESKTOP_TITLE","explorer.exe",70,80,400,300);
  f.x.windowManager.rootWindow.addChild(explorer);f.x.windowManager.focused=explorer;
  f.pointer.activate();move(f.last(),0,0,"Fallback activation skipped Windows sync");
  f.feedback(0,0);move(f.last(),512,384,"No-game fallback did not use full screen bounds");
  f.feedback(512,384);f.win.clear();f.pointer.move(5000,5000);
  move(f.last(),512,384,"Screen fallback did not clip right/bottom edges");
 }
 static void vkMappingLeftClickAndIgnoredControls() {
  Fixture f=new Fixture(1280,720);f.game(2,0,0,800,600);f.activateAt(400,300);
  f.pointer.button(Pointer.Button.BUTTON_LEFT,true);f.pointer.button(Pointer.Button.BUTTON_LEFT,true);
  f.pointer.button(Pointer.Button.BUTTON_LEFT,false);f.win.drain();
  check(f.win.delivered.size()==2,"Repeated press or left release count wrong");
  check(f.win.delivered.get(0).mouse && f.win.delivered.get(0).flags==2,"Left press flag notWindows LEFTDOWN");
  check(f.win.delivered.get(1).mouse && f.win.delivered.get(1).flags==4,"Left release flag notWindows LEFTUP");
  f.win.clear();
  XKeycode[] keys={XKeycode.KEY_ESC,XKeycode.KEY_ENTER,XKeycode.KEY_TAB,XKeycode.KEY_LEFT,
   XKeycode.KEY_UP,XKeycode.KEY_RIGHT,XKeycode.KEY_DOWN};
  int[] vk={0x1b,0x0d,0x09,0x25,0x26,0x27,0x28};
  for(int i=0;i<keys.length;i++) {
   f.pointer.key(keys[i],true);f.pointer.key(keys[i],true);f.pointer.key(keys[i],false);f.win.drain();
   check(f.win.delivered.size()==2,"Duplicate held key or unbalanced release");
   check(!f.win.delivered.get(0).mouse && f.win.delivered.get(0).vkey==vk[i] && f.win.delivered.get(0).flags==0,"Key used X11 keycode instead ofWindows VK");
   check(!f.win.delivered.get(1).mouse && f.win.delivered.get(1).vkey==vk[i] && f.win.delivered.get(1).flags==2,"Key release omittedWindows KEYUP");
   f.win.clear();
  }
  f.pointer.key(XKeycode.KEY_W,true);f.pointer.key(XKeycode.KEY_W,false);
  f.pointer.key(XKeycode.KEY_NONE,true);f.pointer.key(XKeycode.KEY_NONE,false);
  f.pointer.button(Pointer.Button.BUTTON_RIGHT,true);f.pointer.button(Pointer.Button.BUTTON_RIGHT,false);
  f.pointer.button(Pointer.Button.BUTTON_MIDDLE,true);f.pointer.button(Pointer.Button.BUTTON_MIDDLE,false);
  check(f.win.queued.isEmpty(),"Unrecognized menu key/button reached the Windows backend");
 }
 static void deactivateBalancesHeldInputAndRejectsFurtherOutput() {
  Fixture f=new Fixture(1280,720);f.game(2,0,0,800,600);f.activateAt(400,300);
  f.pointer.button(Pointer.Button.BUTTON_LEFT,true);f.pointer.key(XKeycode.KEY_ESC,true);f.win.drain();f.win.clear();
  f.pointer.deactivate();f.win.drain();
  check(f.win.delivered.size()==2,"Deactivate did not balance heldWindows inputs");
  int buttonUps=0,keyUps=0;
  for(WinHandler.Event event:f.win.delivered) {
   if(event.mouse && event.flags==4) buttonUps++;
   if(!event.mouse && event.vkey==0x1b && event.flags==2) keyUps++;
  }
  check(buttonUps==1 && keyUps==1,"Deactivate released wrong Windows inputs");
  f.win.clear();f.pointer.button(Pointer.Button.BUTTON_LEFT,false);f.pointer.key(XKeycode.KEY_ESC,false);
  f.pointer.button(Pointer.Button.BUTTON_LEFT,true);f.pointer.key(XKeycode.KEY_ENTER,true);f.pointer.move(20,20);
  f.feedback(999,999);f.pointer.deactivate();
  check(f.win.queued.isEmpty(),"Mode-off feedback/input caused output or duplicate releases");
 }
 static void queuedActionsAreGuardedAcrossModeChangeAndReactivation() {
  Fixture f=new Fixture(1280,720);f.game(2,0,0,800,600);f.activateAt(400,300);
  f.pointer.move(10,20);f.pointer.button(Pointer.Button.BUTTON_LEFT,true);f.pointer.key(XKeycode.KEY_ENTER,true);
  f.pointer.deactivate();f.win.drain();
  check(f.win.delivered.size()==2,"Stale queued moves/downs escaped the activation guard");
  for(WinHandler.Event event:f.win.delivered)
   check((event.mouse && event.flags==4)||(!event.mouse && event.flags==2),"Only balancing releases may survive deactivation");
  f.win.clear();f.pointer.activate();f.pointer.deactivate();f.pointer.activate();f.win.drain();
  check(f.win.delivered.size()==1,"An older activation's queued recenter survived reactivation");
 }
 static void notReadyProducesNoRequestsAndContextContainsNoPrivateTitles() {
  Fixture f=new Fixture(1280,720);Window game=f.game(2,20,30,800,600);
  f.x.windowManager.focused=game;
  f.x.inputDeviceManager.point=f.x.windowManager.rootWindow; // Deliberately stale cached point window.
  f.x.pointer.setPosition(200,200);
  f.x.grabManager.window=new Window(20,"PRIVATE_GRAB_TITLE","C:\\\\private\\\\creds.txt",0,0,10,10);
  game.attributes.cursor=new Cursor();game.attributes.cursor.visible=false;f.x.relative=true;
  f.win.ready=false;f.pointer.activate();f.pointer.move(20,20);f.pointer.button(Pointer.Button.BUTTON_LEFT,true);
  f.pointer.key(XKeycode.KEY_ENTER,true);f.pointer.deactivate();
  check(f.win.queued.isEmpty(),"Not-ready bridge received a queued input request");
  Map<?,?> context=f.pointer.captureContext();
  Set<String> top=new HashSet<>(Arrays.asList("screenWidth","screenHeight","pointerX","pointerY","relative",
   "gameCursorVisible","forceRoot","focusWindow","pointWindow","grabWindow","menuWindow"));
  Set<String> nested=new HashSet<>(Arrays.asList("id","x","y","width","height","class"));
  check(context.get("screenWidth").equals(1280) && context.get("screenHeight").equals(720),"Context lost live screen dimensions");
  check(((Map<?,?>)context.get("pointWindow")).get("id").equals(game.id),"Capture used stale cached point window after feedback moved the pointer");
  check(Boolean.FALSE.equals(context.get("gameCursorVisible")),"Cursor visibility came from stale point-window cache");
  check(!context.toString().contains("private"),"Capture leaked a path-shaped window class");
  for(Map.Entry<?,?> entry:context.entrySet()) {
   check(top.contains(entry.getKey()),"Non-whitelisted pointer context field: "+entry.getKey());
   if(entry.getValue() instanceof Map) {
    Map<?,?> window=(Map<?,?>)entry.getValue();
    for(Map.Entry<?,?> item:window.entrySet()) {
     check(nested.contains(item.getKey()),"Non-whitelisted nested context field: "+item.getKey());
     if(item.getKey().equals("class")) check(item.getValue() instanceof String,"Window class was not routing metadata");
     else check(item.getValue() instanceof Number,"Window geometry was not numeric");
    }
   }
  }
  check(!context.toString().contains("PRIVATE_"),"Capture context leaked private window titles");
 }
 public static void main(String[] args) {
  activationRecentersOnceAndNeverUsesXServerInput();missingInitialFeedbackRetriesQueryWithoutReplayingMovement();feedbackGuardTimeoutAndDroppedBursts();
  liveWindowShrinkCorrectsOutsideFeedbackPosition();negativeCoordinatesAndSurfaceAreClippedToAncestorsAndScreen();
  wrongFocusUsesTopmostGameAndEligibleFocusWins();screenFallbackDoesNotTreatRootOrExplorerAsGame();
  vkMappingLeftClickAndIgnoredControls();deactivateBalancesHeldInputAndRejectsFurtherOutput();
  queuedActionsAreGuardedAcrossModeChangeAndReactivation();notReadyProducesNoRequestsAndContextContainsNoPrivateTitles();
  System.out.println("PASS: 11 menu pointer scenarios; "+checks+" checks");
 }
}
""",
}

with tempfile.TemporaryDirectory(prefix="pd2-menu-pointer-test-") as temporary:
    directory = Path(temporary)
    files = []
    for name, source in STUBS.items():
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source)
        files.append(str(target))
    files.extend(str(ROOT / "app/src/main/java" / name) for name in (
        "com/winlator/pd2/Pd2MenuPointer.java",
        "com/winlator/xserver/XKeycode.java",
        "com/winlator/winhandler/MouseEventFlags.java",
    ))
    subprocess.run(["javac", "-d", str(directory), *files], check=True)
    subprocess.run(["java", "-cp", str(directory), "MenuPointerTest"], check=True)
