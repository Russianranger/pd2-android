#!/usr/bin/env python3
"""Compile the production Present handler and pacer against observable host stubs.

Exercises real request parsing, resource revalidation, copy/event/fence ordering
and lock release around producer waits. GPU copies retain their legacy deferred
idle release. Caps are upper bounds; achieved throughput may be lower. Does not
emulate GPU completion or claim game FPS.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}",
    "android/util/SparseArray.java": """package android.util;
import java.util.*;
public class SparseArray<T> {
 private final LinkedHashMap<Integer,T> map=new LinkedHashMap<>();
 public int size(){return map.size();} public T get(int id){return map.get(id);}
 public T valueAt(int index){return new ArrayList<T>(map.values()).get(index);}
 public void put(int id,T value){map.put(id,value);} public void remove(int id){map.remove(id);}
 public void removeAt(int index){map.remove(new ArrayList<Integer>(map.keySet()).get(index));}
}
""",
    "org/json/JSONException.java": "package org.json; public class JSONException extends Exception {}",
    "org/json/JSONObject.java": """package org.json;
import java.util.*;
public final class JSONObject {
 private final Map<String,Object> map=new LinkedHashMap<>();
 public JSONObject put(String key,Object value)throws JSONException{map.put(key,value);return this;}
 public long getLong(String key){return ((Number)map.get(key)).longValue();}
 public int getInt(String key){return ((Number)map.get(key)).intValue();}
 public boolean getBoolean(String key){return (Boolean)map.get(key);}
 public String getString(String key){return (String)map.get(key);}
 public String toString(){return map.toString();}
}
""",
    "com/winlator/testing/Guard.java": """package com.winlator.testing;
import java.util.*; import java.util.function.LongSupplier; import com.winlator.xserver.*; import com.winlator.xconnector.*;
public final class Guard {
 public static XServer server; public static Object events,pacer;
 public static XOutputStream output;
 public static final List<Object> renderLocks=new ArrayList<>();
 public static final List<String> trace=new ArrayList<>();
 public static LongSupplier clock;public static final List<Long> copyTimes=new ArrayList<>();
 public static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void unlocked(){
  check(!server.held(),"producer wait held a manager lock");
  for(Object lock:renderLocks)check(!Thread.holdsLock(lock),"producer wait held a render lock");
  check(!Thread.holdsLock(events),"producer wait held event monitor");
  check(!Thread.holdsLock(pacer),"producer wait held pacer monitor");
  check(!output.held(),"producer wait held output lock");
 }
 public static void copy(Object renderLock){
  check(server.heldBoth(),"copy lost manager locks");
  check(Thread.holdsLock(renderLock),"copy lost render lock");
 }
}
""",
    "com/winlator/xconnector/XStreamLock.java": "package com.winlator.xconnector; public interface XStreamLock extends AutoCloseable {void close();}",
    "com/winlator/xconnector/XInputStream.java": """package com.winlator.xconnector;
import java.nio.*; import com.winlator.testing.Guard;
public final class XInputStream {
 private final ByteBuffer bytes; private final boolean unlocked;
 public XInputStream(byte[] data,boolean unlocked){bytes=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);this.unlocked=unlocked;}
 private void read(){if(unlocked)Guard.check(!Guard.server.held(),"Present fields parsed while manager lock held");}
 public int readInt(){read();return bytes.getInt();} public short readShort(){read();return bytes.getShort();}
 public long readLong(){read();return bytes.getLong();}
 public void skip(int count){read();bytes.position(bytes.position()+count);}
 public int remaining(){return bytes.remaining();}
}
""",
    "com/winlator/xconnector/XOutputStream.java": """package com.winlator.xconnector;
