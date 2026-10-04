#!/usr/bin/env python3
"""Exercise production PD2 cleanup with an X server sink that counts raw events."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "com/winlator/xserver/Pointer.java": """package com.winlator.xserver;
import java.util.EnumSet;
public final class Pointer {
 public enum Button { LEFT, MIDDLE, RIGHT, UP, DOWN, SCROLL_LEFT, SCROLL_RIGHT }
 public final EnumSet<Button> held=EnumSet.noneOf(Button.class);
 public boolean isButtonPressed(Button button) { return held.contains(button); }
}
""",
    "com/winlator/xserver/XLock.java": """package com.winlator.xserver;
public interface XLock extends AutoCloseable { void close(); }
""",
    "com/winlator/xserver/XServer.java": """package com.winlator.xserver;
import java.util.ArrayList;
public final class XServer {
 public enum Lockable { WINDOW_MANAGER, INPUT_DEVICE }
 public final Pointer pointer=new Pointer();
 public final ArrayList<Pointer.Button> rawReleases=new ArrayList<>();
 private boolean locked;
 public XLock lock(Lockable... ignored) {
  if(locked) throw new AssertionError("unexpected nested cleanup lock");
  locked=true; return () -> locked=false;
 }
 public void injectPointerButtonRelease(Pointer.Button button) {
  if(!locked) throw new AssertionError("cleanup must lock held-state decision and release");
  pointer.held.remove(button);
  // Actual XServer sends raw releases even when Pointer suppresses an unheld core event.
  rawReleases.add(button);
 }
}
""",
    "CleanupCheck.java": """import com.winlator.xserver.*;
import com.winlator.pd2.Pd2PointerCleanup;
public final class CleanupCheck {
 public static void main(String[] args) {
  XServer server=new XServer();
  Pd2PointerCleanup.releaseHeldButtons(server);
  if(!server.rawReleases.isEmpty()) throw new AssertionError("neutral Native transition emitted mouse events");
  server.pointer.held.add(Pointer.Button.LEFT);
  server.pointer.held.add(Pointer.Button.RIGHT);
  Pd2PointerCleanup.releaseHeldButtons(server);
  if(server.rawReleases.size()!=2 || !server.rawReleases.contains(Pointer.Button.LEFT)
    || !server.rawReleases.contains(Pointer.Button.RIGHT) || !server.pointer.held.isEmpty())
   throw new AssertionError("held buttons must be balanced exactly once");
  Pd2PointerCleanup.releaseHeldButtons(server);
  if(server.rawReleases.size()!=2) throw new AssertionError("repeated lifecycle cleanup emitted duplicate raw events");
  server.pointer.held.add(Pointer.Button.MIDDLE);
  Pd2PointerCleanup.releaseHeldButtons(server);
  if(server.rawReleases.size()!=3 || !server.pointer.held.isEmpty()) throw new AssertionError("new held input not balanced");
  System.out.println("PASS: neutral, held, repeated and resumed pointer cleanup");
 }
}
""",
}


def main():
    with tempfile.TemporaryDirectory(prefix="pd2-pointer-cleanup-") as temp:
        directory = Path(temp)
        for name, content in STUBS.items():
            path = directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        source = ROOT / "app/src/main/java/com/winlator/pd2/Pd2PointerCleanup.java"
        subprocess.run(["javac", "-d", str(directory), str(source),
                        *(str(directory / name) for name in STUBS)], check=True)
        subprocess.run(["java", "-cp", str(directory), "CleanupCheck"], check=True)


if __name__ == "__main__":
    main()
