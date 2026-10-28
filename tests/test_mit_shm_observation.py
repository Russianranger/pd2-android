#!/usr/bin/env python3
"""Observe the actual MIT-SHM handler without changing its existing pixel ownership.

The actual handler, Drawable, extension base and counters are compiled against
small Android/X-transport/Texture stand-ins. An empty native library permits
Drawable class initialization; no JNI pixel-copy implementation is exercised.
The borrowed-buffer mutation assertions deliberately document a known latent
bug retained by this observation-only change, not desired future semantics.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

STUBS = {'android/graphics/Bitmap.java': 'package android.graphics; public class Bitmap { public int '
                                 'getWidth(){return 2;} public int getHeight(){return 2;} }',
 'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
 'com/winlator/core/Callback.java': 'package com.winlator.core; public interface Callback<T>{ void '
                                    'call(T v); }',
 'com/winlator/math/Mathf.java': 'package com.winlator.math; public class Mathf { public static '
                                 'int clamp(int v,int a,int b){return Math.max(a,Math.min(v,b));} '
                                 '}',
 'com/winlator/renderer/GPUImage.java': 'package com.winlator.renderer; import java.nio.*; public '
                                        'class GPUImage extends Texture { public '
                                        'GPUImage(){super(null);} public short getStride(){return '
                                        '2;} public ByteBuffer getVirtualData(){return null;} }',
 'com/winlator/renderer/Texture.java': 'package com.winlator.renderer; import '
                                       'com.winlator.xserver.Drawable; public class Texture { '
                                       'public Texture(Drawable d){} public void '
                                       'setNeedsUpdate(boolean v){} }',
 'com/winlator/xconnector/XInputStream.java': 'package com.winlator.xconnector;import '
                                              'java.nio.*;public class XInputStream{final '
                                              'ByteBuffer b;public XInputStream(byte[] '
                                              'v){b=ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);}public '
                                              'void skip(int n){b.position(b.position()+n);}public '
                                              'int readInt(){return b.getInt();}public short '
                                              'readShort(){return b.getShort();}public byte '
                                              'readByte(){return b.get();}}',
 'com/winlator/xconnector/XOutputStream.java': 'package com.winlator.xconnector;public class '
                                               'XOutputStream{public int writes;public XStreamLock '
                                               'lock(){return ()->{};}public void writeByte(byte '
                                               'v){writes++;}public void writeShort(short '
                                               'v){writes++;}public void writeInt(int '
                                               'v){writes++;}}',
 'com/winlator/xconnector/XStreamLock.java': 'package com.winlator.xconnector;public interface '
                                             'XStreamLock extends AutoCloseable{void close();}',
 'com/winlator/xserver/GraphicsContext.java': 'package com.winlator.xserver; public class '
                                              'GraphicsContext { public enum Function{COPY} public '
                                              'Function getFunction(){return Function.COPY;} }',
 'com/winlator/xserver/XClient.java': 'package com.winlator.xserver; public class XClient { public '
                                      'byte getRequestData(){return 3;} public short '
                                      'getSequenceNumber(){return 1;} }',
 'com/winlator/xserver/XClientRequestHandler.java': 'package com.winlator.xserver; public class '
                                                    'XClientRequestHandler { public static byte '
                                                    'RESPONSE_CODE_SUCCESS=1; }',
 'com/winlator/xserver/XLock.java': 'package com.winlator.xserver; public interface XLock extends '
                                    'AutoCloseable { void close(); }',
 'com/winlator/xserver/XServer.java': 'package com.winlator.xserver; import java.util.*;import '
                                      'java.nio.*;public class XServer {\n'
                                      ' public enum Lockable '
                                      '{SHMSEGMENT_MANAGER,DRAWABLE_MANAGER,GRAPHIC_CONTEXT_MANAGER}\n'
                                      ' public final DM drawableManager=new DM();public final GM '
                                      'graphicsContextManager=new GM();public final SM shm=new '
                                      'SM();\n'
                                      ' public XLock lock(Lockable...v){return ()->{};}public SM '
                                      'getSHMSegmentManager(){return shm;}\n'
                                      ' public static class DM { public final '
                                      'Map<Integer,Drawable> d=new HashMap<>();public Drawable '
                                      'getDrawable(int id){return d.get(id);} }\n'
                                      ' public static class GM { public boolean present=true; '
                                      'public GraphicsContext getGraphicsContext(int id){return '
                                      'present?new GraphicsContext():null;} }\n'
                                      ' public static class SM {public ByteBuffer data;public void '
                                      'attach(int a,int b){} public void detach(int id){} public '
                                      'ByteBuffer getData(int id){return data;}}\n'
                                      '}',
 'com/winlator/xserver/errors/BadDrawable.java': 'package com.winlator.xserver.errors;public class '
                                                 'BadDrawable extends XRequestError{public '
                                                 'BadDrawable(int v){}}',
 'com/winlator/xserver/errors/BadGraphicsContext.java': 'package '
                                                        'com.winlator.xserver.errors;public class '
                                                        'BadGraphicsContext extends '
                                                        'XRequestError{public '
                                                        'BadGraphicsContext(int v){}}',
 'com/winlator/xserver/errors/BadImplementation.java': 'package com.winlator.xserver.errors;public '
                                                       'class BadImplementation extends '
                                                       'XRequestError{}',
 'com/winlator/xserver/errors/BadSHMSegment.java': 'package com.winlator.xserver.errors;public '
                                                   'class BadSHMSegment extends '
                                                   'XRequestError{public BadSHMSegment(int v){}}',
 'com/winlator/xserver/errors/XRequestError.java': 'package com.winlator.xserver.errors;public '
                                                   'class XRequestError extends Exception{public '
                                                   'XRequestError(){}}',
 'org/json/JSONException.java': 'package org.json; public class JSONException extends Exception { '
                                'public JSONException(String m){super(m);} }',
 'org/json/JSONObject.java': 'package org.json; import java.util.*; public class JSONObject { '
                             'private final Map<String,Object> v=new HashMap<>(); public '
                             'JSONObject put(String k,Object x)throws '
                             'JSONException{v.put(k,x);return this;} public long getLong(String '
                             'k)throws JSONException{return ((Number)v.get(k)).longValue();} '
                             'public String getString(String k)throws JSONException{return '
                             '(String)v.get(k);} }'}

FIXTURE = r"""
package com.winlator.xserver.extensions;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import com.winlator.xserver.*;
import com.winlator.xconnector.*;
import org.json.*;

