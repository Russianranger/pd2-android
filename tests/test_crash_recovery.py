#!/usr/bin/env python3
"""Exercise production crash storage and uncaught-handler delegation on the JVM.

Android stubs cover only Context/Application metadata; this does not qualify UI
routing, Android crash delivery, or FileProvider sharing on a device.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "android/content/Context.java": """package android.content;
import java.io.File;
public class Context {
 private final File files;
 public Context() { files = null; }
 public Context(File files) { this.files = files; }
 public File getFilesDir() { return files; }
 public String getPackageName() { return "com.pd2.thor"; }
}
""",
    "android/app/Application.java": """package android.app;
import android.content.Context;
public class Application extends Context { protected void attachBaseContext(Context context) {} }
""",
    "android/os/Build.java": """package android.os;
public class Build {
 public static final String MANUFACTURER="test", MODEL="JVM";
 public static class VERSION { public static final String RELEASE="13"; public static final int SDK_INT=33; }
}
""",
    "android/os/Process.java": """package android.os;
public class Process { public static int myPid() { return 1; } public static void killProcess(int pid) {} }
""",
    "com/winlator/pd2/CrashTest.java": """package com.winlator.pd2;
import android.content.Context;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CrashTest {
 private static int checks;
 private static void check(boolean value, String message) {
  checks++; if (!value) throw new AssertionError(message);
 }
 public static void main(String[] args) throws Exception {
  File work=Files.createTempDirectory("pd2-crash-test-").toFile();
  Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
  try {
   Context context=new Context(work);
   check(!Pd2CrashLog.hasPending(context), "fresh startup does not enter recovery");
   Throwable original=new IllegalStateException("first failure", new IOException("cause evidence"));
   Pd2CrashLog.record(context, Thread.currentThread(), original, "Test metadata");
   check(Pd2CrashLog.hasPending(context), "complete report creates pending marker");
   File report=Pd2CrashLog.getCrashFile(context);
   String first=Files.readString(report.toPath());
   check(first.contains("first failure") && first.contains("cause evidence") && first.contains("Test metadata"),
     "exception, cause, stack and metadata captured");
   Pd2CrashLog.acknowledge(context);
   check(!Pd2CrashLog.hasPending(context) && report.isFile() && Files.readString(report.toPath()).equals(first),
     "retry acknowledges marker and retains exact previous report");

   String huge="é🍎".repeat(100000);
   Pd2CrashLog.record(work, null, new RuntimeException(huge), "unicode size limit");
   byte[] large=Files.readAllBytes(report.toPath());
   check(large.length <= Pd2CrashLog.MAX_CRASH_BYTES, "report has strict 128 KiB byte limit");
   String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
     .decode(ByteBuffer.wrap(large)).toString();
   check(decoded.contains("truncated") && decoded.contains("unicode size limit") && !decoded.contains("first failure"),
     "oversized Unicode report is valid UTF-8, explicitly truncated, and replaces earlier report");

   AtomicInteger delegated=new AtomicInteger();
   Thread.UncaughtExceptionHandler originalHandler=(thread,error)-> {
    check(error == original && thread == Thread.currentThread(), "delegate receives original error and thread");
    delegated.incrementAndGet();
   };
   Thread.setDefaultUncaughtExceptionHandler(originalHandler);
   new Pd2Application().attachBaseContext(context);
   Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), original);
   check(delegated.get() == 1 && Pd2CrashLog.hasPending(context), "capture then delegate Android handler exactly once");

   File blocked=new File(work,"not-a-directory"); Files.write(blocked.toPath(),new byte[]{1});
   Thread.setDefaultUncaughtExceptionHandler(originalHandler);
   new Pd2Application().attachBaseContext(new Context(blocked));
   Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), original);
   check(delegated.get() == 2, "failed crash write still delegates original fatal exception");
   check(!new File(report.getParentFile(), "crash.txt.tmp").exists(), "successful capture leaves no temporary file");
   System.out.println("PD2 crash recovery tests passed: "+checks+" checks");
  } finally {
   Thread.setDefaultUncaughtExceptionHandler(previous);
   try (var paths=Files.walk(work.toPath())) {
    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch(IOException ignored) {} });
   }
  }
 }
}
""",
}

with tempfile.TemporaryDirectory(prefix="pd2-crash-jvm-") as directory:
    work = Path(directory)
    sources = []
    for name, content in STUBS.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(str(path))
    for name in ("Pd2CrashLog.java", "Pd2Application.java"):
        sources.append(str(ROOT / "app/src/main/java/com/winlator/pd2" / name))
    subprocess.run(["javac", "-d", str(work / "classes"), *sources], check=True)
    subprocess.run(["java", "-cp", str(work / "classes"), "com.winlator.pd2.CrashTest"], check=True)
