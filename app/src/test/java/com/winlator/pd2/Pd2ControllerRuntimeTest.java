package com.winlator.pd2;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Exercise the real atomic installer and recovery path with small ELF fixtures. */
public final class Pd2ControllerRuntimeTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test public void upgradesOnlyTheBackendAndKeepsItsOriginalForRollback() throws Exception {
        Fixture f = fixture();
        File save = directory.newFile("character.d2s");
        Files.write(save.toPath(), new byte[]{9, 8, 7});
        install(f, f.patch, hash(f.patch), true);
        assertArrayEquals(f.patch, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(f.base, Files.readAllBytes(f.backup.toPath()));
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(save.toPath()));
        long modified = f.target.lastModified();
        install(f, f.patch, hash(f.patch), true);
        assertEquals(modified, f.target.lastModified());
        assertNoStagingFiles(f);
    }

    @Test public void disablingNotificationsRestoresTheVerifiedOriginal() throws Exception {
        Fixture f = fixture();
        install(f, f.patch, hash(f.patch), true);
        install(f, f.patch, hash(f.patch), false);
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertArrayEquals(f.base, Files.readAllBytes(f.backup.toPath()));
        install(f, f.patch, hash(f.patch), false);
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertNoStagingFiles(f);
    }

    @Test public void corruptedOrTruncatedPayloadCannotReplaceTheWorkingModule() throws Exception {
        Fixture f = fixture();
        byte[] corrupt = f.patch.clone(); corrupt[31] ^= 1;
        assertRejected(f, corrupt, hash(f.patch), true);
        assertRejected(f, Arrays.copyOf(f.patch, 23), hash(f.patch), true);
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertNoStagingFiles(f);
    }

    @Test public void unexpectedInstalledModuleIsPreservedWithoutCreatingABackup() throws Exception {
        Fixture f = fixture();
        byte[] unexpected = elf((byte)7);
        Files.write(f.target.toPath(), unexpected);
        assertRejected(f, f.patch, hash(f.patch), true);
        assertArrayEquals(unexpected, Files.readAllBytes(f.target.toPath()));
        assertFalse(f.backup.exists());
        assertNoStagingFiles(f);
    }

    @Test public void invalidBackupCannotOverwriteTheInstalledReplacement() throws Exception {
        Fixture f = fixture();
        install(f, f.patch, hash(f.patch), true);
        Files.write(f.backup.toPath(), elf((byte)8));
        assertRejected(f, f.patch, hash(f.patch), false);
        assertArrayEquals(f.patch, Files.readAllBytes(f.target.toPath()));
        assertNoStagingFiles(f);
    }

    @Test public void evenAHashMatchedPayloadMustBeAnX8664SharedElfModule() throws Exception {
        Fixture f = fixture();
        for (int offset : new int[]{0, 4, 5, 16, 18}) {
            byte[] wrong = f.patch.clone(); wrong[offset] ^= 1;
            assertRejected(f, wrong, hash(wrong), true);
        }
        assertArrayEquals(f.base, Files.readAllBytes(f.target.toPath()));
        assertNoStagingFiles(f);
    }

    private Fixture fixture() throws Exception {
        File target = directory.newFile("winebus.so");
        File backup = new File(directory.newFolder("backup"), "original.so");
        byte[] base = elf((byte)1), patch = elf((byte)2);
        Files.write(target.toPath(), base);
        return new Fixture(target, backup, base, patch);
    }

    private static void install(Fixture f, byte[] payload, String expected, boolean enable) throws Exception {
        Pd2ControllerRuntime.install(f.target, f.backup, new ByteArrayInputStream(payload), f.patch.length,
                expected, hash(f.base), enable);
    }

    private static void assertRejected(Fixture f, byte[] payload, String expected, boolean enable) throws Exception {
        try { install(f, payload, expected, enable); fail("Unverified module must be rejected"); }
        catch (IOException expectedFailure) { }
    }

    private static void assertNoStagingFiles(Fixture f) {
        for (File parent : new File[]{f.target.getParentFile(), f.backup.getParentFile()}) {
            File[] files = parent.listFiles((dir, name) -> name.startsWith(".pd2-controller-"));
            assertNotNull(files); assertEquals(0, files.length);
        }
    }

    private static byte[] elf(byte value) {
        byte[] data = new byte[32];
        data[0] = 0x7f; data[1] = 'E'; data[2] = 'L'; data[3] = 'F';
        data[4] = 2; data[5] = 1; data[16] = 3; data[18] = 62; data[31] = value;
        return data;
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
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
