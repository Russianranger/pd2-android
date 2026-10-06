#!/usr/bin/env python3
"""Check actual runtime-attempt retention and head/tail preservation on the JVM."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "android/content/Context.java": """package android.content;
import java.io.File;
public class Context {
 private final File files;
 public Context(File files) { this.files=files; }
 public File getFilesDir() { return files; }
}
""",
    "SessionLogTest.java": """import android.content.Context;
import com.winlator.pd2.Pd2SessionLog;
import com.winlator.pd2.Pd2LogOutputStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class SessionLogTest {
 static int checks;
 static void check(boolean ok,String message) { checks++; if(!ok)throw new AssertionError(message); }
 static String read(File file)throws Exception { return Files.readString(file.toPath()); }
 static File[] attempts(File folder) { return folder.listFiles(f -> f.isFile() && f.getName().matches("runtime-attempt-[0-9]{13,19}\\\\.log")); }
 public static void main(String[] args)throws Exception {
  File work=Files.createTempDirectory("pd2-session-log-tests-").toFile();
  try {
   Context context=new Context(work);
   check(Pd2SessionLog.archivePrevious(context)==null,"no log does not create a fabricated attempt");
   File source=new File(work,"pd2/logs/runtime.log"); source.getParentFile().mkdirs();
   Files.write(source.toPath(),new byte[0]);
   check(Pd2SessionLog.archivePrevious(work)==null,"empty log does not create a fabricated attempt");
   String first="FIRST-FAULT: stack overflow\\nsmall complete log\\nLAST-EXIT: status7\\n";
   Files.writeString(source.toPath(),first);
   File small=Pd2SessionLog.archivePrevious(context);
   check(read(small).endsWith(first) && !read(small).contains("middle omitted"),"small previous log copied intact");
   check(read(source).equals(first),"current runtime.log never altered or deleted");
   File folder=Pd2SessionLog.getAttemptsDirectory(work);
   File unrelated=new File(folder,"personal-note.log"); Files.writeString(unrelated.toPath(),"keep me");
   File unrelatedTemp=new File(folder,"personal-note.log.tmp"); Files.writeString(unrelatedTemp.toPath(),"keep temp");
   File unrelatedDirectory=new File(folder,"runtime-attempt-0000000000001.log");unrelatedDirectory.mkdir();
   File stale=new File(folder,"runtime-attempt-0000000000002.log.tmp");Files.writeString(stale.toPath(),"interrupted");
   File reservation=new File(folder,"runtime-attempt-0000000000002.log");reservation.createNewFile();
   File save=new File(work,"pd2/install/test.d2s");save.getParentFile().mkdirs();Files.write(save.toPath(),new byte[]{7});

   byte[] large=new byte[5*1024*1024]; Arrays.fill(large,(byte)'m');
   byte[] head="FIRST-FAULT: preserve early SEH evidence\\n".getBytes(StandardCharsets.UTF_8);
   byte[] tail="\\nLAST-EXIT: preserve final exit status123".getBytes(StandardCharsets.UTF_8);
   System.arraycopy(head,0,large,0,head.length);
   System.arraycopy(tail,0,large,large.length-tail.length,tail.length);
   Files.write(source.toPath(),large);
   File capped=Pd2SessionLog.archivePrevious(work);
   check(capped.length()<=Pd2SessionLog.MAX_ARCHIVE_BYTES,"large archive strictly bounded to2MiB including metadata");
   String bounded=read(capped);
   check(bounded.contains(new String(head,StandardCharsets.UTF_8)) && bounded.endsWith(new String(tail,StandardCharsets.UTF_8)),
     "large log retains early fault and final exit");
   check(bounded.contains("middle omitted") && bounded.contains("Original log bytes: "+large.length),"explicit truncation and original length metadata");
   check(Files.size(source.toPath())==large.length && Arrays.equals(Files.readAllBytes(source.toPath()),large),"oversized source remains unchanged");
   check(!stale.exists() && !reservation.exists(),"interrupted temporary/reservation recovered");

   int priorAttempts=attempts(folder).length;
   ByteArrayOutputStream memory=new ByteArrayOutputStream();
   try(ZipOutputStream zip=new ZipOutputStream(memory)) {
    zip.putNextEntry(new ZipEntry("runtime.log"));
    Pd2SessionLog.writeSnapshot(source,zip);zip.closeEntry();
    zip.putNextEntry(new ZipEntry("another.txt"));zip.write("still open".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
   }
   try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(memory.toByteArray()))) {
    check(zip.getNextEntry().getName().equals("runtime.log"),"snapshot writes into caller ZIP entry");
    byte[] snapshot=zip.readAllBytes();String text=new String(snapshot,StandardCharsets.UTF_8);
    check(snapshot.length<=Pd2SessionLog.MAX_ARCHIVE_BYTES && text.contains("FIRST-FAULT") && text.endsWith(new String(tail,StandardCharsets.UTF_8))
      && text.contains("middle omitted"),"stream snapshot preserves first fault and last exit within2MiB");
    check(zip.getNextEntry().getName().equals("another.txt") && new String(zip.readAllBytes(),StandardCharsets.UTF_8).equals("still open"),
      "snapshot leaves caller stream open for subsequent ZIP entries");
   }
   check(attempts(folder).length==priorAttempts && Arrays.equals(Files.readAllBytes(source.toPath()),large),
     "stream snapshot does not archive, prune or modify source");

   ArrayList<File> captured=new ArrayList<>();
   Files.writeString(source.toPath(),first);
   for(int i=0;i<8;i++)captured.add(Pd2SessionLog.archivePrevious(work));
   check(new HashSet<>(captured).size()==8,"rapid captures have unique names");
   check(attempts(folder).length==Pd2SessionLog.MAX_ATTEMPTS,"latest four attempts retained");
   for(int i=0;i<captured.size();i++)check(captured.get(i).exists()==(i>=4),"retention preserves newest captures");
   check(unrelated.isFile() && read(unrelated).equals("keep me") && unrelatedTemp.isFile() && unrelatedDirectory.isDirectory()
     && save.isFile(),"pruning/recovery never deletes unrelated logs, directories or game saves");
   check(Arrays.stream(folder.listFiles()).noneMatch(f -> f.getName().startsWith("runtime-attempt-") && f.getName().endsWith(".tmp")),
     "complete captures leave no staging files");
   // Live logging must keep startup and latest events through repeated rotations.
   try(Pd2LogOutputStream rolling=new Pd2LogOutputStream(source)) {
    rolling.write("STARTUP: fixture\\n".getBytes(StandardCharsets.UTF_8));
    byte[] chunk=new byte[64*1024]; Arrays.fill(chunk,(byte)'x');
    for(int i=0;i<700;i++) {
     rolling.write(chunk);
     check(source.length()<=Pd2LogOutputStream.MAX_BYTES,"live bound through repeated rotations");
    }
    rolling.write("\\nLATEST: Save and Exit\\n".getBytes(StandardCharsets.UTF_8));
    rolling.flush();
    String live=read(source);
    check(live.startsWith("STARTUP: fixture\\n") && live.contains("runtime log rotated")
      && live.endsWith("LATEST: Save and Exit\\n"),"startup and latest retained after many rotations");
    ByteArrayOutputStream current=new ByteArrayOutputStream(); Pd2SessionLog.writeSnapshot(source,current);
    String currentText=current.toString(StandardCharsets.UTF_8);
    check(current.size()<=Pd2SessionLog.MAX_ARCHIVE_BYTES && currentText.contains("STARTUP: fixture")
      && currentText.endsWith("LATEST: Save and Exit\\n"),"live snapshot retains latest transition");
   }
   // UTF-8 characters cross both retention boundaries; slices remain decodable.
   try(Pd2LogOutputStream rolling=new Pd2LogOutputStream(source)) {
    byte[] unicode="A🎮".repeat(14000).getBytes(StandardCharsets.UTF_8);
    for(int i=0;i<230;i++) rolling.write(unicode);
    rolling.write("\\nUTF8-LATEST\\n".getBytes(StandardCharsets.UTF_8));
    byte[] live=Files.readAllBytes(source.toPath());
    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(live));
    check(live.length<=Pd2LogOutputStream.MAX_BYTES,"Unicode rolling log is bounded and strictly valid UTF8");
    ByteArrayOutputStream current=new ByteArrayOutputStream(); Pd2SessionLog.writeSnapshot(source,current);
    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(current.toByteArray()));
    check(current.toString(StandardCharsets.UTF_8).endsWith("UTF8-LATEST\\n"),"UTF8 snapshot boundaries retain latest complete event");
   }
   // A byte-oriented writer may pause midway through a multibyte character.
   try(Pd2LogOutputStream rolling=new Pd2LogOutputStream(source)) {
    rolling.write(new byte[]{(byte)0xf0,(byte)0x9f});
    ByteArrayOutputStream partial=new ByteArrayOutputStream();Pd2SessionLog.writeSnapshot(source,partial);
    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(partial.toByteArray()));
    check(!partial.toString(StandardCharsets.UTF_8).contains("�"),"snapshot defers an incomplete UTF8 suffix");
    rolling.write(new byte[]{(byte)0x8e,(byte)0xae});
    ByteArrayOutputStream complete=new ByteArrayOutputStream();Pd2SessionLog.writeSnapshot(source,complete);
    check(complete.toString(StandardCharsets.UTF_8).endsWith("🎮"),"later snapshot includes the completed UTF8 character");
   }
   // Starting a new attempt retires its prior writer, including stale callbacks.
   Pd2LogOutputStream previous=new Pd2LogOutputStream(source); previous.write("OLD-ATTEMPT".getBytes(StandardCharsets.UTF_8));
   File oldAttempt=Pd2SessionLog.archivePrevious(work);
   try(Pd2LogOutputStream current=new Pd2LogOutputStream(source)) {
    current.write("NEW-ATTEMPT".getBytes(StandardCharsets.UTF_8));
    boolean rejected=false;
    try {previous.write("STALE-CALLBACK".getBytes(StandardCharsets.UTF_8));}catch(IOException expected){rejected=true;}
    check(rejected && read(source).equals("NEW-ATTEMPT") && read(oldAttempt).contains("OLD-ATTEMPT"),
      "stale writer cannot contaminate next attempt or archived prior attempt");
   }
   // Snapshot and rotation share a lock, so an export cannot read a mixed file.
   try(Pd2LogOutputStream rolling=new Pd2LogOutputStream(source)) {
    rolling.write("CONCURRENT-START\\n".getBytes(StandardCharsets.UTF_8));
    final Throwable[] failure={null};
    Thread writer=new Thread(() -> {
     try {byte[] chunk=new byte[64*1024];Arrays.fill(chunk,(byte)'q');for(int i=0;i<500;i++)rolling.write(chunk);
      rolling.write("\\nCONCURRENT-END\\n".getBytes(StandardCharsets.UTF_8));}
     catch(Throwable error){failure[0]=error;}
    });writer.start();
    for(int i=0;i<15;i++) {
     ByteArrayOutputStream current=new ByteArrayOutputStream(); Pd2SessionLog.writeSnapshot(source,current);
     check(current.size()<=Pd2SessionLog.MAX_ARCHIVE_BYTES && current.toString(StandardCharsets.UTF_8).contains("CONCURRENT-START"),
       "concurrent snapshot has one bounded chronological state");
    }
    writer.join();check(failure[0]==null && read(source).endsWith("CONCURRENT-END\\n"),"writer continues after concurrent exports");
   }
   // Retention still applies if an old process left more than four logs and no current log.
   File old=new File(folder,"runtime-attempt-0000000000000.log"); Files.writeString(old.toPath(),"stale attempt");
   source.delete();check(Pd2SessionLog.archivePrevious(work)==null && !old.exists() && attempts(folder).length==4,
     "missing current log still prunes stale attempt history");
   // Archive and snapshot paths never follow a runtime file or attempt-directory link.
   File privateFile=new File(work,"private.txt");Files.writeString(privateFile.toPath(),"private contents");
   Files.createSymbolicLink(source.toPath(),privateFile.toPath());
   check(Pd2SessionLog.archivePrevious(work)==null && read(privateFile).equals("private contents"),
     "linked runtime source cannot be archived or altered");
   boolean linkedSnapshotRejected=false;
   try {Pd2SessionLog.writeSnapshot(source,new ByteArrayOutputStream());}catch(IOException expected){linkedSnapshotRejected=true;}
   check(linkedSnapshotRejected,"stream snapshot does not follow a runtime source link");
   source.delete();Files.writeString(source.toPath(),"safe current runtime");
   File priorFolder=new File(folder.getParentFile(),"old-attempts");check(folder.renameTo(priorFolder),"move fixture archive directory");
   File outside=new File(work,"outside-attempts");outside.mkdir();Files.createSymbolicLink(folder.toPath(),outside.toPath());
   check(Pd2SessionLog.archivePrevious(work)==null && outside.listFiles().length==0,
     "attempt directory link cannot redirect archive writes");
   System.out.println("PD2 session log tests passed: "+checks+" checks");
  }finally {
   try(var paths=Files.walk(work.toPath())) {
    paths.sorted(Comparator.reverseOrder()).forEach(p -> {try{Files.delete(p);}catch(IOException ignored){}});
   }
  }
 }
}
""",
}
with tempfile.TemporaryDirectory(prefix="pd2-session-log-jvm-") as directory:
    work = Path(directory)
    sources = []
    for name, content in SOURCES.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(str(path))
    sources.append(str(ROOT / "app/src/main/java/com/winlator/pd2/Pd2SessionLog.java"))
    sources.append(str(ROOT / "app/src/main/java/com/winlator/pd2/Pd2LogOutputStream.java"))
    subprocess.run(["javac", "-d", str(work / "classes"), *sources], check=True)
    subprocess.run(["java", "-cp", str(work / "classes"), "SessionLogTest"], check=True)