import java.io.*; import java.util.concurrent.locks.*;
public final class XOutputStream {
 private final ReentrantLock mutex=new ReentrantLock(); private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
 public XStreamLock lock(){mutex.lock();return ()->mutex.unlock();}
 public boolean held(){return mutex.isHeldByCurrentThread();}
 public void writeByte(byte value){bytes.write(value);} public void writeShort(short v){writeIntBytes(v,2);}
 public void writeInt(int v){writeIntBytes(v,4);} private void writeIntBytes(int v,int n){for(int i=0;i<n;i++)bytes.write(v>>>(i*8));}
 public void writePad(int n){for(int i=0;i<n;i++)bytes.write(0);} public byte[] bytes(){return bytes.toByteArray();}
}
""",
    "com/winlator/core/GPUHelper.java": """package com.winlator.core;
public final class GPUHelper {public static int created,destroyed;
 public static long createOffscreenEGLContext(boolean flag){created++;return 42;}
 public static void destroyOffscreenEGLContext(long pointer){if(pointer!=42)throw new AssertionError("wrong EGL context");destroyed++;}
}
""",
    "com/winlator/renderer/Texture.java": """package com.winlator.renderer;
import com.winlator.testing.Guard;
public class Texture {public int copies; public void copyFromSource(Texture source){copies++;Guard.copyTimes.add(Guard.clock.getAsLong());Guard.trace.add("gpu-copy");}}
""",
    "com/winlator/renderer/GPUImage.java": """package com.winlator.renderer;
import com.winlator.xserver.*;
public final class GPUImage extends Texture {public static void createOrObtain(XServer server,Drawable drawable,boolean a,boolean b){}}
""",
    "com/winlator/xserver/XResource.java": "package com.winlator.xserver; public class XResource {public final int id;public XResource(int id){this.id=id;}}",
    "com/winlator/xserver/XResourceManager.java": """package com.winlator.xserver;
public class XResourceManager {public interface OnResourceLifecycleListener {void onFreeResource(XResource resource);}}
""",
    "com/winlator/xserver/Drawable.java": """package com.winlator.xserver;
import com.winlator.renderer.*;import com.winlator.testing.Guard;
public final class Drawable extends XResource {
 public static final class Visual{public int depth;Visual(int depth){this.depth=depth;}}
 public final Object renderLock=new Object(); public final Visual visual;
 public final short width=320,height=240; public Texture texture=new Texture();
 public int copies,updates,lastSource,lastX,lastY; public Drawable(int id,int depth){super(id);visual=new Visual(depth);Guard.renderLocks.add(renderLock);}
 public Texture getTexture(){return texture;} public void setData(Object value){Guard.copy(renderLock);Guard.trace.add("data");}
 public void forceUpdate(){Guard.copy(renderLock);updates++;Guard.trace.add("update");}
 public void copyArea(short sx,short sy,short x,short y,short w,short h,Drawable source){
  Guard.copy(renderLock);Guard.check(sx==0&&sy==0&&w==source.width&&h==source.height,"copy bounds changed");
  copies++;lastSource=source.id;lastX=x;lastY=y;Guard.copyTimes.add(Guard.clock.getAsLong());Guard.trace.add("copy:"+source.id);
 }
}
""",
    "com/winlator/xserver/Window.java": """package com.winlator.xserver;
import java.util.*;
public final class Window extends XResource {
 public final Drawable content;private final Map<String,Object> tags=new HashMap<>();
 public Window(int id,Drawable content){super(id);this.content=content;}public Drawable getContent(){return content;}
 public Object getTag(String name,Object fallback){return tags.getOrDefault(name,fallback);}
 public void setTag(String name,Object value){tags.put(name,value);} public void removeTag(String name){tags.remove(name);}
}
""",
    "com/winlator/xserver/Pixmap.java": "package com.winlator.xserver; public final class Pixmap extends XResource {public final Drawable drawable;public Pixmap(int id,Drawable d){super(id);drawable=d;}}",
    "com/winlator/xserver/WindowManager.java": """package com.winlator.xserver;
