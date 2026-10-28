#!/usr/bin/env python3
"""Run the production Java ALSA bridge against controlled AudioTrack/socket stubs.

This verifies protocol/lifecycle/accounting, not hardware sound quality or ALSA
rate conversion. No PCM content is retained by production diagnostics.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "android/content/SharedPreferences.java": "package android.content; public interface SharedPreferences {int getInt(String k,int d); boolean getBoolean(String k,boolean d);}",
    "android/content/Context.java": "package android.content; public class Context {public static final String AUDIO_SERVICE=\"audio\"; public Object getSystemService(String key){return new android.media.AudioManager();}}",
    "android/media/AudioManager.java": "package android.media; public class AudioManager {public static final String PROPERTY_OUTPUT_FRAMES_PER_BUFFER=\"burst\"; public static String burst=\"256\"; public String getProperty(String k){return burst;}}",
    "android/media/AudioFormat.java": """package android.media;
public class AudioFormat {
 public static final int ENCODING_PCM_8BIT=3,ENCODING_PCM_16BIT=2,ENCODING_PCM_FLOAT=4,ENCODING_DEFAULT=1,CHANNEL_OUT_MONO=4,CHANNEL_OUT_STEREO=12;
 public int rate; public static class Builder {private final AudioFormat f=new AudioFormat();public Builder setEncoding(int v){return this;}public Builder setSampleRate(int v){f.rate=v;return this;}public Builder setChannelMask(int v){return this;}public AudioFormat build(){return f;}}
} """,
    "android/media/AudioTrack.java": """package android.media;
