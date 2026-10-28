package com.winlator.pd2;

import android.app.Application;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static com.winlator.pd2.Pd2D2glPolicyTest.bytes;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2D2glConfigTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final String LAUNCH = "fixture-launch-24";

    static final class Fixture {
        File files, game, config;
        byte[] original;
    }
    private Fixture fixture() throws Exception {
        Fixture f = new Fixture();
        f.files = folder.newFolder().getCanonicalFile();
        f.game = new File(f.files, "pd2/install/Diablo/ProjectD2");
        assertTrue(f.game.mkdirs());
        f.config = new File(f.game, "d2gl.json");
        f.original = bytes(Pd2D2glPolicyTest.config());
        Files.write(f.config.toPath(), f.original);
        return f;
    }
    private JSONObject prepare(Fixture f, int fps, boolean light) {
        return Pd2D2glConfig.prepareFiles(f.files, f.game, fps, light, LAUNCH, null);
    }
    private JSONObject prepare(Fixture f, int fps, boolean light, boolean classicCursor) {
        return Pd2D2glConfig.prepareFiles(f.files, f.game, fps, light, classicCursor, LAUNCH, null);
    }
    private JSONObject prepare(Fixture f, int fps, boolean light, boolean classicCursor, boolean simpleScaling) {
        return Pd2D2glConfig.prepareFiles(f.files, f.game, fps, light, classicCursor, simpleScaling, LAUNCH, null);
    }
    private byte[] read(File file) throws Exception { return Files.readAllBytes(file.toPath()); }
    private File state(Fixture f, String name) throws Exception {
        String id = new String(read(new File(f.game, ".pd2-d2gl-installation-id")), StandardCharsets.UTF_8).trim();
        return new File(f.files, "pd2/runtime/d2gl/" + id + "/" + name);
    }
    private JSONObject config(Fixture f) throws Exception { return new JSONObject(new String(read(f.config), StandardCharsets.UTF_8)); }

    @Test public void unsafeCleanupIsRejectedBeforeAnyContextOrPathAccess() throws Exception {
        for (Pd2WineSession.Result cleanup : new Pd2WineSession.Result[]{null,
                new Pd2WineSession.Result("afterStop", 0, 0, true, 0, ""),
                new Pd2WineSession.Result("beforeLaunch", 0, 1, true, 0, ""),
                new Pd2WineSession.Result("beforeLaunch", 0, 0, false, 0, "")}) {
            for (boolean scaling : new boolean[]{false, true}) {
                try { Pd2D2glConfig.prepareAfterCleanup(null, cleanup, null, 60, true, true, scaling, LAUNCH); fail(); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("cleanup")); }
            }
        }
        Pd2D2glConfig.requireCleanup(new Pd2WineSession.Result("beforeLaunch", 1, 0, true, 0, ""));
    }

    @Test public void capAndLightProfileAreAtomicBackedUpAndFullyReversible() throws Exception {
        Fixture f = fixture();
        File save = new File(f.game, "character.d2s"), dll = new File(f.game, "ProjectDiablo.dll");
        Files.write(save.toPath(), new byte[]{1, 2, 3}); Files.write(dll.toPath(), new byte[]{4, 5, 6});
        JSONObject applied = prepare(f, 60, true);
        assertEquals("applied", applied.getString("status"));
        assertTrue(applied.getBoolean("capApplied")); assertTrue(applied.getBoolean("effectsApplied"));
        assertEquals(6, applied.getInt("changedFields"));
        assertArrayEquals(f.original, read(state(f, "original.json")));
        assertFalse(new JSONObject(new String(read(state(f, "journal.json")), StandardCharsets.UTF_8)).getBoolean("pending"));
        assertTrue(new String(read(f.config), StandardCharsets.UTF_8).contains("0.12345678901234567890"));
        JSONObject again = prepare(f, 60, true);
        assertEquals(0, again.getInt("changedFields")); assertTrue(again.getBoolean("capApplied"));
        JSONObject restored = prepare(f, -1, false);
        assertEquals(6, restored.getInt("restoredFields"));
        assertArrayEquals(f.original, read(f.config));
        assertArrayEquals(new byte[]{1, 2, 3}, read(save)); assertArrayEquals(new byte[]{4, 5, 6}, read(dll));
    }

    @Test public void resetPreservesLaterUserEditsAndUnrelatedRawSettings() throws Exception {
        Fixture f = fixture(); prepare(f, 60, true);
        String edited = new String(read(f.config), StandardCharsets.UTF_8)
                .replace("\"foreground_fps_value\": 60", "\"foreground_fps_value\": 90")
                .replace("\"frame_latency\": 3", "\"frame_latency\": 4");
        Files.write(f.config.toPath(), bytes(edited));
        JSONObject restored = prepare(f, -1, false);
        assertEquals(1, restored.getInt("conflictsPreserved"));
        assertEquals(90, config(f).getJSONObject("screen").getInt("foreground_fps_value"));
        assertEquals(4, config(f).getJSONObject("other").getInt("frame_latency"));
        assertTrue(config(f).getJSONObject("graphics").getBoolean("bloom"));
    }

    @Test public void newExplicitRequestRebasesTheValueRestoredToALaterUserChoice() throws Exception {
        Fixture f = fixture(); prepare(f, 60, false);
        Files.write(f.config.toPath(), bytes(new String(read(f.config), StandardCharsets.UTF_8)
                .replace("\"foreground_fps_value\": 60", "\"foreground_fps_value\": 90")));
        assertEquals(1, prepare(f, 30, false).getInt("userValuesRebased"));
        assertEquals(30, config(f).getJSONObject("screen").getInt("foreground_fps_value"));
        prepare(f, -1, false);
        assertEquals(90, config(f).getJSONObject("screen").getInt("foreground_fps_value"));
    }

    @Test public void classicCursorAppliesAndTheOldOverloadRestoresExactlyItsOwnedValue() throws Exception {
        Fixture f = fixture();
        JSONObject applied = prepare(f, -1, false, true);
        assertEquals("applied", applied.getString("status"));
        assertTrue(applied.getBoolean("classicCursorRequested"));
        assertTrue(applied.getBoolean("cursorApplied"));
        assertFalse(applied.getJSONObject("current").getBoolean("features.hd_cursor"));
        assertFalse(applied.getBoolean("capApplied"));
        assertFalse(applied.getBoolean("effectsApplied"));
        assertEquals(1, applied.getInt("changedFields"));
        JSONObject change = applied.getJSONArray("fieldChanges").getJSONObject(0);
        assertEquals("features.hd_cursor", change.getString("field"));
        assertTrue(change.getBoolean("before"));
        assertFalse(change.getBoolean("after"));
        assertArrayEquals(bytes(Pd2D2glPolicyTest.config().replace("\"hd_cursor\": true", "\"hd_cursor\": false")), read(f.config));
        JSONObject restored = prepare(f, -1, false);
        assertEquals(1, restored.getInt("restoredFields"));
        assertFalse(restored.getBoolean("classicCursorRequested"));
        assertFalse(restored.getBoolean("cursorApplied"));
        assertTrue(restored.getJSONObject("current").getBoolean("features.hd_cursor"));
        assertArrayEquals(f.original, read(f.config));
    }

    @Test public void userSelectedClassicCursorIsNeverChangedBackWithoutLauncherOwnership() throws Exception {
        Fixture f = fixture();
        byte[] user = bytes(Pd2D2glPolicyTest.config().replace("\"hd_cursor\": true", "\"hd_cursor\": false"));
        Files.write(f.config.toPath(), user);
        JSONObject applied = prepare(f, -1, false, true);
        assertTrue(applied.getBoolean("cursorApplied"));
        assertEquals(0, applied.getInt("changedFields"));
        assertFalse(new File(f.game, ".pd2-d2gl-installation-id").exists());
        prepare(f, -1, false);
        assertArrayEquals(user, read(f.config));
    }

    @Test public void laterUserCursorValueIsRebasedOrPreservedWithoutCoercion() throws Exception {
        Fixture f = fixture();
        prepare(f, -1, false, true);
        Files.write(f.config.toPath(), f.original);
        assertEquals(1, prepare(f, -1, false, true).getInt("userValuesRebased"));
        prepare(f, -1, false);
        assertArrayEquals(f.original, read(f.config));
        prepare(f, -1, false, true);
        byte[] manual = bytes(new String(read(f.config), StandardCharsets.UTF_8)
                .replace("\"hd_cursor\": false", "\"hd_cursor\": \"custom\""));
        Files.write(f.config.toPath(), manual);
        JSONObject restored = prepare(f, -1, false);
        assertEquals(1, restored.getInt("conflictsPreserved"));
        assertArrayEquals(manual, read(f.config));
    }

    @Test public void unavailableCursorFieldSkipsOnlyCursorAndDoesNotInventASetting() throws Exception {
        for (String value : new String[]{"\"another_cursor\": true", "\"hd_cursor\": \"true\"", "\"hd_cursor\": null"}) {
            Fixture f = fixture();
            byte[] original = bytes(Pd2D2glPolicyTest.config().replace("\"hd_cursor\": true", value));
            Files.write(f.config.toPath(), original);
            JSONObject applied = prepare(f, 60, false, true);
            assertTrue(applied.getBoolean("capApplied"));
            assertFalse(applied.getBoolean("cursorApplied"));
            assertTrue(applied.getString("cursorReason").contains("missing or unrecognized"));
            assertEquals(3, applied.getInt("changedFields"));
            assertFalse(applied.getJSONObject("current").has("features.hd_cursor"));
            prepare(f, -1, false);
            assertArrayEquals(original, read(f.config));
        }
    }

    @Test public void anOwnedCursorBecomingUntypedDoesNotBlockOtherChangesOrGetOverwritten() throws Exception {
        Fixture f = fixture();
        prepare(f, -1, false, true);
        Files.write(f.config.toPath(), bytes(new String(read(f.config), StandardCharsets.UTF_8)
                .replace("\"hd_cursor\": false", "\"hd_cursor\": \"custom\"")));
        JSONObject applied = prepare(f, 60, false, true);
        assertTrue(applied.getBoolean("capApplied"));
        assertFalse(applied.getBoolean("cursorApplied"));
        JSONObject entries = new JSONObject(new String(read(state(f, "journal.json")), StandardCharsets.UTF_8)).getJSONObject("entries");
        assertTrue(entries.has("features.hd_cursor"));
        prepare(f, -1, false);
        assertEquals("custom", config(f).getJSONObject("features").getString("hd_cursor"));
        assertTrue(config(f).getJSONObject("screen").getBoolean("window_vsync"));
    }

    @Test public void sixFieldVersionOneJournalsCanAddCursorAndRestoreAllSevenFields() throws Exception {
        Fixture f = fixture();
        prepare(f, 60, true);
        File journal = state(f, "journal.json");
        JSONObject old = new JSONObject(new String(read(journal), StandardCharsets.UTF_8));
        assertEquals("pd2-d2gl-json-1", old.getString("revision"));
        assertEquals(6, old.getJSONObject("entries").length());
        assertFalse(old.getJSONObject("entries").has("features.hd_cursor"));
        JSONObject applied = prepare(f, 60, true, true);
        assertTrue(applied.getBoolean("cursorApplied"));
        assertEquals(1, applied.getJSONArray("fieldChanges").length());
        JSONObject updated = new JSONObject(new String(read(journal), StandardCharsets.UTF_8));
        assertEquals(old.getString("revision"), updated.getString("revision"));
        assertEquals(7, updated.getJSONObject("entries").length());
        assertArrayEquals(f.original, read(state(f, "original.json")));
        assertEquals(7, prepare(f, -1, false).getInt("restoredFields"));
        assertArrayEquals(f.original, read(f.config));
    }

    @Test public void readOnlySnapshotFieldsCannotBeInjectedIntoOwnershipJournal() throws Exception {
        Fixture f = fixture();
        prepare(f, 60, false);
        byte[] applied = read(f.config);
        File journal = state(f, "journal.json");
        JSONObject saved = new JSONObject(new String(read(journal), StandardCharsets.UTF_8));
        saved.getJSONObject("entries").put("other.frame_latency",
                new JSONObject().put("original", 3).put("applied", 1));
        Files.write(journal.toPath(), bytes(saved.toString()));
        assertEquals("failed", prepare(f, -1, false).getString("status"));
        assertArrayEquals(applied, read(f.config));
    }

    @Test public void simpleScalingChangesOnlyThePresetAndRestoresBackslashValues() throws Exception {
        Fixture f = fixture();
        JSONObject applied = prepare(f, -1, false, false, true);
        assertTrue(applied.getBoolean("simpleScalingRequested"));
        assertTrue(applied.getBoolean("scalingApplied"));
        assertEquals("applied", applied.getString("status"));
        assertFalse(applied.getBoolean("capApplied"));
        assertFalse(applied.getBoolean("effectsApplied"));
        assertFalse(applied.getBoolean("cursorApplied"));
        assertEquals(1, applied.getInt("changedFields"));
        assertEquals("bilinear.slangp", config(f).getJSONObject("graphics").getString("shader_preset"));
        JSONObject change = applied.getJSONArray("fieldChanges").getJSONObject(0);
        assertEquals("graphics.shader_preset", change.getString("field"));
        assertEquals("user\\preset.slangp", change.getString("before"));
        assertEquals("bilinear.slangp", change.getString("after"));
        assertArrayEquals(f.original, read(state(f, "original.json")));
        assertEquals(0, prepare(f, -1, false, false, true).getInt("changedFields"));
        JSONObject restored = prepare(f, -1, false, false);
        assertFalse(restored.getBoolean("simpleScalingRequested"));
        assertFalse(restored.getBoolean("scalingApplied"));
        assertEquals(1, restored.getInt("restoredFields"));
        assertArrayEquals(f.original, read(f.config));
    }

    @Test public void existingBilinearSelectionIsNotClaimedOrChangedOnDisable() throws Exception {
        Fixture f = fixture();
        byte[] original = bytes(Pd2D2glPolicyTest.config().replace("user\\\\preset.slangp", "bilinear.slangp"));
        Files.write(f.config.toPath(), original);
        JSONObject applied = prepare(f, -1, false, false, true);
        assertTrue(applied.getBoolean("scalingApplied"));
        assertEquals(0, applied.getInt("changedFields"));
        assertFalse(new File(f.game, ".pd2-d2gl-installation-id").exists());
        prepare(f, -1, false, false);
        assertArrayEquals(original, read(f.config));
    }

    @Test public void userShaderChangesArePreservedOnDisableAndRebasedOnExplicitReapply() throws Exception {
        Fixture f = fixture();
        prepare(f, -1, false, false, true);
        String manual = new String(read(f.config), StandardCharsets.UTF_8)
                .replace("bilinear.slangp", "fsr\\\\fsr-easu.slangp");
        Files.write(f.config.toPath(), bytes(manual));
        JSONObject disabled = prepare(f, -1, false, false);
        assertEquals(1, disabled.getInt("conflictsPreserved"));
        assertArrayEquals(bytes(manual), read(f.config));
        prepare(f, -1, false, false, true);
        manual = new String(read(f.config), StandardCharsets.UTF_8).replace("bilinear.slangp", "custom/shader.slangp");
        Files.write(f.config.toPath(), bytes(manual));
        assertEquals(1, prepare(f, -1, false, false, true).getInt("userValuesRebased"));
        JSONObject restored = prepare(f, -1, false, false);
        assertEquals(1, restored.getInt("restoredFields"));
        assertEquals("applied", restored.getString("status"));
        assertFalse(restored.getBoolean("scalingApplied"));
        assertEquals("custom/shader.slangp", config(f).getJSONObject("graphics").getString("shader_preset"));
        // Restore the owned string's value using Android's JSON escaping; all other bytes stay exact.
        String expected = manual.replace("\"shader_preset\": \"custom/shader.slangp\"",
                "\"shader_preset\": " + JSONObject.quote("custom/shader.slangp"));
        assertArrayEquals(bytes(expected), read(f.config));
    }

    @Test public void missingUntypedOrUnsafePresetSkipsScalingButAllowsOtherSettings() throws Exception {
        for (String value : new String[]{"\"other_shader\": \"custom.slangp\"", "\"shader_preset\": false",
                "\"shader_preset\": null", "\"shader_preset\": \"../private.slangp\""}) {
            Fixture f = fixture();
            byte[] original = bytes(Pd2D2glPolicyTest.config()
                    .replace("\"shader_preset\": \"user\\\\preset.slangp\"", value));
            Files.write(f.config.toPath(), original);
            JSONObject applied = prepare(f, 60, false, true, true);
            assertTrue(applied.getBoolean("capApplied"));
            assertTrue(applied.getBoolean("cursorApplied"));
            assertFalse(applied.getBoolean("scalingApplied"));
            assertTrue(applied.getString("scalingReason").contains("missing or unrecognized"));
            assertFalse(applied.getJSONObject("current").has("graphics.shader_preset"));
            assertEquals(4, applied.getInt("changedFields"));
            prepare(f, -1, false, false);
            assertArrayEquals(original, read(f.config));
        }
    }

    @Test public void unknownEffectsDoNotSuppressScalingApplyOrRestore() throws Exception {
        Fixture f = fixture();
        byte[] original = bytes(Pd2D2glPolicyTest.config().replace("\"fxaa\": true, ", ""));
        Files.write(f.config.toPath(), original);
        JSONObject applied = prepare(f, -1, true, false, true);
        assertTrue(applied.getBoolean("scalingApplied"));
        assertFalse(applied.getBoolean("effectsApplied"));
        assertEquals(1, applied.getInt("changedFields"));
        assertEquals(1, prepare(f, -1, true, false).getInt("restoredFields"));
        assertArrayEquals(original, read(f.config));
    }

    @Test public void sevenFieldVersionOneJournalCanAddScalingAndRestoreAllEight() throws Exception {
        Fixture f = fixture();
        prepare(f, 60, true, true);
        File journal = state(f, "journal.json");
        JSONObject old = new JSONObject(new String(read(journal), StandardCharsets.UTF_8));
        assertEquals("pd2-d2gl-json-1", old.getString("revision"));
        assertEquals(7, old.getJSONObject("entries").length());
        assertFalse(old.getJSONObject("entries").has("graphics.shader_preset"));
        JSONObject applied = prepare(f, 60, true, true, true);
        assertTrue(applied.getBoolean("scalingApplied"));
        assertEquals(1, applied.getInt("changedFields"));
        JSONObject saved = new JSONObject(new String(read(journal), StandardCharsets.UTF_8));
        assertEquals(old.getString("revision"), saved.getString("revision"));
        assertEquals(8, saved.getJSONObject("entries").length());
        assertEquals(8, prepare(f, -1, false, false).getInt("restoredFields"));
        assertArrayEquals(f.original, read(f.config));
        assertArrayEquals(f.original, read(state(f, "original.json")));
    }

    @Test public void interruptedScalingTransactionRecoversAndKeepsTheOriginal() throws Exception {
        Fixture f = fixture();
        JSONObject interrupted = Pd2D2glConfig.prepareFiles(f.files, f.game, -1, false, false, true, LAUNCH,
                ignored -> { throw new IOException("Fixture scaling interruption"); });
        assertEquals("failed", interrupted.getString("status"));
        assertArrayEquals(f.original, read(f.config));
        assertTrue(prepare(f, -1, false, false, true).getBoolean("scalingApplied"));
        prepare(f, -1, false, false);
        assertArrayEquals(f.original, read(f.config));
    }

    @Test public void anOwnedPresetBecomingUnsafeNeverGetsOverwritten() throws Exception {
        Fixture f = fixture();
        prepare(f, -1, false, false, true);
        byte[] manual = bytes(new String(read(f.config), StandardCharsets.UTF_8)
                .replace("bilinear.slangp", "../user.slangp"));
        Files.write(f.config.toPath(), manual);
        JSONObject applied = prepare(f, -1, false, true, true);
        assertFalse(applied.getBoolean("scalingApplied"));
        assertTrue(applied.getBoolean("cursorApplied"));
        assertTrue(new JSONObject(new String(read(state(f, "journal.json")), StandardCharsets.UTF_8))
                .getJSONObject("entries").has("graphics.shader_preset"));
        prepare(f, -1, false, false);
        assertEquals("../user.slangp", config(f).getJSONObject("graphics").getString("shader_preset"));
    }

    @Test public void unlimitedDisablesTheLimiterWithoutWritingZeroAndFrameRestoreIsIndependent() throws Exception {
        Fixture f = fixture(); prepare(f, 60, true);
        JSONObject unlimited = prepare(f, 0, true);
        assertTrue(unlimited.getBoolean("capApplied"));
        assertFalse(config(f).getJSONObject("screen").getBoolean("foreground_fps_active"));
        assertEquals(60, config(f).getJSONObject("screen").getInt("foreground_fps_value"));
        prepare(f, -1, true);
        assertTrue(config(f).getJSONObject("screen").getBoolean("window_vsync"));
        assertEquals(144, config(f).getJSONObject("screen").getInt("foreground_fps_value"));
        assertFalse(config(f).getJSONObject("graphics").getBoolean("bloom"));
    }

    @Test public void reimportIdentityNeverRestoresAnOldInstallationsValues() throws Exception {
        Fixture f = fixture(); prepare(f, 60, true);
        File oldBackup = state(f, "original.json");
        assertTrue(new File(f.game, ".pd2-d2gl-installation-id").delete());
        byte[] fresh = bytes(Pd2D2glPolicyTest.config().replace("\"foreground_fps_value\": 144", "\"foreground_fps_value\": 90"));
        Files.write(f.config.toPath(), fresh);
        prepare(f, 60, false);
        assertNotEquals(oldBackup.getPath(), state(f, "original.json").getPath());
        prepare(f, -1, false);
        assertArrayEquals(fresh, read(f.config));
        assertArrayEquals(f.original, read(oldBackup));
    }

    @Test public void unsupportedMissingOversizedAndAmbiguousConfigsAreKept() throws Exception {
        Fixture f = fixture();
        Files.write(f.config.toPath(), bytes("{\"screen\":{}}"));
        assertEquals("skipped", prepare(f, 60, true).getString("status"));
        assertArrayEquals(bytes("{\"screen\":{}}"), read(f.config));
        assertFalse(new File(f.game, ".pd2-d2gl-installation-id").exists());
        assertTrue(f.config.delete()); assertEquals("skipped", prepare(f, 60, true).getString("status"));
        Files.write(f.config.toPath(), new byte[Pd2D2glConfig.MAX_CONFIG_BYTES + 1]);
        assertEquals("failed", prepare(f, 60, true).getString("status"));
        Files.write(f.config.toPath(), f.original);
        Files.write(new File(f.game, "D2GL.JSON").toPath(), f.original);
        assertEquals("failed", prepare(f, 60, true).getString("status"));
        assertArrayEquals(f.original, read(f.config));
    }

    @Test public void unknownEffectsSchemaDoesNotPreventRecognizedFrameSettings() throws Exception {
        Fixture f = fixture();
        Files.write(f.config.toPath(), bytes(Pd2D2glPolicyTest.config().replace("\"fxaa\": true, ", "")));
        JSONObject applied = prepare(f, 60, true);
        assertTrue(applied.getBoolean("capApplied")); assertFalse(applied.getBoolean("effectsApplied"));
        assertTrue(applied.has("effectsReason"));
        assertTrue(config(f).getJSONObject("graphics").getBoolean("bloom"));
        assertFalse(config(f).getJSONObject("graphics").has("fxaa"));
    }

    @Test public void symbolicConfigAndTamperedJournalOrBackupCannotOverwriteFiles() throws Exception {
        Fixture f = fixture(); File external = folder.newFile(); Files.write(external.toPath(), f.original);
        assertTrue(f.config.delete()); Files.createSymbolicLink(f.config.toPath(), external.toPath());
        assertEquals("failed", prepare(f, 60, true).getString("status"));
        assertArrayEquals(f.original, read(external));
        Files.delete(f.config.toPath()); Files.write(f.config.toPath(), f.original);
        prepare(f, 60, false); byte[] applied = read(f.config);
        File journal = state(f, "journal.json");
        JSONObject metadata = new JSONObject(new String(read(journal), StandardCharsets.UTF_8));
        metadata.put("gameRelativePath", "pd2/install/some-other/ProjectD2");
        Files.write(journal.toPath(), bytes(metadata.toString()));
        assertEquals("failed", prepare(f, -1, false).getString("status"));
        assertArrayEquals(applied, read(f.config));
        metadata.put("gameRelativePath", "pd2/install/Diablo/ProjectD2");
        Files.write(journal.toPath(), bytes(metadata.toString()));
        Files.write(state(f, "original.json").toPath(), bytes("unknown"));
        assertEquals("failed", prepare(f, -1, false).getString("status"));
        assertArrayEquals(applied, read(f.config));
    }

    @Test public void interruptedBeforeReplacementRecoversWithoutLosingTheOriginal() throws Exception {
        Fixture f = fixture(); prepare(f, 60, false);
        byte[] previous = read(f.config);
        JSONObject interrupted = Pd2D2glConfig.prepareFiles(f.files, f.game, 30, true, LAUNCH,
                ignored -> { throw new IOException("Fixture interruption"); });
        assertEquals("failed", interrupted.getString("status")); assertArrayEquals(previous, read(f.config));
        assertTrue(new JSONObject(new String(read(state(f, "journal.json")), StandardCharsets.UTF_8)).getBoolean("pending"));
        assertTrue(prepare(f, 30, false).getBoolean("capApplied"));
        prepare(f, -1, false); assertArrayEquals(f.original, read(f.config));
    }

    @Test public void interruptedAfterReplacementRecognizesItsOwnBytesOnTheNextLaunch() throws Exception {
        Fixture f = fixture(); prepare(f, 60, true);
        File journal = state(f, "journal.json");
        JSONObject saved = new JSONObject(new String(read(journal), StandardCharsets.UTF_8));
        saved.put("pending", true); Files.write(journal.toPath(), bytes(saved.toString()));
        prepare(f, -1, false); assertArrayEquals(f.original, read(f.config));
    }

    @Test public void concurrentConfigEditIsNotOverwrittenAndAmbiguousRecoveryStaysReadOnly() throws Exception {
        Fixture f = fixture(); byte[] manual = bytes(Pd2D2glPolicyTest.config().replace("\"frame_latency\": 3", "\"frame_latency\": 4"));
        JSONObject result = Pd2D2glConfig.prepareFiles(f.files, f.game, 60, true, LAUNCH,
                target -> Files.write(target.toPath(), manual));
        assertEquals("failed", result.getString("status")); assertArrayEquals(manual, read(f.config));
        assertEquals("skipped", prepare(f, -1, false).getString("status"));
        assertArrayEquals(manual, read(f.config));
        assertArrayEquals(f.original, read(state(f, "original.json")));
    }

    @Test public void inspectionOnlyReadsAllowlistedValuesAndNeverCreatesState() throws Exception {
        Fixture f = fixture();
        JSONObject snapshot = Pd2D2glConfig.inspectFiles(f.files, f.game);
        assertEquals("observed", snapshot.getString("status")); assertTrue(snapshot.getBoolean("schemaRecognized"));
        assertEquals(Pd2D2glConfig.hash(f.original), snapshot.getString("configSha256"));
        assertEquals(11, snapshot.getJSONObject("current").length());
        assertEquals("user\\preset.slangp", snapshot.getJSONObject("current").getString("graphics.shader_preset"));
        assertEquals(1280, snapshot.getJSONObject("current").getInt("screen.window_size_width"));
        assertEquals(720, snapshot.getJSONObject("current").getInt("screen.window_size_height"));
        assertEquals(3, snapshot.getJSONObject("current").getInt("other.frame_latency"));
        assertFalse(snapshot.getJSONObject("current").has("unknown"));
        assertArrayEquals(f.original, read(f.config));
        assertFalse(new File(f.game, ".pd2-d2gl-installation-id").exists());
        assertFalse(new File(f.files, "pd2/runtime").exists());
        File outside = folder.newFolder("external-ProjectD2");
        assertEquals("failed", Pd2D2glConfig.inspectFiles(f.files, outside).getString("status"));
        assertEquals("failed", Pd2D2glConfig.inspectFiles(f.files, null).getString("status"));
    }

    @Test public void trustedAppRootAliasesWorkButImportedChildLinksRemainForbidden() throws Exception {
        Fixture f = fixture();
        File alias = new File(folder.getRoot(), "app-root-alias");
        Files.createSymbolicLink(alias.toPath(), f.files.toPath());
        File captured = new File(alias, "pd2/install/Diablo/ProjectD2");
        assertEquals(f.game, Pd2D2glConfig.normalizeCapturedDirectory(alias, captured));
        File child = new File(f.files, "pd2/install/linked");
        Files.createSymbolicLink(child.toPath(), new File(f.files, "pd2/install/Diablo").toPath());
        try {
            Pd2D2glConfig.normalizeCapturedDirectory(alias, new File(alias, "pd2/install/linked/ProjectD2"));
            fail("Imported subtree links must not be normalized away");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("symbolic link")); }
    }

    @Test public void stringBooleanRecoveryMetadataCannotBeCoercedIntoOwnership() throws Exception {
        Fixture f = fixture(); prepare(f, 60, false);
        File journal = state(f, "journal.json"); byte[] current = read(f.config);
        JSONObject saved = new JSONObject(new String(read(journal), StandardCharsets.UTF_8));
        saved.put("pending", "true"); Files.write(journal.toPath(), bytes(saved.toString()));
        assertEquals("failed", prepare(f, -1, false).getString("status"));
        assertArrayEquals(current, read(f.config));
    }

    @Test public void backupMustStillBeVerifiedAtTheConfigReplacementBoundary() throws Exception {
        Fixture f = fixture();
        JSONObject result = Pd2D2glConfig.prepareFiles(f.files, f.game, 60, true, LAUNCH,
                ignored -> {
                    try { Files.write(state(f, "original.json").toPath(), bytes("changed")); }
                    catch (Exception error) { throw new IOException(error); }
                });
        assertEquals("failed", result.getString("status"));
        assertArrayEquals(f.original, read(f.config));
    }
}
