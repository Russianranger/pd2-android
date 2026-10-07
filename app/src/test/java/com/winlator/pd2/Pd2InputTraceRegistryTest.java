package com.winlator.pd2;

import android.app.Application;

import androidx.preference.PreferenceManager;

import com.winlator.xenvironment.RootFS;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import static org.junit.Assert.*;

/** Raw Wine hive preservation, interrupted installation and exact captured-prefix restoration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2InputTraceRegistryTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();
    private static final String HEADER = "WINE REGISTRY Version 2\n#arch=win64\n\n";
    private static final String KEY = "[Software\\\\Wine\\\\Debug] 1720000000\n#time=01dabbcc00112233\n";
    private static final String OTHER = "[Software\\\\Other] 42\n\"Version\"=\"unchanged\"\n";

    @Test public void traceIsOffByDefaultAndCleanupMustHavePassed() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        assertFalse(Pd2InputTraceRuntime.requested(context));
        Pd2InputTraceRuntime.requireCleanup(cleanup("beforeLaunch", true), true);
        Pd2InputTraceRuntime.requireCleanup(cleanup("afterStop", true), false);
        for (Pd2WineSession.Result invalid : new Pd2WineSession.Result[]{null,
                cleanup("beforeLaunch", false), cleanup("afterStop", false), cleanup("stopSkipped", true)}) {
            for (boolean preparing : new boolean[]{false, true}) {
                try { Pd2InputTraceRuntime.requireCleanup(invalid, preparing); fail("Must pass cleanup before mutation"); }
                catch (IOException expected) { }
            }
        }
        try { Pd2InputTraceRuntime.prepareAfterCleanup(null, cleanup("afterStop", true), null, null); fail(); }
        catch (IOException expected) { }
    }

    @Test public void originalRawTypesEscapesAndContinuationsRoundTripExactly() throws Exception {
        String original = HEADER + KEY + "\"RelayInclude\"=\"old\\\\module.\\\"Export\\\"\"\n"
                + "\"RelayExclude\"=dword:1234abcd\n"
                + "\"RelayFromInclude\"=hex(7):47,00,61,00,\\\n  6d,00,65,00,00,00\n"
                + "\"RelayFromExclude\"=str(2):\"%CUSTOM%\\\\x\"\n"
                + "\"SnoopInclude\"=\"Fog.*\"\n\n" + OTHER;
        Fixture f = fixture(original);
        Pd2InputTraceRegistry.apply(f.hive, f.journal);
        String applied = read(f.hive);
        assertTrue(applied.contains("\"RelayInclude\"=\"" + Pd2InputTraceRegistry.FILTER + "\"\n"));
        assertTrue(applied.contains("\"RelayExclude\"=\"\"\n"));
        assertFalse(applied.contains("\"RelayFromInclude\"="));
        assertFalse(applied.contains("\"RelayFromExclude\"="));
        assertTrue(applied.contains("\"SnoopInclude\"=\"Fog.*\""));
        Pd2InputTraceRegistry.RestoreResult result = Pd2InputTraceRegistry.restore(f.hive, f.journal);
        assertEquals(4, result.restoredFields);
        assertEquals(0, result.conflictsPreserved);
        assertEquals(original, read(f.hive));
        assertFalse(f.journal.exists());
        assertFalse(Pd2InputTraceRegistry.restore(f.hive, f.journal).hadJournal);
        assertNoStaging(f);
    }

    @Test public void defaultOffWithoutJournalDoesNotInspectOrRequireAnyHive() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        File journal = new File(context.getFilesDir().getCanonicalFile(), "pd2/runtime/input-trace-journal.json");
        Files.deleteIfExists(journal.toPath());
        assertFalse(Pd2InputTraceRuntime.prepareAfterCleanup(context, cleanup("beforeLaunch", true), null, null));
        Pd2InputTraceRuntime.restoreAfterCleanup(context, cleanup("afterStop", true), null, null);
        assertFalse(journal.exists());
    }

    @Test public void requestedTraceConsumesPreferenceOnlyAfterApplyAndRestoresAfterStop() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        File journal = new File(context.getFilesDir().getCanonicalFile(), "pd2/runtime/input-trace-journal.json");
        Files.deleteIfExists(journal.toPath());
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
                .putBoolean(Pd2InputTraceRuntime.PREFERENCE, true).commit();
        String owner = "33333333-3333-3333-3333-333333333333";
        try { Pd2InputTraceRuntime.prepareAfterCleanup(context, cleanup("beforeLaunch", true), directory.newFile("wrong.reg"), owner); fail(); }
        catch (IOException expected) { }
        assertTrue(Pd2InputTraceRuntime.requested(context));
        assertFalse(journal.exists());
        File hive = new File(RootFS.find(context).getRootDir().getCanonicalFile(), "home/trace-runtime-018/.wine/user.reg");
        assertTrue(hive.getParentFile().isDirectory() || hive.getParentFile().mkdirs());
        String original = HEADER + KEY + "\"RelayInclude\"=hex:aa,bb\n";
        write(hive, original);
        assertTrue(Pd2InputTraceRuntime.prepareAfterCleanup(context, cleanup("beforeLaunch", true), hive, owner));
        assertFalse(Pd2InputTraceRuntime.requested(context));
        assertTrue(journal.isFile());
        assertTrue(Pd2InputTraceRuntime.status(context).getBoolean("pendingRestoration"));
        Pd2InputTraceRuntime.restoreAfterCleanup(context, cleanup("afterStop", true), hive, owner);
        assertEquals(original, read(hive));
        assertFalse(journal.exists());
    }

    @Test public void absentDebugKeyAndAbsentValuesAreRestoredWithoutLeavingPolicy() throws Exception {
        for (String original : new String[]{HEADER + OTHER, HEADER + KEY + "\"SnoopInclude\"=\"Fog.*\"\n"}) {
            Fixture f = fixture(original);
            Pd2InputTraceRegistry.apply(f.hive, f.journal);
            Pd2InputTraceRegistry.RestoreResult result = Pd2InputTraceRegistry.restore(f.hive, f.journal);
            assertTrue(result.hadJournal);
            assertEquals(2, result.restoredFields);
            assertEquals(original, read(f.hive));
            assertNoStaging(f);
        }
    }

    @Test public void unchangedDebugSectionRestoresExactOrderWhileOtherKeysChange() throws Exception {
        String original = HEADER + KEY + "\"RelayFromInclude\"=\"Game.exe\"\n\"SnoopInclude\"=\"Fog.*\"\n" + OTHER;
        Fixture f = fixture(original);
        Pd2InputTraceRegistry.apply(f.hive, f.journal);
        write(f.hive, read(f.hive).replace("\"Version\"=\"unchanged\"", "\"Version\"=\"changed\""));
        Pd2InputTraceRegistry.restore(f.hive, f.journal);
        assertEquals(original.replace("\"Version\"=\"unchanged\"", "\"Version\"=\"changed\""), read(f.hive));
    }

    @Test public void crlfAndUtf8BytesOutsideOwnedValuesStayExact() throws Exception {
        String original = (HEADER + KEY + "\"relayinclude\"=hex:ab,cd,\\\n  ef,00\n"
                + "\"SnoopInclude\"=\"Fög.*\"\n" + OTHER).replace("\n", "\r\n");
        Fixture f = fixture(original);
        byte[] before = Files.readAllBytes(f.hive.toPath());
        Pd2InputTraceRegistry.apply(f.hive, f.journal);
        Pd2InputTraceRegistry.restore(f.hive, f.journal);
        assertArrayEquals(before, Files.readAllBytes(f.hive.toPath()));
    }

    @Test public void unrelatedChangesAndChangedOwnedValuesArePreserved() throws Exception {
        String original = HEADER + KEY + "\"RelayInclude\"=\"previous\"\n"
                + "\"RelayExclude\"=hex:aa,bb\n\"RelayFromInclude\"=\"Game.exe\"\n" + OTHER;
        Fixture f = fixture(original);
        Pd2InputTraceRegistry.apply(f.hive, f.journal);
        write(f.hive, read(f.hive).replace("\"" + Pd2InputTraceRegistry.FILTER + "\"", "\"custom-change\"")
                .replace("\"Version\"=\"unchanged\"", "\"Version\"=\"new-value\""));
        Pd2InputTraceRegistry.RestoreResult result = Pd2InputTraceRegistry.restore(f.hive, f.journal);
        assertEquals(1, result.conflictsPreserved);
        assertTrue(read(f.hive).contains("\"RelayInclude\"=\"custom-change\""));
        assertTrue(read(f.hive).contains("\"RelayExclude\"=hex:aa,bb"));
        assertTrue(read(f.hive).contains("\"RelayFromInclude\"=\"Game.exe\""));
        assertTrue(read(f.hive).contains("\"Version\"=\"new-value\""));
        assertFalse(f.journal.exists());
    }

    @Test public void aCreatedKeyWithNewForeignValuesIsKept() throws Exception {
        Fixture f = fixture(HEADER + OTHER);
        Pd2InputTraceRegistry.apply(f.hive, f.journal);
        write(f.hive, read(f.hive) + "\"Foreign\"=\"keep\"\n");
        Pd2InputTraceRegistry.restore(f.hive, f.journal);
        assertTrue(read(f.hive).contains("[Software\\\\Wine\\\\Debug]"));
        assertTrue(read(f.hive).contains("\"Foreign\"=\"keep\""));
        assertFalse(read(f.hive).contains("\"RelayInclude\"="));
    }

    @Test public void anExternallyRemovedDebugKeyIsNotRecreated() throws Exception {
        Fixture f = fixture(HEADER + KEY + "\"RelayInclude\"=\"old\"\n\"RelayFromInclude\"=\"Game.exe\"\n" + OTHER);
        Pd2InputTraceRegistry.apply(f.hive, f.journal);
        write(f.hive, HEADER + OTHER);
        Pd2InputTraceRegistry.RestoreResult result = Pd2InputTraceRegistry.restore(f.hive, f.journal);
        assertEquals(2, result.conflictsPreserved);
        assertEquals(HEADER + OTHER, read(f.hive));
        assertFalse(f.journal.exists());
    }

    @Test public void interruptedPublishAndInterruptedRestoreAreIdempotent() throws Exception {
        String original = HEADER + KEY + "\"RelayInclude\"=\"old\"\n" + OTHER;
        Fixture f = fixture(original);
        Pd2InputTraceRegistry.apply(f.hive, f.journal);
        byte[] durableJournal = Files.readAllBytes(f.journal.toPath());
        write(f.hive, original); // Durable journal exists, but hive replacement never happened.
        assertEquals(0, Pd2InputTraceRegistry.restore(f.hive, f.journal).restoredFields);
        assertEquals(original, read(f.hive));
        Pd2InputTraceRegistry.apply(f.hive, f.journal);
        Pd2InputTraceRegistry.restore(f.hive, f.journal);
        Files.write(f.journal.toPath(), durableJournal); // Restored hive, crash before journal retirement.
        assertEquals(0, Pd2InputTraceRegistry.restore(f.hive, f.journal).restoredFields);
        assertEquals(original, read(f.hive));
        assertFalse(f.journal.exists());
    }

    @Test public void anotherCapturedHiveCannotBeSelectedByJournal() throws Exception {
        Fixture f = fixture(HEADER + KEY);
        Pd2InputTraceRegistry.apply(f.hive, f.journal);
        File other = directory.newFile("other.reg"); write(other, HEADER + OTHER);
        byte[] before = Files.readAllBytes(f.hive.toPath());
        try { Pd2InputTraceRegistry.restore(other, f.journal); fail("Captured prefix must match"); }
        catch (IOException expected) { }
        assertArrayEquals(before, Files.readAllBytes(f.hive.toPath()));
        assertEquals(HEADER + OTHER, read(other));
        assertTrue(f.journal.isFile());
    }

    @Test public void staleStopCannotRestoreAReplacementLaunchJournal() throws Exception {
        Fixture f = fixture(HEADER + KEY + "\"RelayInclude\"=\"old\"\n");
        String old = "11111111-1111-1111-1111-111111111111", replacement = "22222222-2222-2222-2222-222222222222";
        Pd2InputTraceRegistry.apply(f.hive, f.journal, replacement);
        byte[] hive = Files.readAllBytes(f.hive.toPath()), journal = Files.readAllBytes(f.journal.toPath());
        try { Pd2InputTraceRegistry.restore(f.hive, f.journal, old); fail("A stale same-prefix Stop must not restore the replacement trace"); }
        catch (IOException expected) { }
        assertArrayEquals(hive, Files.readAllBytes(f.hive.toPath()));
        assertArrayEquals(journal, Files.readAllBytes(f.journal.toPath()));
        assertEquals(2, Pd2InputTraceRegistry.restore(f.hive, f.journal, replacement).restoredFields);
        assertFalse(f.journal.exists());
        // A fresh, fully cleaned preflight may recover an interrupted older owner.
        Pd2InputTraceRegistry.apply(f.hive, f.journal, old);
        assertTrue(Pd2InputTraceRegistry.restore(f.hive, f.journal, null).hadJournal);
    }

    @Test public void malformedDuplicateUnknownAndOversizedValuesRefuseBeforeMutation() throws Exception {
        String huge = new String(new char[Pd2InputTraceRegistry.MAX_VALUE_BYTES + 1]).replace('\0', 'x');
        for (String bad : new String[]{
                HEADER + KEY + "\"RelayInclude\"=dword:1234\n",
                HEADER + KEY + "\"RelayInclude\"=unsupported:abcd\n",
                HEADER + KEY + "\"RelayInclude\"=hex:aa,xx\n",
                HEADER + KEY + "\"RelayInclude\"=\"truncated\n",
                HEADER + KEY + "\"RelayInclude\"=hex:aa,\\\nnot-continuation\n",
                HEADER + KEY + "\"RelayInclude\"=\"first\"\n\"relayinclude\"=\"second\"\n",
                HEADER + KEY + KEY,
                HEADER + KEY + "\"RelayInclude\"=\"" + huge + "\"\n",
                HEADER + KEY + "\"Foreign\"=\"" + huge + "\"\n",
                HEADER + KEY + "\"RelayInclude\"=\"old\"",
                "[Software\\\\Wine\\\\Debug]\n"}) {
            Fixture f = fixture(bad);
            try { Pd2InputTraceRegistry.apply(f.hive, f.journal); fail("Invalid targeted value must be kept"); }
            catch (IOException expected) { }
            assertEquals(bad, read(f.hive));
            assertFalse(f.journal.exists());
            assertNoStaging(f);
        }
    }

    @Test public void aPendingOrCorruptJournalIsNeverOverwritten() throws Exception {
        Fixture f = fixture(HEADER + KEY);
        write(f.journal, "not-a-journal");
        try { Pd2InputTraceRegistry.apply(f.hive, f.journal); fail(); }
        catch (IOException expected) { }
        try { Pd2InputTraceRegistry.restore(f.hive, f.journal); fail(); }
        catch (IOException expected) { }
        assertEquals("not-a-journal", read(f.journal));
        assertEquals(HEADER + KEY, read(f.hive));
    }

    @Test public void journalRawValuesCannotInjectAnotherFieldOrKey() throws Exception {
        Fixture f = fixture(HEADER + KEY + "\"RelayInclude\"=\"old\"\n");
        Pd2InputTraceRegistry.apply(f.hive, f.journal);
        byte[] applied = Files.readAllBytes(f.hive.toPath());
        JSONObject saved = new JSONObject(read(f.journal));
        saved.getJSONObject("prior").put("RelayInclude", Base64.getEncoder().encodeToString(
                "\"RelayInclude\"=\"old\"\n\"Foreign\"=\"injected\"\n".getBytes(StandardCharsets.UTF_8)));
        write(f.journal, saved.toString());
        try { Pd2InputTraceRegistry.restore(f.hive, f.journal); fail(); }
        catch (IOException expected) { }
        assertArrayEquals(applied, Files.readAllBytes(f.hive.toPath()));
        assertTrue(f.journal.exists());
    }

    @Test public void symbolicLinksAndNonRegularTargetsAreNeverFollowed() throws Exception {
        Fixture f = fixture(HEADER + KEY);
        File outside = directory.newFile("outside.reg"); write(outside, HEADER + OTHER);
        Files.delete(f.hive.toPath()); Files.createSymbolicLink(f.hive.toPath(), outside.toPath());
        try { Pd2InputTraceRegistry.apply(f.hive, f.journal); fail(); }
        catch (IOException expected) { }
        assertEquals(HEADER + OTHER, read(outside));
        assertTrue(Files.isSymbolicLink(f.hive.toPath()));
        assertFalse(f.journal.exists());
        Files.delete(f.hive.toPath()); write(f.hive, HEADER + KEY);
        Files.createSymbolicLink(f.journal.toPath(), new File(directory.getRoot(), "missing.json").toPath());
        try { Pd2InputTraceRegistry.apply(f.hive, f.journal); fail(); }
        catch (IOException expected) { }
        assertEquals(HEADER + KEY, read(f.hive));
        assertTrue(Files.isSymbolicLink(f.journal.toPath()));
        Files.delete(f.journal.toPath()); assertTrue(f.journal.mkdir());
        try { Pd2InputTraceRegistry.apply(f.hive, f.journal); fail(); }
        catch (IOException expected) { }
    }

    @Test public void forgedOriginalSectionCannotChangeUnownedDebugValues() throws Exception {
        Fixture f = fixture(HEADER + KEY + "\"SnoopInclude\"=\"Fog.*\"\n");
        Pd2InputTraceRegistry.apply(f.hive, f.journal);
        byte[] applied = Files.readAllBytes(f.hive.toPath());
        JSONObject saved = new JSONObject(read(f.journal));
        String old = new String(Base64.getDecoder().decode(saved.getString("originalSection")), StandardCharsets.UTF_8);
        saved.put("originalSection", Base64.getEncoder().encodeToString(old.replace("Fog.*", "secret-change").getBytes(StandardCharsets.UTF_8)));
        write(f.journal, saved.toString());
        try { Pd2InputTraceRegistry.restore(f.hive, f.journal); fail("A backup section must derive the current owned section without changing foreign values"); }
        catch (IOException expected) { }
        assertArrayEquals(applied, Files.readAllBytes(f.hive.toPath()));
        assertTrue(f.journal.exists());
    }

    @Test public void oversizedHiveAndJournalAreRejectedWithoutReadingBeyondLimits() throws Exception {
        Fixture f = fixture(HEADER + KEY);
        Files.write(f.hive.toPath(), new byte[Pd2InputTraceRegistry.MAX_HIVE_BYTES + 1]);
        try { Pd2InputTraceRegistry.apply(f.hive, f.journal); fail(); }
        catch (IOException expected) { }
        assertEquals(Pd2InputTraceRegistry.MAX_HIVE_BYTES + 1, f.hive.length());
        assertFalse(f.journal.exists());
        write(f.hive, HEADER + KEY);
        Files.write(f.journal.toPath(), new byte[Pd2InputTraceRegistry.MAX_JOURNAL_BYTES + 1]);
        try { Pd2InputTraceRegistry.restore(f.hive, f.journal); fail(); }
        catch (IOException expected) { }
        assertEquals(HEADER + KEY, read(f.hive));
        assertEquals(Pd2InputTraceRegistry.MAX_JOURNAL_BYTES + 1, f.journal.length());
    }

    @Test public void excessiveLinesAndRecursiveJournalContentAreRefusedWithBoundedParsing() throws Exception {
        String many = HEADER + new String(new char[Pd2InputTraceRegistry.MAX_LINES]).replace('\0', '\n');
        Fixture f = fixture(many);
        try { Pd2InputTraceRegistry.apply(f.hive, f.journal); fail(); }
        catch (IOException expected) { }
        assertEquals(many, read(f.hive));
        assertFalse(f.journal.exists());
        write(f.hive, HEADER + KEY);
        for (String invalid : new String[]{"{\"prior\":{\"nested\":{\"value\":{}}}}", "{\"prior\":[]}"}) {
            write(f.journal, invalid);
            try { Pd2InputTraceRegistry.restore(f.hive, f.journal); fail(); }
            catch (IOException expected) { }
            assertEquals(HEADER + KEY, read(f.hive));
            assertEquals(invalid, read(f.journal));
        }
    }

    private Fixture fixture(String original) throws Exception {
        File parent = directory.newFolder();
        File hive = new File(parent, "user.reg"), journal = new File(parent, "input-trace-journal.json");
        write(hive, original);
        return new Fixture(hive, journal);
    }
    private static void write(File file, String text) throws Exception { Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static Pd2WineSession.Result cleanup(String phase, boolean pass) { return new Pd2WineSession.Result(phase, pass ? 0 : -1, 0, true, 1, ""); }
    private static void assertNoStaging(Fixture f) {
        File[] files = f.hive.getParentFile().listFiles((dir, name) -> name.startsWith(".pd2-input-trace-"));
        assertNotNull(files); assertEquals(0, files.length);
    }
    private static final class Fixture {
        final File hive, journal;
        Fixture(File hive, File journal) { this.hive = hive; this.journal = journal; }
    }
}