import java.nio.*;import java.util.concurrent.*;
public class AudioTrack {
 public static final int PERFORMANCE_MODE_NONE=0,PERFORMANCE_MODE_LOW_LATENCY=1,PERFORMANCE_MODE_POWER_SAVING=2,PLAYSTATE_PLAYING=3,WRITE_BLOCKING=0;
 public static AudioTrack last; public static boolean block; public final CountDownLatch entered=new CountDownLatch(1),unpaused=new CountDownLatch(1);
 public int rate,bytes,plays,pauses; public boolean released;
 public static class Builder {private final AudioTrack t=new AudioTrack();public Builder setPerformanceMode(int v){return this;}public Builder setAudioFormat(AudioFormat f){t.rate=f.rate;return this;}public Builder setBufferSizeInBytes(int n){t.bytes=n;return this;}public AudioTrack build(){last=t;return t;}}
 public int write(ByteBuffer b,int n,int mode){entered.countDown();if(block){try{if(!unpaused.await(3,TimeUnit.SECONDS))throw new AssertionError("shutdown did not pause writer");}catch(InterruptedException e){throw new AssertionError(e);}return 0;}b.position(b.position()+n);return n;}
 public void play(){plays++;}public void pause(){pauses++;unpaused.countDown();}public void stop(){}public void flush(){}public void release(){released=true;}public void setVolume(float v){}
 public int getPlayState(){return PLAYSTATE_PLAYING;}public int getSampleRate(){return rate;}public int getBufferSizeInFrames(){return bytes/4;}public int getBufferCapacityInFrames(){return bytes/4;}public int getUnderrunCount(){return 0;}public int setBufferSizeInFrames(int n){return n;}
} """,
    "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}",
    "com/winlator/core/StringUtils.java": "package com.winlator.core; public final class StringUtils {public static String replace(String s,int a,int b,String v){return s.substring(0,a)+v+s.substring(b);}}",
    "com/winlator/contentdialog/AudioDriverConfigDialog.java": "package com.winlator.contentdialog; public class AudioDriverConfigDialog {public static final byte DEFAULT_LATENCY_MILLIS=16;public static final float DEFAULT_VOLUME=1f;}",
    "org/json/JSONException.java": "package org.json; public class JSONException extends Exception {}",
    "org/json/JSONObject.java": """package org.json;import java.util.*;public class JSONObject {
 final Map<String,Object> values=new LinkedHashMap<>();public JSONObject put(String k,Object v)throws JSONException{values.put(k,v);return this;}
 public long getLong(String k){return ((Number)values.get(k)).longValue();}public int getInt(String k){return ((Number)values.get(k)).intValue();}public boolean getBoolean(String k){return (Boolean)values.get(k);}public String getString(String k){return (String)values.get(k);}public JSONArray getJSONArray(String k){return (JSONArray)values.get(k);}public String toString(){return values.toString();}
} """,
    "org/json/JSONArray.java": "package org.json;import java.util.*;public class JSONArray {final List<Object> values=new ArrayList<>();public JSONArray put(Object v){values.add(v);return this;}public int length(){return values.size();}public JSONObject getJSONObject(int n){return (JSONObject)values.get(n);}}",
    "com/winlator/sysvshm/SysVSharedMemory.java": "package com.winlator.sysvshm;import java.nio.*;public class SysVSharedMemory {public static int unmapped;public static void unmapSHMSegment(ByteBuffer b,int n){unmapped++;}public static int createMemoryFd(String n,int size){return 5;}public static ByteBuffer mapSHMSegment(int fd,int n,int o,boolean r){return ByteBuffer.allocateDirect(n);}}",
    "com/winlator/xconnector/RequestHandler.java": "package com.winlator.xconnector;import java.io.*;public interface RequestHandler {boolean handleRequest(ConnectedClient c)throws IOException;}",
    "com/winlator/xconnector/ConnectionHandler.java": "package com.winlator.xconnector;public interface ConnectionHandler {void handleNewConnection(ConnectedClient c);void handleConnectionShutdown(ConnectedClient c);}",
    "com/winlator/xconnector/ConnectedClient.java": "package com.winlator.xconnector;public class ConnectedClient {private Object tag;public XInputStream input;public final XOutputStream output=new XOutputStream();public Object getTag(){return tag;}public void setTag(Object o){tag=o;}public XInputStream getInputStream(){return input;}public XOutputStream getOutputStream(){return output;}}",
    "com/winlator/xconnector/XInputStream.java": "package com.winlator.xconnector;import java.nio.*;public class XInputStream {final ByteBuffer b;public XInputStream(byte[] v){b=ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);}public int available(){return b.remaining();}public byte readByte(){return b.get();}public int readInt(){return b.getInt();}public ByteBuffer readByteBuffer(int n){ByteBuffer s=b.slice();s.limit(n);b.position(b.position()+n);return s;}}",
    "com/winlator/xconnector/XStreamLock.java": "package com.winlator.xconnector;public interface XStreamLock extends AutoCloseable {void close();}",
    "com/winlator/xconnector/XOutputStream.java": "package com.winlator.xconnector;import java.io.*;public class XOutputStream {public final ByteArrayOutputStream b=new ByteArrayOutputStream();public XStreamLock lock(){return ()->{};}public void writeInt(int v){for(int i=0;i<4;i++)b.write(v>>>(i*8));}public void writeByte(byte v){b.write(v);}public void setAncillaryFd(int fd){}}",
    "com/winlator/xconnector/UnixSocketConfig.java": "package com.winlator.xconnector;public class UnixSocketConfig {}",
    "com/winlator/xconnector/XConnectorEpoll.java": "package com.winlator.xconnector;public class XConnectorEpoll {public static XConnectorEpoll last;public static Runnable beforeDestroy;public final ConnectionHandler connections;public XConnectorEpoll(UnixSocketConfig c,ConnectionHandler h,RequestHandler r){connections=h;last=this;}public void setMultithreadedClients(boolean v){}public void start(){}public void destroy(){if(beforeDestroy!=null)beforeDestroy.run();}public static void closeFd(int fd){}}",
    "com/winlator/xenvironment/EnvironmentComponent.java": "package com.winlator.xenvironment;public abstract class EnvironmentComponent {protected XEnvironment environment;public abstract void start();public abstract void stop();}",
    "com/winlator/xenvironment/XEnvironment.java": "package com.winlator.xenvironment;import android.content.Context;public class XEnvironment {public Context getContext(){return new Context();}public void addComponent(EnvironmentComponent c){c.environment=this;}}",
}
FIXTURE = r'''package com.winlator.alsaserver;
import android.content.*;import android.media.*;import com.winlator.core.*;import com.winlator.pd2.*;import com.winlator.xconnector.*;import com.winlator.xenvironment.*;import com.winlator.xenvironment.components.*;import com.winlator.sysvshm.*;
import org.json.*;import java.nio.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.lang.reflect.*;import java.io.*;
public class AudioBridgeCheck {
 static int checks,scenarios;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void eq(long a,long b,String m){check(a==b,m+": "+a+" != "+b);}
 static long count(ALSAClient c,String k){return c.performanceSnapshot().getLong(k);}
 static final class Fake implements ALSAClient.Track {
  int[] results={};int next,plays,pauses,rate=44100,size=1000,capacity=1300,underruns,resizes,lastResize;boolean throwWrite,brokenProgress,released;
  CountDownLatch entered,unblock,sampleRead,returnSample;String stalled;List<Integer> lengths=new ArrayList<>();
  int sample(String kind,int value){CountDownLatch ready=null,done=null;synchronized(this){if(kind.equals(stalled)){stalled=null;ready=sampleRead;done=returnSample;}}if(ready!=null){ready.countDown();try{check(done.await(2,TimeUnit.SECONDS),"sample not released");}catch(InterruptedException e){throw new AssertionError(e);}}return value;}
  public int write(ByteBuffer b,int bytes){lengths.add(bytes);if(entered!=null){entered.countDown();try{check(unblock.await(2,TimeUnit.SECONDS),"writer not cancelled");}catch(InterruptedException e){throw new AssertionError(e);}return 0;}if(throwWrite)throw new IllegalStateException();int n=next<results.length?results[next++]:bytes;if(n>0&&!brokenProgress)b.position(b.position()+Math.min(n,bytes));return n;}
  public void play(){plays++;}public void pause(){pauses++;if(unblock!=null)unblock.countDown();}public void stop(){}public void flush(){}public void release(){released=true;}
  public int playState(){return AudioTrack.PLAYSTATE_PLAYING;}public int sampleRate(){return sample("rate",rate);}public int bufferSize(){return sample("size",size);}public int bufferCapacity(){return capacity;}public int underruns(){return sample("underruns",underruns);}public int resize(int n){resizes++;lastResize=n;size=n;return n;}
 }
 static ALSAClient make(Fake f){AtomicLong clock=new AtomicLong();ALSAClient c=new ALSAClient(new ALSAClient.Options(),(format,bytes,options)->f,()->clock.getAndAdd(10));c.setChannels(2);c.setDataType(ALSAClient.DataType.S16LE);c.setSampleRate(44100);c.setBufferSize(1024);c.prepare();return c;}
 static byte[] packet(int code,int len,byte[] data){return ByteBuffer.allocate(5+data.length).order(ByteOrder.LITTLE_ENDIAN).put((byte)code).putInt(len).put(data).array();}
 static byte[] prepare(int channels,int type,int rate,int frames){return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).put((byte)channels).put((byte)type).putInt(rate).putInt(frames).array();}
 static ConnectedClient connection(ALSAClient c,byte[] bytes){ConnectedClient cc=new ConnectedClient();cc.setTag(c);cc.input=new XInputStream(bytes);return cc;}
 static void invalid(ALSARequestHandler h,ALSAClient c,int code,int len,byte[] data)throws Exception {try{h.handleRequest(connection(c,packet(code,len,data)));throw new AssertionError("invalid request accepted");}catch(IOException expected){checks++;}}
 static final class Prefs implements SharedPreferences {Map<String,Object> v=new HashMap<>();public int getInt(String k,int d){return v.containsKey(k)?(Integer)v.get(k):d;}public boolean getBoolean(String k,boolean d){return v.containsKey(k)?(Boolean)v.get(k):d;}}
 public static void main(String[] ignored)throws Exception {
  scenarios++;Prefs p=new Prefs();eq(32,Pd2AudioPolicy.latencyMillis(p),"balanced default");check(!Pd2AudioPolicy.ecoMode(p),"eco must opt in");for(int n:new int[]{16,32,64}){p.v.put(Pd2AudioPolicy.PREFERENCE,n);eq(n,Pd2AudioPolicy.latencyMillis(p),"saved latency");eq(n,ALSAClient.Options.fromKeyValueSet(new KeyValueSet(Pd2AudioPolicy.configuration(p))).latencyMillis,"config round trip");}for(Object n:new Object[]{-1,0,65568,"32"}){p.v.put(Pd2AudioPolicy.PREFERENCE,n);eq(32,Pd2AudioPolicy.latencyMillis(p),"invalid policy");}p.v.put(Pd2AudioPolicy.ECO_PREFERENCE,true);check(Pd2AudioPolicy.ecoMode(p),"eco selection");p.v.put(Pd2AudioPolicy.ECO_PREFERENCE,"true");check(!Pd2AudioPolicy.ecoMode(p),"eco invalid type");
  scenarios++;ALSAClient.Options options=ALSAClient.Options.fromKeyValueSet(new KeyValueSet("latencyMillis=65568,volume=NaN,performanceMode=99"));eq(16,options.latencyMillis,"avoid short wrap");check(options.volume==1f,"finite volume");eq(1,options.performanceMode,"valid performance mode");eq(256,ALSAClient.Options.fromKeyValueSet(new KeyValueSet("latencyMillis=256")).latencyMillis,"upper latency bound");
  scenarios++;for(int rate:new int[]{8000,22050,44100,48000,192000})for(int ms:new int[]{16,32,64}){int bytes=ALSAClient.latencyMillisToBufferSize(ms,2,ALSAClient.DataType.S16LE,rate);check(bytes>0&&bytes%1024==0,"full burst");check((long)bytes*1000 >= (long)ms*rate*4,"latency never rounded down");}eq(0,ALSAClient.latencyMillisToBufferSize(Integer.MAX_VALUE,2,ALSAClient.DataType.FLOATLE,192000),"overflow latency rejected");eq(0,ALSAClient.latencyMillisToBufferSize(32,0,ALSAClient.DataType.U8,48000),"invalid channels");check(!ALSAClient.validBuffer(Integer.MAX_VALUE,2,ALSAClient.DataType.FLOATLE,48000),"buffer overflow rejected");check(ALSAClient.validBuffer(1025,2,ALSAClient.DataType.S16LE,44100),"frame count need not divide frame bytes");
  scenarios++;AudioManager.burst="-1";ALSAClient.assignFramesPerBuffer(new Context());eq(1024,ALSAClient.latencyMillisToBufferSize(16,2,ALSAClient.DataType.S16LE,8000),"negative burst fallback");AudioManager.burst="2147483647";ALSAClient.assignFramesPerBuffer(new Context());eq(1024,ALSAClient.latencyMillisToBufferSize(16,2,ALSAClient.DataType.S16LE,8000),"oversized burst fallback");AudioManager.burst="256";ALSAClient.assignFramesPerBuffer(new Context());
  scenarios++;Fake f=new Fake();f.results=new int[]{4,4,8};ALSAClient c=make(f);eq(1,f.plays,"prepare plays before write");ByteBuffer data=ByteBuffer.allocate(16);c.writeDataToTrack(data);eq(4,c.pointer(),"frame pointer");eq(3,count(c,"writeCalls"),"partial loop");eq(2,count(c,"shortWrites"),"short writes");eq(16,count(c,"writtenBytes"),"written bytes");eq(30,count(c,"writeNanos"),"write timing");eq(0,data.position(),"rewind");
  scenarios++;f=new Fake();f.results=new int[]{4,0,99};c=make(f);c.writeDataToTrack(ByteBuffer.allocate(16));eq(2,count(c,"writeCalls"),"zero exits");eq(1,count(c,"zeroWrites"),"zero metric");eq(1,c.pointer(),"only written frames");c.writeDataToTrack(ByteBuffer.allocate(4));eq(2,c.pointer(),"subsequent write recovers");
  scenarios++;f=new Fake();f.results=new int[]{4,-6};c=make(f);c.writeDataToTrack(ByteBuffer.allocate(16));eq(1,count(c,"writeErrors"),"negative error count");eq(-6,c.performanceSnapshot().getInt("lastWriteError"),"error code");eq(1,c.pointer(),"partial before error");
  scenarios++;f=new Fake();f.throwWrite=true;c=make(f);c.writeDataToTrack(ByteBuffer.allocate(4));eq(1,count(c,"writeExceptions"),"exception metric");eq(1,count(c,"writeErrors"),"exception error");eq(0,c.pointer(),"exception no false progress");
  scenarios++;f=new Fake();f.brokenProgress=true;c=make(f);c.writeDataToTrack(ByteBuffer.allocate(4));eq(1,count(c,"writeCalls"),"nonadvancing positive result exits");eq(1,count(c,"zeroWrites"),"nonadvancing metric");
  scenarios++;f=new Fake();c=make(f);f.underruns=40000;c.performanceSnapshot();c.writeDataToTrack(ByteBuffer.allocate(4));eq(40000,count(c,"observedUnderruns"),"no short underrun overflow");eq(1256,f.lastResize,"one burst growth");f.underruns++;c.writeDataToTrack(ByteBuffer.allocate(4));eq(1300,f.lastResize,"growth clamped to capacity");eq(1300,c.performanceSnapshot().getInt("bufferSizeFrames"),"actual resized value");f.underruns++;c.writeDataToTrack(ByteBuffer.allocate(4));eq(2,f.resizes,"never resize beyond capacity");eq(44100,c.performanceSnapshot().getInt("sampleRate"),"actual negotiated rate");
  scenarios++;f=new Fake();c=make(f);Field position=ALSAClient.class.getDeclaredField("positionBytes");position.setAccessible(true);position.setLong(c,(1L<<31)+8);eq(536870914,c.pointer(),"no signed byte overflow");position.setLong(c,(1L<<32)*4+12);eq(3,c.pointer(),"wire frame wraps32");eq(Long.MAX_VALUE,ALSAClient.saturatedAdd(Long.MAX_VALUE-1,5),"telemetry saturates");
  scenarios++;f=new Fake();f.entered=new CountDownLatch(1);f.unblock=new CountDownLatch(1);c=make(f);final ALSAClient blocked=c;Thread writer=new Thread(()->blocked.writeDataToTrack(ByteBuffer.allocate(4)));writer.start();check(f.entered.await(1,TimeUnit.SECONDS),"write entered");long before=System.nanoTime();c.performanceSnapshot();check(System.nanoTime()-before<200_000_000L,"snapshot cannot wait on write");c.stopForShutdown();writer.join(500);check(!writer.isAlive(),"shutdown unblocked writer");c.start();eq(1,f.plays,"no start after shutdown");c.prepare();eq(1,f.plays,"no prepare after shutdown");eq(1,count(c,"shutdowns"),"idempotent shutdown");c.stopForShutdown();eq(1,count(c,"shutdowns"),"idempotence");
  scenarios++;CountDownLatch creating=new CountDownLatch(1),finish=new CountDownLatch(1);Fake created=new Fake();c=new ALSAClient(new ALSAClient.Options(),(format,bytes,o)->{creating.countDown();try{check(finish.await(1,TimeUnit.SECONDS),"creation timeout");}catch(InterruptedException e){throw new AssertionError(e);}return created;},System::nanoTime);c.setChannels(2);c.setDataType(ALSAClient.DataType.S16LE);c.setSampleRate(48000);c.setBufferSize(1024);final ALSAClient starting=c;Thread prep=new Thread(starting::prepare);prep.start();check(creating.await(1,TimeUnit.SECONDS),"creation started");c.stopForShutdown();finish.countDown();prep.join(500);check(!prep.isAlive()&&created.released&&created.plays==0,"shutdown wins prepare publication");
  scenarios++;ALSATelemetry telemetry=new ALSATelemetry();for(int n=0;n<12;n++){ALSAClient a=make(new Fake());telemetry.register(a);a.writeDataToTrack(ByteBuffer.allocate(4));}JSONObject snap=telemetry.performanceSnapshot();eq(12,snap.getInt("activeClients"),"all active counted");eq(8,snap.getJSONArray("clients").length(),"bounded active details");eq(4,snap.getInt("omittedClientDetails"),"omitted count");eq(48,snap.getLong("writtenBytes"),"aggregate all clients");telemetry.stopClients();ALSAClient late=make(new Fake());telemetry.register(late);check(late.performanceSnapshot().getBoolean("closing"),"late client cancelled");
  scenarios++;telemetry=new ALSATelemetry();for(int n=0;n<12;n++){ALSAClient a=make(new Fake());telemetry.register(a);a.writeDataToTrack(ByteBuffer.allocate(4));a.release();telemetry.unregister(a);telemetry.unregister(a);}snap=telemetry.performanceSnapshot();eq(12,snap.getLong("closedConnections"),"closed once");eq(48,snap.getLong("writtenBytes"),"closed totals retained");eq(8,snap.getJSONArray("clients").length(),"bounded retired details");
  scenarios++;ALSARequestHandler h=new ALSARequestHandler();c=make(new Fake());check(!h.handleRequest(connection(c,new byte[4])),"fragmented header");check(!h.handleRequest(connection(c,packet(4,10,new byte[9]))),"fragmented prepare");check(!h.handleRequest(connection(c,packet(8,6,new byte[5]))),"fragmented min buffer");invalid(h,c,4,9,new byte[9]);invalid(h,c,8,5,new byte[5]);invalid(h,c,4,10,prepare(2,255,48000,1024));invalid(h,c,4,10,prepare(0,1,48000,1024));invalid(h,c,4,10,prepare(2,1,0,1024));invalid(h,c,4,10,prepare(2,4,48000,Integer.MAX_VALUE));invalid(h,c,5,-1,new byte[0]);invalid(h,c,99,0,new byte[0]);
  scenarios++;c=make(new Fake());ConnectedClient cc=connection(c,packet(4,10,prepare(2,1,22050,1025)));check(h.handleRequest(cc),"valid prepare");eq(4100,c.getBufferSizeInBytes(),"frames payload");check(c.getSharedBuffer()!=null,"shared mapping");eq(4104,c.getSharedBuffer().capacity(),"4 byte pointer prefix");
  scenarios++;ByteBuffer shm=c.getSharedBuffer();for(int n=4;n<20;n++)shm.put(n,(byte)n);cc.input=new XInputStream(packet(5,4,new byte[0]));check(h.handleRequest(cc),"short shm write");cc.input=new XInputStream(packet(5,16,new byte[0]));check(h.handleRequest(cc),"growing shm write resets limits");eq(20,count(c,"writtenBytes"),"two shared writes");eq(5,shm.getInt(0),"shared frame pointer");invalid(h,c,5,4101,new byte[0]);
  scenarios++;AudioTrack.block=true;ALSAServerComponent component=new ALSAServerComponent(new UnixSocketConfig(),new ALSAClient.Options());new XEnvironment().addComponent(component);component.start();ConnectedClient live=new ConnectedClient();XConnectorEpoll.last.connections.handleNewConnection(live);ALSAClient client=(ALSAClient)live.getTag();client.setChannels(2);client.setDataType(ALSAClient.DataType.S16LE);client.setSampleRate(48000);client.setBufferSize(1024);client.prepare();Thread active=new Thread(()->client.writeDataToTrack(ByteBuffer.allocate(4)));active.start();check(AudioTrack.last.entered.await(1,TimeUnit.SECONDS),"component writer active");XConnectorEpoll.beforeDestroy=()->{check(AudioTrack.last.pauses>0,"pause before connector joins");try{active.join(500);}catch(InterruptedException e){throw new AssertionError(e);}check(!active.isAlive(),"join can finish");XConnectorEpoll.last.connections.handleConnectionShutdown(live);};component.stop();eq(1,component.performanceSnapshot().getLong("closedConnections"),"shutdown records retained");check(!component.performanceSnapshot().getBoolean("running"),"server stopped");AudioTrack.block=false;XConnectorEpoll.beforeDestroy=null;
  scenarios++;Fake stale=new Fake();ALSAClient sampled=make(stale);stale.underruns=397;stale.stalled="underruns";stale.sampleRead=new CountDownLatch(1);stale.returnSample=new CountDownLatch(1);AtomicReference<Throwable> samplerFailure=new AtomicReference<>();Thread sampler=new Thread(()->{try{sampled.performanceSnapshot();}catch(Throwable error){samplerFailure.set(error);}});sampler.start();check(stale.sampleRead.await(1,TimeUnit.SECONDS),"stale underrun captured");stale.underruns=400;sampled.writeDataToTrack(ByteBuffer.allocate(4));stale.returnSample.countDown();sampler.join(500);check(!sampler.isAlive()&&samplerFailure.get()==null,"stale sampler finished");eq(400,count(sampled,"observedUnderruns"),"stale low sample never double-counts");eq(400,sampled.performanceSnapshot().getInt("underrunCount"),"current underruns never decrease");
  scenarios++;Fake old=new Fake(),replacement=new Fake();replacement.rate=22050;replacement.size=256;replacement.capacity=512;replacement.underruns=2;AtomicInteger generation=new AtomicInteger();ALSAClient switching=new ALSAClient(new ALSAClient.Options(),(format,bytes,o)->generation.getAndIncrement()==0?old:replacement,System::nanoTime);switching.setChannels(2);switching.setDataType(ALSAClient.DataType.S16LE);switching.setSampleRate(44100);switching.setBufferSize(1024);switching.prepare();old.stalled="rate";old.sampleRead=new CountDownLatch(1);old.returnSample=new CountDownLatch(1);Thread oldSampler=new Thread(()->{try{switching.performanceSnapshot();}catch(Throwable error){samplerFailure.set(error);}});oldSampler.start();check(old.sampleRead.await(1,TimeUnit.SECONDS),"old track sample captured");switching.setSampleRate(22050);switching.setBufferSize(256);switching.prepare();old.underruns=999;old.returnSample.countDown();oldSampler.join(500);check(!oldSampler.isAlive()&&samplerFailure.get()==null,"old track sampler finished");JSONObject current=switching.performanceSnapshot();eq(22050,current.getInt("sampleRate"),"old rate cannot overwrite replacement");eq(256,current.getInt("bufferSizeFrames"),"old size cannot overwrite replacement");eq(512,current.getInt("bufferCapacityFrames"),"old capacity cannot overwrite replacement");eq(2,current.getLong("observedUnderruns"),"old track cannot add false underruns");eq(2,current.getInt("underrunCount"),"new track underrun baseline");
  System.out.println("Audio bridge: "+scenarios+" scenarios / "+checks+" checks passed");
 }
}
'''


def main():
    sources = [ROOT / "app/src/main/java" / p for p in (
        "com/winlator/alsaserver/ALSAClient.java", "com/winlator/alsaserver/ALSATelemetry.java",
        "com/winlator/alsaserver/ALSAClientConnectionHandler.java", "com/winlator/alsaserver/ALSARequestHandler.java",
        "com/winlator/alsaserver/RequestCodes.java", "com/winlator/xenvironment/components/ALSAServerComponent.java",
        "com/winlator/pd2/Pd2AudioPolicy.java", "com/winlator/core/KeyValueSet.java",
    )]
    with tempfile.TemporaryDirectory(prefix="pd2-audio-bridge-") as temporary:
        directory = Path(temporary)
        for name, content in STUBS.items():
            path = directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(path)
        fixture = directory / "com/winlator/alsaserver/AudioBridgeCheck.java"
        fixture.parent.mkdir(parents=True, exist_ok=True)
        fixture.write_text(FIXTURE)
        sources.append(fixture)
        javac = shutil.which("javac")
        compiler = [javac] if javac else ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main"]
        subprocess.run([*compiler, "--release", "17", "-d", str(directory), *map(str, sources)], check=True, timeout=60)
        subprocess.run(["java", "-cp", str(directory), "com.winlator.alsaserver.AudioBridgeCheck"], check=True, timeout=30)


if __name__ == "__main__":
    main()