public final class MitShmObservationFixture {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void eq(long expected,long actual,String message){check(expected==actual,message+": expected="+expected+", actual="+actual);}
 static Drawable drawable(boolean borrowed){Drawable d=new Drawable(1,2,2,new Visual(1,true,32,8,0,0,0));d.setUseSharedData(borrowed);return d;}
 static ByteBuffer pixels(){ByteBuffer b=ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<4;i++)b.putInt(i*4,i+1);return b;}
 static byte[] packet(int offset,int sx,int sw,int dx,int depth){return ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(2).putShort((short)2).putShort((short)2).putShort((short)sx).putShort((short)0).putShort((short)sw).putShort((short)2).putShort((short)dx).putShort((short)0).put((byte)depth).put((byte)2).put((byte)0).put((byte)0).putInt(3).putInt(offset).array();}
 static void put(XServer x,MITSHMExtension handler,Drawable d,ByteBuffer source,int offset,int sx,int sw,int dx,int depth)throws Exception{
  x.drawableManager.d.put(1,d);x.shm.data=source;XOutputStream output=new XOutputStream();
  handler.handleRequest(new XClient(),new XInputStream(packet(offset,sx,sw,dx,depth)),output);
  eq(0,output.writes,"observation must not add protocol replies/completion events");
 }
 public static void main(String[]args)throws Exception{
  XServer x=new XServer();MITSHMExtension handler=new MITSHMExtension(x,(byte)-1);
  ByteBuffer shared=pixels();Drawable d=drawable(true);put(x,handler,d,shared,0,0,2,0,32);
  check(d.getData()==shared,"keep the existing borrowed-buffer behavior");eq(1,d.getData().getInt(0),"initial shared pixel");
  shared.putInt(0,99);eq(99,d.getData().getInt(0),"known alias: guest mutation after request return remains visible");
  JSONObject report=handler.performanceSnapshot();eq(1,report.getLong("requests"),"first request");eq(1,report.getLong("sharedBorrowed"),"borrowed branch");eq(0,report.getLong("copied"),"no copy branch");eq(16,report.getLong("logicalBytes"),"four-byte logical pixels");
  ByteBuffer offset=ByteBuffer.allocateDirect(20).order(ByteOrder.LITTLE_ENDIAN);offset.putInt(0,0xdeadbeef);for(int i=0;i<4;i++)offset.putInt(4+i*4,11+i);
  d=drawable(true);put(x,handler,d,offset,4,0,2,0,32);eq(0xdeadbeef,d.getData().getInt(0),"keep existing ignored-offset semantics; observation is not a fix");
  shared=pixels();d=drawable(true);put(x,handler,d,shared,0,1,1,0,32);eq(1,d.getData().getInt(0),"keep existing ignored source subrectangle");
  put(x,handler,drawable(true),pixels(),0,0,2,1,24);
  put(x,handler,drawable(true),pixels(),0,0,2,0,1);
  d=drawable(false);d.setData(null);put(x,handler,d,pixels(),0,0,2,0,32);
  check(d.getData()==null,"copy-branch observation does not claim a native copy completed");
  report=handler.performanceSnapshot();eq(6,report.getLong("requests"),"accepted request count");eq(5,report.getLong("sharedBorrowed"),"borrowed branch requests");eq(1,report.getLong("copied"),"copy branch requests");eq(1,report.getLong("nonzeroOffset"),"nonzero offset counted without application");eq(2,report.getLong("subrect"),"source and destination partial coverage counted");eq(72,report.getLong("logicalBytes"),"known requested geometry bytes");eq(1,report.getLong("unknownByteEstimates"),"unsupported depth estimate omitted");check(report.getString("scope").contains("Not FPS"),"scope must not call request counts FPS");
  x.shm.data=null;
  try{handler.handleRequest(new XClient(),new XInputStream(packet(0,0,2,0,32)),new XOutputStream());throw new AssertionError("missing SHM should fail");}catch(com.winlator.xserver.errors.BadSHMSegment expected){}
  x.shm.data=pixels();x.graphicsContextManager.present=false;
  try{handler.handleRequest(new XClient(),new XInputStream(packet(0,0,2,0,32)),new XOutputStream());throw new AssertionError("missing GC should fail");}catch(com.winlator.xserver.errors.BadGraphicsContext expected){}
  x.graphicsContextManager.present=true;x.drawableManager.d.clear();
  try{handler.handleRequest(new XClient(),new XInputStream(packet(0,0,2,0,32)),new XOutputStream());throw new AssertionError("missing drawable should fail");}catch(com.winlator.xserver.errors.BadDrawable expected){}
  eq(6,handler.performanceSnapshot().getLong("requests"),"failed resource validation is not observed as an accepted request");
  eq(16,ShmPutImageStats.estimateBytes(2,2,24),"depth24 storage also uses four bytes");eq(0,ShmPutImageStats.estimateBytes(0,Long.MAX_VALUE,32),"empty geometry");eq(-1,ShmPutImageStats.estimateBytes(-1,2,32),"negative geometry unknown");eq(-1,ShmPutImageStats.estimateBytes(Long.MAX_VALUE,2,32),"pixel multiplication overflow");eq(-1,ShmPutImageStats.estimateBytes(Long.MAX_VALUE/4+1,1,32),"byte multiplication overflow");
  ShmPutImageStats stats=new ShmPutImageStats();stats.record(true,0,false,Long.MAX_VALUE);stats.record(false,0,false,1);eq(Long.MAX_VALUE,stats.snapshot().logicalBytes,"aggregate byte counter saturates");eq(Long.MAX_VALUE,ShmPutImageStats.add(Long.MAX_VALUE,1),"request counters saturate");
  final ShmPutImageStats concurrent=new ShmPutImageStats();List<Thread> threads=new ArrayList<>();
  for(int n=0;n<8;n++){Thread thread=new Thread(()->{for(int i=0;i<1000;i++)concurrent.record(true,0,false,16);});threads.add(thread);thread.start();}
  for(Thread thread:threads){thread.join(3000);check(!thread.isAlive(),"counter worker ended");}
  ShmPutImageStats.Snapshot snapshot=concurrent.snapshot();eq(8000,snapshot.requests,"concurrent requests");eq(8000,snapshot.sharedBorrowed,"concurrent branch counts");eq(128000,snapshot.logicalBytes,"concurrent logical bytes");
  System.out.println("MIT-SHM observation: "+checks+" checks passed; actual handler and Drawable, transport/GL stand-ins; no JNI pixel-copy or rendering test.");
 }
}
"""

class MitShmObservationTest(unittest.TestCase):
    def test_actual_handler_keeps_pixels_and_reports_bounded_request_observations(self):
        if not shutil.which("java") or not shutil.which("gcc"):
            self.skipTest("JDK17 and host GCC are required")
        with tempfile.TemporaryDirectory(prefix="pd2-mitshm-") as temporary:
            out = Path(temporary)
            source, classes = out / "src", out / "classes"
            classes.mkdir()
            files = dict(STUBS)
            for name in ["xserver/Drawable.java", "xserver/XResource.java", "xserver/Visual.java",
                         "xserver/extensions/Extension.java", "xserver/extensions/MITSHMExtension.java",
                         "xserver/extensions/ShmPutImageStats.java"]:
                files["com/winlator/" + name] = (ROOT / "app/src/main/java/com/winlator" / name).read_text()
            files["com/winlator/xserver/extensions/MitShmObservationFixture.java"] = FIXTURE
            for name, content in files.items():
                path = source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            (out / "empty.c").write_text("void bootstrap(void){}\n")
            subprocess.run(["gcc", "-shared", "-fPIC", str(out / "empty.c"), "-o", str(out / "libwinlator.so")], check=True)
            compiler = [shutil.which("javac")] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
            subprocess.run(compiler + ["-encoding", "UTF-8", "-d", str(classes), *map(str, source.rglob("*.java"))], check=True)
            subprocess.run(["java", "-Djava.library.path=" + str(out), "-cp", str(classes),
                            "com.winlator.xserver.extensions.MitShmObservationFixture"], check=True, timeout=15)

if __name__ == "__main__":
    unittest.main(verbosity=2)