import java.util.*;
public final class WindowManager extends XResourceManager {
 public interface OnWindowModificationListener{default void onUpdateWindowGeometry(Window window,boolean resized){}}
 public final Map<Integer,Window> windows=new HashMap<>();public final List<OnResourceLifecycleListener> frees=new ArrayList<>();
 public void addOnWindowModificationListener(OnWindowModificationListener listener){}
 public void addOnResourceLifecycleListener(OnResourceLifecycleListener listener){frees.add(listener);}
 public Window getWindow(int id){return windows.get(id);} public void destroy(int id){Window old=windows.remove(id);for(var f:frees)f.onFreeResource(old);}
}
""",
    "com/winlator/xserver/PixmapManager.java": """package com.winlator.xserver;
import java.util.*;public final class PixmapManager {public final Map<Integer,Pixmap> pixmaps=new HashMap<>();public Pixmap getPixmap(int id){return pixmaps.get(id);}}
""",
    "com/winlator/xserver/XServer.java": """package com.winlator.xserver;
import java.util.concurrent.locks.*;import com.winlator.xserver.extensions.SyncExtension;
public final class XServer {
 public enum Lockable{WINDOW_MANAGER,PIXMAP_MANAGER}
 public final WindowManager windowManager=new WindowManager();public final PixmapManager pixmapManager=new PixmapManager();
 public final SyncExtension sync=new SyncExtension();private final ReentrantLock[] locks={new ReentrantLock(),new ReentrantLock()};
 public XLock lock(Lockable... resources){for(var r:resources)locks[r.ordinal()].lock();return ()->{for(int i=resources.length-1;i>=0;i--)locks[resources[i].ordinal()].unlock();};}
 public boolean held(){return locks[0].isHeldByCurrentThread()||locks[1].isHeldByCurrentThread();}
 public boolean heldBoth(){return locks[0].isHeldByCurrentThread()&&locks[1].isHeldByCurrentThread();}
 public Object getExtensionByName(String name){return sync;}
}
""",
    "com/winlator/xserver/XClient.java": """package com.winlator.xserver;
import java.util.*;import java.util.function.*;import com.winlator.xconnector.*;import com.winlator.xserver.events.*;import com.winlator.testing.Guard;
public final class XClient {
 public int opcode;public XInputStream input;public final List<Object> sent=new ArrayList<>();public final List<Consumer<XClient>> destroys=new ArrayList<>();
 public int getRequestData(){return opcode;}public short getSequenceNumber(){return (short)0x105;}
 public int getRemainingRequestLength(){return input.remaining();}
 public void addOnDestroyListener(Consumer<XClient> listener){destroys.add(listener);}
 public void sendEvent(Object event){sent.add(event);
  if(event instanceof PresentCompleteNotify e)Guard.trace.add("complete:"+e.serial);
  else if(event instanceof PresentIdleNotify e)Guard.trace.add("idle:"+e.serial);
  else Guard.trace.add("configure");
 }
}
""",
    "com/winlator/xserver/XClientRequestHandler.java": "package com.winlator.xserver;public final class XClientRequestHandler{public static final byte RESPONSE_CODE_SUCCESS=1;}",
    "com/winlator/xserver/errors/XRequestError.java": "package com.winlator.xserver.errors;public class XRequestError extends Exception{public final int code,data;public XRequestError(int c,int d){code=c;data=d;}}",
    "com/winlator/xserver/errors/BadWindow.java": "package com.winlator.xserver.errors;public final class BadWindow extends XRequestError{public BadWindow(int id){super(3,id);}}",
    "com/winlator/xserver/errors/BadMatch.java": "package com.winlator.xserver.errors;public final class BadMatch extends XRequestError{public BadMatch(){super(8,0);}}",
    "com/winlator/xserver/errors/BadImplementation.java": "package com.winlator.xserver.errors;public final class BadImplementation extends XRequestError{public BadImplementation(){super(17,0);}}",
    "com/winlator/xserver/extensions/SyncExtension.java": """package com.winlator.xserver.extensions;
