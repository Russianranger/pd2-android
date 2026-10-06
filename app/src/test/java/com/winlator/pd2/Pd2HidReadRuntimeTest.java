package com.winlator.pd2;

import android.app.Application;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.Assert.*;

/** Exercise the real installer, preflight requirement and exact-image rollback. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2HidReadRuntimeTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test public void experimentalDriverIsOffUntilExplicitlySelected() {
        Application context = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        assertFalse(Pd2HidReadRuntime.enabled(context));
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(Pd2HidReadRuntime.ENABLED_PREFERENCE, true).commit();
        assertTrue(Pd2HidReadRuntime.enabled(context));
    }

    @Test public void onlySuccessfulBeforeLaunchCleanupAllowsPreparation() throws Exception {
        Pd2HidReadRuntime.requireCleanup(cleanup("beforeLaunch", 0, 0, true, ""));
        Pd2HidReadRuntime.requireCleanup(cleanup("beforeLaunch", 1, 0, true, ""));
        for (Pd2WineSession.Result result : new Pd2WineSession.Result[]{null,
                cleanup("afterStop", 0, 0, true, ""), cleanup("beforeLaunch", -1, 0, true, ""),
                cleanup("beforeLaunch", 0, 1, true, ""), cleanup("beforeLaunch", 0, 0, false, ""),
                cleanup("beforeLaunch", 0, 0, true, "Unverified processes")}) {
            try { Pd2HidReadRuntime.requireCleanup(result); fail("Cleanup must pass before mutation"); }
            catch (IOException expected) { }
            try {
                Pd2HidReadRuntime.prepareAfterCleanup(null, result);
                fail("Rejected cleanup must stop before accessing the runtime or assets");
            } catch (IOException expected) { }
        }
    }

    @Test public void firstUpgradeKeepsTheExactOriginalAndDoesNotTouchSaves() throws Exception {
        Fixture f = fixture();
        File save = directory.newFile("character.d2s");
        Files.write(save.toPath(), new byte[]{9, 8, 7});
        install(f, f.patch, hash(f.patch), true);
        assertArrayEquals(f.patch, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(f.base, Files.readAllBytes(f.backup.toPath()));
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(save.toPath()));
        long targetModified = f.target.lastModified(), backupModified = f.backup.lastModified();
        install(f, f.patch, hash(f.patch), true);
        assertEquals(targetModified, f.target.lastModified());
        assertEquals(backupModified, f.backup.lastModified());
        assertNoStaging(f);
    }

    @Test public void optingOutRestoresTheExactBaselineAndKeepsItsBackup() throws Exception {
        Fixture f = fixture();
        install(f, f.patch, hash(f.patch), true);
        install(f, f.patch, hash(f.patch), false);
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(f.base, Files.readAllBytes(f.backup.toPath()));
        long modified = f.target.lastModified();
        install(f, f.patch, hash(f.patch), false);
        assertEquals(modified, f.target.lastModified());
        assertNoStaging(f);
    }

    @Test public void verifiedExistingOriginalIsKeptDuringUpgrade() throws Exception {
        Fixture f = fixture();
        Files.write(f.backup.toPath(), f.base);
        long modified = f.backup.lastModified();
        install(f, f.patch, hash(f.patch), true);
        assertEquals(modified, f.backup.lastModified());
        assertArrayEquals(f.base, Files.readAllBytes(f.backup.toPath()));
        assertNoStaging(f);
    }

    @Test public void unknownInstalledDriverIsKeptEvenWhenRecoveryIsOff() throws Exception {
        Fixture f = fixture();
        byte[] unknown = pe((byte)9);
        Files.write(f.target.toPath(), unknown);
        for (boolean enabled : new boolean[]{false, true}) assertRejected(f, f.patch, hash(f.patch), enabled);
        assertArrayEquals(unknown, Files.readAllBytes(f.target.toPath()));
        assertFalse(f.backup.exists());
        assertNoStaging(f);
    }

    @Test public void anUnknownBackupIsNeverReplacedWithAnotherOriginal() throws Exception {
        Fixture f = fixture();
        byte[] unknown = pe((byte)9);
        Files.write(f.backup.toPath(), unknown);
        install(f, f.patch, hash(f.patch), false);
        assertRejected(f, f.patch, hash(f.patch), true);
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(unknown, Files.readAllBytes(f.backup.toPath()));
        assertNoStaging(f);
    }

    @Test public void patchedDriverRequiresVerifiedOriginalEvenIfAlreadyEnabled() throws Exception {
        Fixture f = fixture();
        install(f, f.patch, hash(f.patch), true);
        for (boolean missing : new boolean[]{true, false}) {
            if (missing) assertTrue(f.backup.delete());
            else Files.write(f.backup.toPath(), pe((byte)9));
            for (boolean enable : new boolean[]{false, true}) assertRejected(f, f.patch, hash(f.patch), enable);
            assertArrayEquals(f.patch, Files.readAllBytes(f.target.toPath()));
        }
        assertNoStaging(f);
    }

    @Test public void badHashTruncatedAndExtraPayloadCannotReplaceBaseline() throws Exception {
        Fixture f = fixture();
        byte[] corrupt = f.patch.clone(); corrupt[511] ^= 1;
        assertRejected(f, corrupt, hash(f.patch), true);
        assertRejected(f, Arrays.copyOf(f.patch, 511), hash(f.patch), true);
        assertRejected(f, Arrays.copyOf(f.patch, 513), hash(f.patch), true);
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertNoStaging(f);
    }

    @Test public void hashMatchedPayloadStillRequiresPeAmd64Headers() throws Exception {
        Fixture f = fixture();
        for (int offset : new int[]{0, 0x80, 0x84, 0x98}) {
            byte[] wrong = f.patch.clone(); wrong[offset] ^= 1;
            assertRejected(f, wrong, hash(wrong), true);
        }
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertNoStaging(f);
    }

    @Test public void invalidPeOffsetsAndSectionTableAreRejectedWithoutOverflow() throws Exception {
        Fixture f = fixture();
        byte[][] invalid = {f.patch.clone(), f.patch.clone(), f.patch.clone()};
        Arrays.fill(invalid[0], 0x3c, 0x40, (byte)0xff);
        invalid[1][0x86] = 97;
        invalid[2][0x94] = (byte)0xff; invalid[2][0x95] = (byte)0xff;
        for (byte[] wrong : invalid) assertRejected(f, wrong, hash(wrong), true);
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertNoStaging(f);
    }

    @Test public void symbolicLinkTargetIsNeverFollowed() throws Exception {
        Fixture f = fixture();
        File outside = directory.newFile("outside.sys");
        Files.write(outside.toPath(), f.base);
        Files.delete(f.target.toPath());
        Files.createSymbolicLink(f.target.toPath(), outside.toPath());
        assertRejected(f, f.patch, hash(f.patch), true);
        assertTrue(Files.isSymbolicLink(f.target.toPath()));
        assertArrayEquals(f.base, Files.readAllBytes(outside.toPath()));
        assertFalse(f.backup.exists());
        assertNoStaging(f);
    }

    @Test public void danglingBackupLinkAndParentDirectoryLinkArePreserved() throws Exception {
        Fixture f = fixture();
        Files.createSymbolicLink(f.backup.toPath(), new File(directory.getRoot(), "missing.sys").toPath());
        install(f, f.patch, hash(f.patch), false);
        assertRejected(f, f.patch, hash(f.patch), true);
        assertTrue(Files.isSymbolicLink(f.backup.toPath()));
        Files.delete(f.backup.toPath());
        File linked = new File(directory.getRoot(), "linked");
        Files.createSymbolicLink(linked.toPath(), f.target.getParentFile().toPath());
        try {
            Pd2HidReadRuntime.install(new File(linked, f.target.getName()), f.backup,
                    new ByteArrayInputStream(f.patch), f.patch.length, hash(f.patch), hash(f.base), true);
            fail("Linked runtime directory must not be mutated");
        } catch (IOException expected) { }
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertNoStaging(f);
    }

    @Test public void targetChangedWhileReadingPayloadIsKept() throws Exception {
        Fixture f = fixture();
        byte[] unknown = pe((byte)9);
        InputStream changing = new ByteArrayInputStream(f.patch) {
            @Override public synchronized int read(byte[] bytes, int offset, int count) {
                try { Files.write(f.target.toPath(), unknown); }
                catch (IOException failure) { throw new IllegalStateException(failure); }
                return super.read(bytes, offset, count);
            }
        };
        try {
            Pd2HidReadRuntime.install(f.target, f.backup, changing, f.patch.length,
                    hash(f.patch), hash(f.base), true);
            fail("A changed destination must not be overwritten");
        } catch (IOException expected) { }
        assertArrayEquals(unknown, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(f.base, Files.readAllBytes(f.backup.toPath()));
        assertNoStaging(f);
    }

    @Test public void oversizedAndMissingRuntimeFilesAreRejectedBeforeBackup() throws Exception {
        Fixture f = fixture();
        Files.write(f.target.toPath(), new byte[Pd2HidReadRuntime.MAX_MODULE_BYTES + 1]);
        assertRejected(f, f.patch, hash(f.patch), true);
        assertEquals(Pd2HidReadRuntime.MAX_MODULE_BYTES + 1, f.target.length());
        assertFalse(f.backup.exists());
        Files.delete(f.target.toPath());
        assertRejected(f, f.patch, hash(f.patch), true);
        assertFalse(f.target.exists());
        assertFalse(f.backup.exists());
        assertNoStaging(f);
    }

    @Test public void invalidImageMetadataCannotReadPayloadOrCreateBackup() throws Exception {
        Fixture f = fixture();
        InputStream unreadable = new InputStream() {
            @Override public int read() { throw new AssertionError("Invalid metadata must not read a payload"); }
        };
        for (long size : new long[]{-1, 255, Pd2HidReadRuntime.MAX_MODULE_BYTES + 1}) {
            try {
                Pd2HidReadRuntime.install(f.target, f.backup, unreadable, size,
                        hash(f.patch), hash(f.base), true);
                fail("Invalid driver size must be rejected");
            } catch (IOException expected) { }
        }
        for (String expectedHash : new String[]{null, "", "sha256", hash(f.patch).toUpperCase(Locale.ROOT)}) {
            try {
                Pd2HidReadRuntime.install(f.target, f.backup, unreadable, f.patch.length,
                        expectedHash, hash(f.base), true);
                fail("Invalid digest metadata must be rejected");
            } catch (IOException expected) { }
        }
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertFalse(f.backup.exists());
        assertNoStaging(f);
    }

    @Test public void optingOutOfUntouchedBaselineDoesNotReadPayloadOrCreateBackup() throws Exception {
        Fixture f = fixture();
        InputStream unreadable = new InputStream() {
            @Override public int read() { throw new AssertionError("Disabled untouched baseline must not read a payload"); }
        };
        Pd2HidReadRuntime.install(f.target, f.backup, unreadable, f.patch.length,
                hash(f.patch), hash(f.base), false);
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertFalse(f.backup.exists());
        assertNoStaging(f);
    }

    @Test public void manifestAcceptsOnlyTheExactAcceptedDriverAndRevision() throws Exception {
        JSONObject accepted = manifest();
        assertEquals(Pd2HidReadRuntime.REVISION, parseManifest(accepted.toString()).getString("revision"));
        for (String key : new String[]{"revision", "asset", "dllname", "machine", "base_sha256", "sha256", "bytes"}) {
            JSONObject changed = manifest();
            changed.put(key, key.equals("bytes") ? 65535 : "unexpected");
            assertManifestRejected(changed.toString());
        }
        assertManifestRejected("{\"revision\":");
        char[] huge = new char[16 * 1024 + 1]; Arrays.fill(huge, ' ');
        assertManifestRejected(new String(huge));
    }

    private static Pd2WineSession.Result cleanup(String phase, int kill, int wait, boolean free, String error) {
        return new Pd2WineSession.Result(phase, kill, wait, free, 1, error);
    }

    private static JSONObject manifest() throws Exception {
        return new JSONObject().put("revision", Pd2HidReadRuntime.REVISION)
                .put("asset", "pd2/hid-read/hidclass.sys").put("dllname", "hidclass.sys")
                .put("machine", "PE32+-AMD64").put("bytes", 65536)
                .put("base_sha256", "a335d3560f14d5b1e31f90fd765ee6261f43a4d70a1f456fbec805ccf18132bc")
                .put("sha256", "def30d1b2b6ada06c0ce04d96a8055c67b2f07f2d10f622960ad259f75a64dcb");
    }

    private static JSONObject parseManifest(String json) throws Exception {
        return Pd2HidReadRuntime.manifest(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertManifestRejected(String json) throws Exception {
        try { parseManifest(json); fail("Unverified manifest must not select a runtime image"); }
        catch (IOException expected) { }
    }

    private Fixture fixture() throws Exception {
        File target = new File(directory.newFolder(), "hidclass.sys");
        File backup = new File(directory.newFolder(), "original.sys");
        byte[] base = pe((byte)1), patch = pe((byte)2);
        Files.write(target.toPath(), base);
        return new Fixture(target, backup, base, patch);
    }

    private static void install(Fixture f, byte[] bytes, String expected, boolean enabled) throws Exception {
        Pd2HidReadRuntime.install(f.target, f.backup, new ByteArrayInputStream(bytes), f.patch.length,
                expected, hash(f.base), enabled);
    }

    private static void assertRejected(Fixture f, byte[] bytes, String expected, boolean enabled) throws Exception {
        try { install(f, bytes, expected, enabled); fail("Unverified driver must be rejected"); }
        catch (IOException expectedFailure) { }
    }

    private static void assertNoStaging(Fixture f) {
        for (File parent : new File[]{f.target.getParentFile(), f.backup.getParentFile()}) {
            File[] files = parent.listFiles((dir, name) -> name.startsWith(".pd2-hid-read-"));
            assertNotNull(files); assertEquals(0, files.length);
        }
    }

    private static byte[] pe(byte value) {
        byte[] bytes = new byte[512];
        bytes[0] = 'M'; bytes[1] = 'Z'; bytes[0x3c] = (byte)0x80;
        bytes[0x80] = 'P'; bytes[0x81] = 'E';
        bytes[0x84] = 0x64; bytes[0x85] = (byte)0x86; bytes[0x86] = 1;
        bytes[0x94] = (byte)0xf0; bytes[0x98] = 0x0b; bytes[0x99] = 2;
        bytes[511] = value;
        return bytes;
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    private static final class Fixture {
        final File target, backup;
        final byte[] base, patch;
        Fixture(File target, File backup, byte[] base, byte[] patch) {
            this.target = target; this.backup = backup; this.base = base; this.patch = patch;
        }
    }
}
