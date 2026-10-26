package com.winlator.pd2;

import android.app.Application;

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

/** Exact-image opt-in, donor preservation and rollback using the actual installer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2XInputReaderRuntimeTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test public void preparationRequiresSuccessfulBeforeLaunchCleanupBeforeContextOrAssetAccess() throws Exception {
        Pd2XInputReaderRuntime.requireCleanup(cleanup("beforeLaunch", 0, 0, true, ""));
        Pd2XInputReaderRuntime.requireCleanup(cleanup("beforeLaunch", 1, 0, true, ""));
        for (Pd2WineSession.Result invalid : new Pd2WineSession.Result[]{null,
                cleanup("afterStop", 0, 0, true, ""), cleanup("beforeLaunch", -1, 0, true, ""),
                cleanup("beforeLaunch", 0, 1, true, ""), cleanup("beforeLaunch", 0, 0, false, ""),
                cleanup("beforeLaunch", 0, 0, true, "Unverified processes")}) {
            for (boolean enabled : new boolean[]{false, true}) {
                try {
                    Pd2XInputReaderRuntime.prepareAfterCleanup(null, invalid, enabled);
                    fail("Cleanup must pass before accessing runtime paths or assets");
                } catch (IOException expected) { assertTrue(expected.getMessage().contains("cleanup")); }
            }
        }
    }

    @Test public void optInPreservesDifferentSizedDonorAndDoesNotRewriteAlreadyInstalledImages() throws Exception {
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

    @Test public void returningToStandardRestoresExactDonorAndKeepsVerifiedBackup() throws Exception {
        Fixture f = fixture();
        install(f, f.patch, hash(f.patch), true);
        Pd2XInputReaderRuntime.install(f.target, f.backup, null, f.patch.length, hash(f.patch),
                f.base.length, hash(f.base), false);
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(f.base, Files.readAllBytes(f.backup.toPath()));
        long modified = f.target.lastModified();
        install(f, f.patch, hash(f.patch), false);
        assertEquals(modified, f.target.lastModified());
        assertNoStaging(f);
    }

    @Test public void standardUntouchedDonorDoesNotReadPayloadOrTouchUnusedBackup() throws Exception {
        Fixture f = fixture();
        byte[] unrelated = image(512, (byte)9);
        Files.write(f.backup.toPath(), unrelated);
        Pd2XInputReaderRuntime.install(f.target, f.backup, unreadable(), f.patch.length, hash(f.patch),
                f.base.length, hash(f.base), false);
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(unrelated, Files.readAllBytes(f.backup.toPath()));
        assertNoStaging(f);
    }

    @Test public void unknownInstalledImageIsPreservedInBothModes() throws Exception {
        Fixture f = fixture();
        byte[] unknown = image(512, (byte)9);
        Files.write(f.target.toPath(), unknown);
        for (boolean enabled : new boolean[]{false, true}) {
            assertRejected(f, f.patch, hash(f.patch), enabled, "accepted Wine runtime");
        }
        assertArrayEquals(unknown, Files.readAllBytes(f.target.toPath()));
        assertFalse(f.backup.exists());
        assertNoStaging(f);
    }

    @Test public void unknownBackupPreventsOptInAndIsNeverReplaced() throws Exception {
        Fixture f = fixture();
        byte[] unknown = image(512, (byte)9);
        Files.write(f.backup.toPath(), unknown);
        assertRejected(f, f.patch, hash(f.patch), true, "backup cannot be verified");
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(unknown, Files.readAllBytes(f.backup.toPath()));
        assertNoStaging(f);
    }

    @Test public void activeExperimentAlwaysRequiresVerifiedOriginalForReuseOrRollback() throws Exception {
        Fixture f = fixture();
        install(f, f.patch, hash(f.patch), true);
        assertTrue(f.backup.delete());
        for (boolean enabled : new boolean[]{false, true}) {
            assertRejected(f, f.patch, hash(f.patch), enabled, "backup is unavailable");
        }
        Files.write(f.backup.toPath(), image(512, (byte)9));
        for (boolean enabled : new boolean[]{false, true}) {
            assertRejected(f, f.patch, hash(f.patch), enabled, "backup cannot be verified");
        }
        assertArrayEquals(f.patch, Files.readAllBytes(f.target.toPath()));
        assertNoStaging(f);
    }

    @Test public void wrongHashTruncatedAndExtraPayloadLeaveDonorIntact() throws Exception {
        Fixture f = fixture();
        byte[] corrupt = f.patch.clone(); corrupt[corrupt.length - 1] ^= 1;
        assertRejected(f, corrupt, hash(f.patch), true, "image verification failed");
        assertRejected(f, Arrays.copyOf(f.patch, f.patch.length - 1), hash(f.patch), true, "image verification failed");
        assertRejected(f, Arrays.copyOf(f.patch, f.patch.length + 1), hash(f.patch), true, "size limit");
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(f.base, Files.readAllBytes(f.backup.toPath()));
        assertNoStaging(f);
    }

    @Test public void hashMatchedPayloadStillNeedsI386BuiltinDllHeaders() throws Exception {
        Fixture f = fixture();
        for (int offset : new int[]{0, 64, 0x80, 0x84, 0x98}) {
            byte[] wrong = f.patch.clone(); wrong[offset] ^= 1;
            assertRejected(f, wrong, hash(wrong), true, "image verification failed");
        }
        byte[] wrongMachine = f.patch.clone(); wrongMachine[0x84] = 0x64; wrongMachine[0x85] = (byte)0x86;
        assertRejected(f, wrongMachine, hash(wrongMachine), true, "image verification failed");
        byte[] notDll = f.patch.clone(); notDll[0x97] = 0;
        assertRejected(f, notDll, hash(notDll), true, "image verification failed");
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertNoStaging(f);
    }

    @Test public void invalidPeOffsetsAndSectionTableCannotOverflowValidation() throws Exception {
        Fixture f = fixture();
        byte[][] invalid = {f.patch.clone(), f.patch.clone(), f.patch.clone()};
        Arrays.fill(invalid[0], 0x3c, 0x40, (byte)0xff);
        invalid[1][0x86] = 97;
        invalid[2][0x94] = (byte)0xff; invalid[2][0x95] = (byte)0xff;
        for (byte[] wrong : invalid) assertRejected(f, wrong, hash(wrong), true, "image verification failed");
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertNoStaging(f);
    }

    @Test public void symbolicTargetAndLinkedParentAreNeverFollowed() throws Exception {
        Fixture f = fixture();
        File outside = directory.newFile("outside.dll");
        Files.write(outside.toPath(), f.base);
        Files.delete(f.target.toPath());
        Files.createSymbolicLink(f.target.toPath(), outside.toPath());
        assertRejected(f, f.patch, hash(f.patch), true, "symbolic link");
        assertTrue(Files.isSymbolicLink(f.target.toPath()));
        assertArrayEquals(f.base, Files.readAllBytes(outside.toPath()));
        Files.delete(f.target.toPath()); Files.write(f.target.toPath(), f.base);
        File linked = new File(directory.getRoot(), "linked");
        Files.createSymbolicLink(linked.toPath(), f.target.getParentFile().toPath());
        try {
            Pd2XInputReaderRuntime.install(new File(linked, f.target.getName()), f.backup,
                    new ByteArrayInputStream(f.patch), f.patch.length, hash(f.patch), f.base.length, hash(f.base), true);
            fail("Linked parent must be preserved");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("symbolic link")); }
        assertFalse(f.backup.exists());
        assertNoStaging(f);
    }

    @Test public void danglingBackupLinkAndSameFileBackupCannotBeOverwritten() throws Exception {
        Fixture f = fixture();
        Files.createSymbolicLink(f.backup.toPath(), new File(directory.getRoot(), "missing.dll").toPath());
        assertRejected(f, f.patch, hash(f.patch), true, "symbolic link");
        assertTrue(Files.isSymbolicLink(f.backup.toPath()));
        try {
            Pd2XInputReaderRuntime.install(f.target, f.target, new ByteArrayInputStream(f.patch),
                    f.patch.length, hash(f.patch), f.base.length, hash(f.base), true);
            fail("Donor and backup must be distinct paths");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("separate files")); }
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertNoStaging(f);
    }

    @Test public void targetChangedDuringPayloadReadIsNotOverwritten() throws Exception {
        Fixture f = fixture();
        byte[] unknown = image(512, (byte)9);
        InputStream changing = changingPayload(f.patch, f.target, unknown);
        try {
            Pd2XInputReaderRuntime.install(f.target, f.backup, changing, f.patch.length,
                    hash(f.patch), f.base.length, hash(f.base), true);
            fail("A changed destination must be kept");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("changed during installation")); }
        assertArrayEquals(unknown, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(f.base, Files.readAllBytes(f.backup.toPath()));
        assertNoStaging(f);
    }

    @Test public void backupChangedDuringPayloadReadCannotPublishAnUnrestorableExperiment() throws Exception {
        Fixture f = fixture();
        byte[] unknown = image(512, (byte)9);
        try {
            Pd2XInputReaderRuntime.install(f.target, f.backup, changingPayload(f.patch, f.backup, unknown),
                    f.patch.length, hash(f.patch), f.base.length, hash(f.base), true);
            fail("Publication must recheck the saved donor");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("backup cannot be verified")); }
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(unknown, Files.readAllBytes(f.backup.toPath()));
        assertNoStaging(f);
    }

    @Test public void invalidMetadataAndMissingOrOversizedTargetsCannotCreateBackup() throws Exception {
        Fixture f = fixture();
        for (long size : new long[]{-1, 255, Pd2XInputReaderRuntime.MAX_MODULE_BYTES + 1}) {
            try {
                Pd2XInputReaderRuntime.install(f.target, f.backup, unreadable(), size, hash(f.patch),
                        f.base.length, hash(f.base), true);
                fail("Invalid metadata must fail before payload or backup access");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("metadata")); }
        }
        for (String digest : new String[]{null, "", "sha256", hash(f.patch).toUpperCase(Locale.ROOT), hash(f.base)}) {
            try {
                Pd2XInputReaderRuntime.install(f.target, f.backup, unreadable(), f.patch.length, digest,
                        f.base.length, hash(f.base), true);
                fail("Invalid or identical image hashes must be rejected");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("metadata")); }
        }
        assertFalse(f.backup.exists());
        Files.delete(f.target.toPath());
        assertRejected(f, f.patch, hash(f.patch), true, "expected size");
        Files.write(f.target.toPath(), new byte[Pd2XInputReaderRuntime.MAX_MODULE_BYTES + 1]);
        assertRejected(f, f.patch, hash(f.patch), true, "expected size");
        assertFalse(f.backup.exists());
        assertNoStaging(f);
    }

    @Test public void packagedManifestMustMatchPinnedImageMetadata() throws Exception {
        JSONObject accepted;
        try (InputStream input = RuntimeEnvironment.getApplication().getAssets().open("pd2/xinput-reader/manifest.json")) {
            accepted = Pd2XInputReaderRuntime.manifest(input);
        }
        assertEquals(Pd2XInputReaderRuntime.REVISION, accepted.getString("revision"));
        for (String key : new String[]{"revision", "asset", "dllname", "machine", "base_sha256", "sha256", "bytes"}) {
            JSONObject changed = new JSONObject(accepted.toString());
            changed.put(key, key.equals("bytes") ? -1 : "unexpected");
            assertManifestRejected(changed.toString());
        }
        assertManifestRejected("{\"revision\":");
        char[] huge = new char[16 * 1024 + 1]; Arrays.fill(huge, ' ');
        assertManifestRejected(new String(huge));
    }

    private static Pd2WineSession.Result cleanup(String phase, int kill, int wait, boolean free, String error) {
        return new Pd2WineSession.Result(phase, kill, wait, free, 1, error);
    }

    private static InputStream unreadable() {
        return new InputStream() {
            @Override public int read() { throw new AssertionError("Payload must not be read"); }
        };
    }

    private static InputStream changingPayload(byte[] payload, File file, byte[] replacement) {
        return new ByteArrayInputStream(payload) {
            @Override public synchronized int read(byte[] bytes, int offset, int count) {
                try { Files.write(file.toPath(), replacement); }
                catch (IOException failure) { throw new IllegalStateException(failure); }
                return super.read(bytes, offset, count);
            }
        };
    }

    private Fixture fixture() throws Exception {
        File target = new File(directory.newFolder(), "xinput1_4.dll");
        File backup = new File(directory.newFolder(), "original.dll");
        byte[] base = image(512, (byte)1), patch = image(768, (byte)2);
        Files.write(target.toPath(), base);
        return new Fixture(target, backup, base, patch);
    }

    private static void install(Fixture f, byte[] payload, String expected, boolean enabled) throws Exception {
        Pd2XInputReaderRuntime.install(f.target, f.backup, new ByteArrayInputStream(payload),
                f.patch.length, expected, f.base.length, hash(f.base), enabled);
    }

    private static void assertRejected(Fixture f, byte[] payload, String expected, boolean enabled,
                                       String message) throws Exception {
        try { install(f, payload, expected, enabled); fail("Unverified XInput DLL must be rejected"); }
        catch (IOException failure) { assertTrue(failure.getMessage(), failure.getMessage().contains(message)); }
    }

    private static void assertNoStaging(Fixture f) {
        for (File parent : new File[]{f.target.getParentFile(), f.backup.getParentFile()}) {
            File[] files = parent.listFiles((dir, name) -> name.startsWith(".pd2-xinput-reader-"));
            assertNotNull(files); assertEquals(0, files.length);
        }
    }

    private static void assertManifestRejected(String json) throws Exception {
        try {
            Pd2XInputReaderRuntime.manifest(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            fail("Unverified manifest must not select a runtime image");
        } catch (IOException expected) {}
    }

    private static byte[] image(int size, byte value) {
        byte[] bytes = new byte[size];
        bytes[0] = 'M'; bytes[1] = 'Z'; bytes[0x3c] = (byte)0x80;
        byte[] marker = "Wine builtin DLL\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, bytes, 64, marker.length);
        bytes[0x80] = 'P'; bytes[0x81] = 'E';
        bytes[0x84] = 0x4c; bytes[0x85] = 1; bytes[0x86] = 1;
        bytes[0x94] = (byte)0xe0; bytes[0x97] = 0x20;
        bytes[0x98] = 0x0b; bytes[0x99] = 1;
        bytes[size - 1] = value;
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