import java.util.*;import com.winlator.testing.Guard;
public final class SyncExtension {public final List<Integer> fences=new ArrayList<>();public void setTriggered(int id){fences.add(id);Guard.trace.add("fence:"+id);}}
""",
    "com/winlator/xserver/events/PresentCompleteNotify.java": """package com.winlator.xserver.events;
import com.winlator.xserver.*;import com.winlator.xserver.extensions.PresentExtension;
public final class PresentCompleteNotify {public static final int PRESENT_COMPLETE=1;public final int serial,window,eventId;public final long ust,msc;
 public PresentCompleteNotify(PresentExtension p,int id,Window w,int s,PresentExtension.CompleteKind k,PresentExtension.CompleteMode m,long ust,long msc){
  if(k!=PresentExtension.CompleteKind.PIXMAP||m!=PresentExtension.CompleteMode.COPY)throw new AssertionError("complete kind/mode changed");
  serial=s;window=w.id;eventId=id;this.ust=ust;this.msc=msc;}}
""",
    "com/winlator/xserver/events/PresentIdleNotify.java": """package com.winlator.xserver.events;
import com.winlator.xserver.*;import com.winlator.xserver.extensions.PresentExtension;
public final class PresentIdleNotify {public static final int PRESENT_IDLE=2;public final int serial,window,pixmap,eventId,fence;
 public PresentIdleNotify(PresentExtension p,int id,Window w,Pixmap pix,int s,int fence){serial=s;window=w.id;pixmap=pix.id;eventId=id;this.fence=fence;}}
""",
    "com/winlator/xserver/events/PresentConfigureNotify.java": """package com.winlator.xserver.events;
import com.winlator.xserver.*;import com.winlator.xserver.extensions.PresentExtension;
public final class PresentConfigureNotify {public static final int PRESENT_CONFIGURE=0;public final int flags;public PresentConfigureNotify(PresentExtension p,int id,Window w,int flags){this.flags=flags;}}
""",
}

FIXTURE = r"""
package com.winlator.xserver.extensions;
import java.nio.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;
import com.winlator.core.*;import com.winlator.renderer.*;import com.winlator.xconnector.*;
import com.winlator.xserver.*;import com.winlator.xserver.errors.*;import com.winlator.xserver.events.*;
import com.winlator.testing.Guard;import org.json.JSONObject;

public final class PresentHandlerCheck {
 static int scenarios,checks;
 static void check(boolean value,String message){checks++;Guard.check(value,message);}
 static void eq(long expected,long actual,String message){check(expected==actual,message+": expected="+expected+" actual="+actual);}
 static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
 static void field(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
 static final class Time implements PresentFramePacer.Clock,PresentFramePacer.Sleeper {
  long now=1_000_000_000L;final List<Long> delays=new ArrayList<>();Runnable onWait;
  public long nanoTime(){return now;}
  public void sleep(long nanos){Guard.unlocked();check(nanos>0,"nonpositive wait");delays.add(nanos);Guard.trace.add("wait");
   Runnable action=onWait;onWait=null;if(action!=null)action.run();now+=nanos;
  }
 }
 static final class Future extends FutureTask<Object> implements ScheduledFuture<Object>{
  final long delay;Future(Runnable task,long delay){super(task,null);this.delay=delay;}
  public long getDelay(TimeUnit unit){return unit.convert(delay,TimeUnit.NANOSECONDS);}
  public int compareTo(Delayed other){return Long.compare(delay,other.getDelay(TimeUnit.NANOSECONDS));}
 }
 static final class Scheduler extends AbstractExecutorService implements ScheduledExecutorService {
  final List<Future> tasks=new ArrayList<>();boolean closed;int next;
  public ScheduledFuture<?> schedule(Runnable task,long delay,TimeUnit unit){check(delay>=0,"negative legacy idle delay");Future f=new Future(task,unit.toNanos(delay));tasks.add(f);return f;}
  public <T> ScheduledFuture<T> schedule(Callable<T> c,long d,TimeUnit u){throw new AssertionError("unexpected callable task");}
  public ScheduledFuture<?> scheduleAtFixedRate(Runnable r,long i,long p,TimeUnit u){throw new AssertionError("unexpected repeating task");}
  public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r,long i,long p,TimeUnit u){throw new AssertionError("unexpected repeating task");}
  public void execute(Runnable r){r.run();}public void shutdown(){closed=true;}public List<Runnable> shutdownNow(){closed=true;return List.of();}
  public boolean isShutdown(){return closed;}public boolean isTerminated(){return closed;}public boolean awaitTermination(long t,TimeUnit u){return closed;}
  void deliverNext(Time time){Guard.unlocked();Future task=tasks.get(next++);time.now+=task.delay;task.run();}
 }
 static final class Fixture {
  final XServer server=new XServer();final XClient client=new XClient();final XOutputStream output=new XOutputStream();
  final Time time=new Time();final PresentFramePacer pacer=new PresentFramePacer(time,time);final PresentExtension extension;
  final Drawable content,source;final Window window;final Pixmap pixmap;
  Fixture(int fps,boolean configure)throws Exception{
   Guard.server=server;Guard.output=output;Guard.renderLocks.clear();Guard.trace.clear();Guard.copyTimes.clear();Guard.clock=time::nanoTime;
   content=new Drawable(11,24);source=new Drawable(22,24);window=new Window(1,content);pixmap=new Pixmap(2,source);
   server.windowManager.windows.put(1,window);server.pixmapManager.pixmaps.put(2,pixmap);
   extension=new PresentExtension(server,(byte)100,pacer);Guard.pacer=pacer;Guard.events=field(extension,"events");
   if(configure)extension.configureFramePacing(fps);
   select(7);Guard.trace.clear();
  }
  void request(int opcode,byte[] data,boolean unlocked)throws Exception{client.opcode=opcode;client.input=new XInputStream(data,unlocked);extension.handleRequest(client,client.input,output);eq(0,client.input.remaining(),"request not fully consumed");check(!server.held(),"handler leaked manager lock");}
  void select(int mask)throws Exception{request(3,ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(91).putInt(1).putInt(mask).array(),false);}
  void present(int serial,long target)throws Exception{present(1,2,serial,target,5);}
  void present(int windowId,int pixmapId,int serial,long target,int fence)throws Exception{
   ByteBuffer b=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
   b.putInt(windowId).putInt(pixmapId).putInt(serial).putLong(0).putShort((short)-6).putShort((short)11)
    .putLong(0).putInt(fence).putLong(0).putLong(target);
   request(1,b.array(),true);
  }
  JSONObject stats(){return extension.performanceSnapshot();}
  long count(Class<?> type){return client.sent.stream().filter(type::isInstance).count();}
  void noScheduler()throws Exception{check(field(extension,"idleNotifyScheduler")==null,"capped/zero target path scheduled delayed idle");}
 }
 static void cappedZeroTargets()throws Exception{
  Fixture f=new Fixture(60,true);f.present(17,0);f.present(18,0);f.present(19,0);
  eq(2,f.time.delays.size(),"zero-target Presents bypassed 60FPS pacing");eq(16_666_667L,f.time.delays.get(0),"60FPS interval");
  eq(3,f.content.copies,"copies missing");eq(-6,f.content.lastX,"x offset");eq(11,f.content.lastY,"y offset");
  eq(3,f.count(PresentCompleteNotify.class),"complete count");eq(3,f.count(PresentIdleNotify.class),"idle count");
  check(Guard.trace.equals(List.of("complete:17","copy:22","fence:5","idle:17","wait","complete:18","copy:22","fence:5","idle:18","wait","complete:19","copy:22","fence:5","idle:19")),"wait/copy/fence/event ordering changed: "+Guard.trace);
  for(Object event:f.client.sent)if(event instanceof PresentIdleNotify e){eq(91,e.eventId,"idle subscription ID");eq(1,e.window,"idle window");eq(2,e.pixmap,"idle pixmap");eq(5,e.fence,"idle fence");}
  JSONObject s=f.stats();eq(3,s.getLong("presentRequests"),"present coverage");eq(3,s.getLong("imageCopies"),"copy coverage");
  eq(3,s.getLong("cpuImageCopies"),"CPU copy coverage");eq(0,s.getLong("gpuImageCopies"),"CPU path counted GPU copies");
  eq(0,s.getLong("legacyIdleScheduled"),"zero-target legacy schedule coverage");
  eq(3,s.getLong("zeroTargetRequests"),"zero target coverage");eq(2,s.getLong("waits"),"wait coverage");f.noScheduler();scenarios++;
 }
 static void cappedPositiveTargets()throws Exception{
  Fixture f=new Fixture(30,true);f.present(1,100);f.present(2,101);
  eq(1,f.time.delays.size(),"positive target was not paced");eq(33_333_334L,f.time.delays.get(0),"30FPS interval");
  eq(2,f.server.sync.fences.size(),"copied buffers not released immediately");eq(2,f.count(PresentIdleNotify.class),"positive idle missing");f.noScheduler();
  eq(2,f.stats().getLong("cpuImageCopies"),"positive CPU copy coverage");eq(0,f.stats().getLong("gpuImageCopies"),"positive CPU counted GPU copies");
  eq(0,f.stats().getLong("legacyIdleScheduled"),"positive CPU path scheduled delayed idle");
  eq(0,f.stats().getLong("zeroTargetRequests"),"positive target counted as zero");scenarios++;
 }
 static void gpuPositiveTargetsKeepLegacyReleaseAndWaitOnlyTheRemainder()throws Exception{
  for(int fps:new int[]{60,30}){
   Fixture f=new Fixture(fps,true);f.source.texture=new GPUImage();Scheduler scheduler=new Scheduler();field(f.extension,"idleNotifyScheduler",scheduler);
   long interval=(1_000_000_000L+fps-1)/fps;
   f.present(31,1);eq(0,f.time.delays.size(),"first GPU frame waited");eq(1,scheduler.tasks.size(),"GPU positive frame lost legacy idle schedule");
   eq(0,f.server.sync.fences.size(),"GPU fence released before legacy idle");eq(0,f.count(PresentIdleNotify.class),"GPU idle released before legacy delay");
   eq(16_666_666L,scheduler.tasks.get(0).delay,"first legacy GPU delay changed");long firstCopy=Guard.copyTimes.get(0);
   // Legacy scheduling intentionally still uses System.nanoTime. The injected
   // pacer clock advances by the first exact legacy interval, modelling a client
   // that waits for Idle before its next request. Two frames avoid inventing a
   // shared fake clock for later legacy wall-clock deadlines. Production uses
   // System.nanoTime for both, so real idle time counts toward the producer cap.
   scheduler.deliverNext(f.time);eq(1,f.server.sync.fences.size(),"scheduled GPU fence missing");eq(1,f.count(PresentIdleNotify.class),"scheduled GPU idle missing");
   f.present(32,2);eq(1,f.time.delays.size(),"second GPU frame did not pace remaining interval");
   eq(interval-16_666_666L,f.time.delays.get(0),"producer blindly added another full interval after GPU idle");
   eq(interval,Guard.copyTimes.get(1)-firstCopy,"synchronous GPU copy cadence escaped selected upper bound");
   eq(2,scheduler.tasks.size(),"second GPU frame lost legacy idle schedule");eq(1,f.count(PresentIdleNotify.class),"second GPU frame bypassed legacy release");
   eq(2,f.stats().getLong("imageCopies"),"GPU total-copy coverage");eq(0,f.stats().getLong("cpuImageCopies"),"GPU path counted CPU copies");
   eq(2,f.stats().getLong("gpuImageCopies"),"GPU path coverage");eq(2,f.stats().getLong("legacyIdleScheduled"),"GPU legacy schedule coverage");
   scheduler.deliverNext(f.time);eq(2,f.count(PresentIdleNotify.class),"second scheduled GPU idle missing");eq(2,f.server.sync.fences.size(),"second GPU fence missing");scenarios++;
  }
 }
 static void legacyPaths()throws Exception{
  for(boolean configure:new boolean[]{false,true}){
   Fixture f=new Fixture(0,configure);f.present(1,0);f.present(2,0);eq(0,f.time.delays.size(),"uncapped zero target waited");f.noScheduler();
   Scheduler scheduler=new Scheduler();field(f.extension,"idleNotifyScheduler",scheduler);f.present(3,1);
   eq(1,scheduler.tasks.size(),"legacy positive target no longer scheduled");eq(2,f.count(PresentIdleNotify.class),"legacy idle sent too early");
   scheduler.tasks.get(0).run();eq(3,f.count(PresentIdleNotify.class),"legacy scheduled idle missing");
   eq(1,f.stats().getLong("legacyIdleScheduled"),"legacy CPU schedule coverage");
   check(f.stats().getBoolean("configured")==configure,"configured scope changed");scenarios++;
  }
 }
 static void nullAndInvalidResources()throws Exception{
  Fixture f=new Fixture(60,true);f.present(1,0);f.time.now+=8_000_000L;f.present(1,999,2,0,123);
  eq(0,f.time.delays.size(),"null pixmap request was throttled");eq(1,f.content.updates,"null pixmap lost forceUpdate");
  eq(1,f.server.sync.fences.size(),"null pixmap unexpectedly signaled a fence");eq(2,f.count(PresentCompleteNotify.class),"null pixmap lost complete event");
  f.present(3,0);eq(8_666_667L,f.time.delays.get(0),"null pixmap changed frame history");eq(1,f.stats().getLong("nullPixmapRequests"),"null path coverage");
  int delays=f.time.delays.size(),copies=f.content.copies;
  try{f.present(77,2,4,0,5);throw new AssertionError("missing window accepted");}catch(BadWindow expected){eq(77,expected.data,"BadWindow ID");}
  f.source.visual.depth=32;
  try{f.present(5,0);throw new AssertionError("mismatching depth accepted");}catch(BadMatch expected){}
  eq(delays,f.time.delays.size(),"invalid request waited before validation");eq(copies,f.content.copies,"invalid request copied");scenarios++;
 }
 static void resourcesAreRevalidatedAfterWait()throws Exception{
  Fixture destroyed=new Fixture(60,true);destroyed.present(1,0);destroyed.time.onWait=()->destroyed.server.windowManager.destroy(1);
  try{destroyed.present(2,0);throw new AssertionError("destroyed window copied");}catch(BadWindow expected){}
  eq(1,destroyed.content.copies,"destroyed window received stale copy");eq(1,destroyed.server.sync.fences.size(),"destroyed frame fence released");
  eq(0,destroyed.stats().getInt("trackedWindows"),"freed pacing history retained");
  Fixture replaced=new Fixture(60,true);replaced.present(1,0);
  replaced.time.onWait=()->replaced.server.pixmapManager.pixmaps.put(2,new Pixmap(2,new Drawable(33,32)));
  try{replaced.present(2,0);throw new AssertionError("stale pixmap used after replacement");}catch(BadMatch expected){}
  eq(1,replaced.content.copies,"replaced pixmap copied before revalidation");
  Fixture reused=new Fixture(60,true);reused.present(1,0);reused.time.onWait=()->reused.server.windowManager.windows.put(1,new Window(1,new Drawable(44,24)));
  try{reused.present(2,0);throw new AssertionError("reused window ID accepted stale request");}catch(BadWindow expected){}
  eq(1,reused.content.copies,"reused window ID copied stale frame");scenarios++;
 }
 static void locksAllowInputAndGeometryReset()throws Exception{
  Fixture f=new Fixture(60,true);f.present(1,0);
  f.time.onWait=()->{
   Guard.unlocked();try(XLock lock=f.server.lock(XServer.Lockable.WINDOW_MANAGER,XServer.Lockable.PIXMAP_MANAGER)){
    synchronized(f.content.renderLock){Guard.trace.add("independent-input");}
   }
  };
  f.present(2,0);check(Guard.trace.contains("independent-input"),"input callback did not run while producer waited");
  f.extension.onUpdateWindowGeometry(f.window,true);int waits=f.time.delays.size();f.present(3,0);
  eq(waits,f.time.delays.size(),"resize retained obsolete deadline");eq(1,f.count(PresentConfigureNotify.class),"resize configure missing");scenarios++;
 }
 static void gpuCopyAndQueryVersion()throws Exception{
  Fixture f=new Fixture(60,true);f.source.texture=new GPUImage();int created=GPUHelper.created,destroyed=GPUHelper.destroyed;
  f.present(7,0);f.present(8,0);eq(1,GPUHelper.created-created,"copy context not reused");eq(2,f.content.texture.copies,"GPU copy count");
  eq(2,f.content.updates,"GPU copy forceUpdate");eq(1,f.client.destroys.size(),"context teardown listener duplicated");
  check(Guard.trace.equals(List.of("complete:7","data","gpu-copy","update","fence:5","idle:7","wait","complete:8","data","gpu-copy","update","fence:5","idle:8")),"GPU copy/fence ordering changed");
  eq(2,f.stats().getLong("gpuImageCopies"),"zero-target GPU coverage");eq(0,f.stats().getLong("legacyIdleScheduled"),"zero-target GPU behavior changed");f.noScheduler();
  f.client.destroys.get(0).accept(f.client);eq(1,GPUHelper.destroyed-destroyed,"EGL context teardown lost");
  f.request(0,new byte[8],false);byte[] reply=f.output.bytes();eq(32,reply.length,"query reply length");eq(1,reply[0],"query status");
  ByteBuffer b=ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN);eq(0x105,b.getShort(2),"query sequence");eq(1,b.getInt(8),"query major");eq(0,b.getInt(12),"query minor");
  f.select(0);int events=f.client.sent.size();f.present(9,0);eq(events,f.client.sent.size(),"removed event subscription still notified");scenarios++;
 }
 public static void main(String[] args)throws Exception{
  cappedZeroTargets();cappedPositiveTargets();gpuPositiveTargetsKeepLegacyReleaseAndWaitOnlyTheRemainder();legacyPaths();nullAndInvalidResources();resourcesAreRevalidatedAfterWait();locksAllowInputAndGeometryReset();gpuCopyAndQueryVersion();
  System.out.println("PASS: "+scenarios+" production Present handler scenarios, "+checks+" checks; paced/legacy/null/error/resource-reuse/GPU/event/fence paths and unlocked waits");
 }
}
"""


def java_tool(name):
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / name)
    found = shutil.which(name)
    if found:
        candidates.append(Path(found))
    candidates.append(ROOT.parent / "tools" / "bin" / name)
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    raise RuntimeError(f"JDK tool unavailable: {name}")


def main():
    production = ROOT / "app/src/main/java"
    sources = [production / path for path in (
        "com/winlator/xserver/extensions/PresentExtension.java",
        "com/winlator/xserver/extensions/PresentFramePacer.java",
        "com/winlator/xserver/extensions/Extension.java",
        "com/winlator/xserver/XLock.java",
        "com/winlator/core/Bitmask.java",
    )]
    with tempfile.TemporaryDirectory(prefix="pd2-present-handler-") as temporary:
        directory = Path(temporary)
        for name, content in STUBS.items():
            target = directory / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)
            sources.append(target)
        fixture = directory / "com/winlator/xserver/extensions/PresentHandlerCheck.java"
        fixture.write_text(FIXTURE)
        sources.append(fixture)
        subprocess.run([java_tool("javac"), "--release", "17", "-d", str(directory),
                        *map(str, sources)], check=True, timeout=60)
        subprocess.run([java_tool("java"), "-cp", str(directory),
                        "com.winlator.xserver.extensions.PresentHandlerCheck"], check=True, timeout=30)


if __name__ == "__main__":
    main()
