package com.winlator.pd2;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Run with scripts/test-import.sh; fixtures contain headers only, never game assets. */
public final class Pd2ImportTests {
    private static int checks;
    private interface Throwing { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("pd2-import-tests-").toFile();
        try {
            pathGuards();
            File installed = new File(work, "nested");
            fixture(new File(installed, "My Diablo II"));
            Pd2InstallValidator.Result result = Pd2InstallValidator.validate(installed);
            check(result.valid, "nested full installation is accepted: " + result.message);
            check(result.gameExecutableRelativePath.equals("My Diablo II/ProjectD2/Game.exe"), "nested executable path is correct");
            check(result.clientRootRelativePath.equals("My Diablo II"), "nested root path is correct");

            File partial = new File(work, "partial/ProjectD2"); partial.mkdirs();
            writePe(new File(partial, "Game.exe"), 0x014c, 0x010b);
            check(!Pd2InstallValidator.validate(partial.getParentFile()).valid, "incomplete mod is rejected");
            writePe(new File(installed, "My Diablo II/ProjectD2/Game.exe"), 0x8664, 0x020b);
            check(!Pd2InstallValidator.validate(installed).valid, "64-bit Game.exe is rejected");
            writePe(new File(installed, "My Diablo II/ProjectD2/Game.exe"), 0xaa64, 0x020b);
            check(!Pd2InstallValidator.validate(installed).valid, "ARM64 Game.exe is rejected");
            writePe(new File(installed, "My Diablo II/ProjectD2/Game.exe"), 0x014c, 0x010b);
            new File(installed, "My Diablo II/d2exp.mpq").delete();
            check(!Pd2InstallValidator.validate(installed).valid, "missing LoD archive is rejected");
            fixture(new File(installed, "My Diablo II"));
            fixture(new File(installed, "Another installation"));
            check(!Pd2InstallValidator.validate(installed).valid, "ambiguous complete installations are rejected");

            File archive = zip(work, "safe.zip", "nested/readme.txt", "safe");
            File extracted = new File(work, "safe-output"); extracted.mkdirs();
            Pd2ImportIO.extractZip(archive, extracted, null);
            check(Files.readString(new File(extracted, "nested/readme.txt").toPath()).equals("safe"), "ZIP files stream into nested folders");
            File zip64 = asZip64(archive, new File(work, "zip64.zip"));
            File output64 = new File(work, "zip64-output"); output64.mkdirs();
            Pd2ImportIO.extractZip(zip64, output64, null);
            check(new File(output64, "nested/readme.txt").isFile(), "ZIP64 archive accepted with bounded metadata");
            for (String path : new String[]{"../escape.txt", "/absolute.txt", "C:/escape.txt", "folder\\escape.txt"}) {
                File unsafe = zip(work, "unsafe-" + checks + ".zip", path, "unsafe");
                File output = new File(work, "unsafe-output-" + checks); output.mkdirs();
                rejects(() -> Pd2ImportIO.extractZip(unsafe, output, null), "archive path rejected: " + path);
            }
            File links = zip(work, "symlink.zip", "dangerous-link", "../outside");
            patchCentral(links, 38, 4, 0120777L << 16);
            rejects(() -> Pd2ImportIO.extractZip(links, extracted, null), "ZIP symlinks are rejected before extraction");
            check(!new File(extracted, "dangerous-link").exists(), "rejected symlink was not materialized");

            File crc = zip(work, "crc.zip", "crc.txt", "checksum");
            patchCentral(crc, 16, 4, 0);
            rejects(() -> Pd2ImportIO.extractZip(crc, extracted, null), "wrong ZIP CRC is rejected");
            File encrypted = zip(work, "encrypted.zip", "encrypted.txt", "payload");
            patchCentral(encrypted, 8, 2, 1);
            rejects(() -> Pd2ImportIO.extractZip(encrypted, extracted, null), "encrypted ZIP is rejected");
            File collision = new File(work, "collision.zip");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(collision.toPath()))) {
                out.putNextEntry(new ZipEntry("Game.exe")); out.write(1); out.closeEntry();
                out.putNextEntry(new ZipEntry("game.exe")); out.write(2); out.closeEntry();
            }
            rejects(() -> Pd2ImportIO.extractZip(collision, extracted, null), "case-colliding ZIP paths are rejected");
            Pd2ImportIO.Budget oversized = new Pd2ImportIO.Budget(extracted, null);
            rejects(() -> oversized.copy(new ByteArrayInputStream(new byte[]{1}), new File(extracted, "oversized"),
                    "oversized", Pd2ImportIO.MAX_FILE_BYTES + 1, -1), "declared oversized file rejected before allocation/copy");
            rejects(() -> new Pd2ImportIO.Budget(extracted, null).copy(new ByteArrayInputStream(new byte[]{1}),
                    new File(extracted, "short"), "short", 2, -1), "truncated source file rejected");

            Path outside = new File(work, "outside-target").toPath(); Files.write(outside, new byte[]{1});
            File linkedInstall = new File(work, "linked-install"); fixture(linkedInstall);
            Files.createSymbolicLink(new File(linkedInstall, "outside").toPath(), outside);
            check(!Pd2InstallValidator.validate(linkedInstall).valid, "filesystem symbolic link rejected");
            Pd2ImportIO.deleteTree(linkedInstall);
            check(Files.exists(outside), "cleanup does not follow symbolic links");
            promotionRecovery(work);
            System.out.println("PD2 import tests passed: " + checks + " checks");
        } finally { Pd2ImportIO.deleteTree(work); }
    }

    private static void promotionRecovery(File work) throws Exception {
        File storage = new File(work, "promotion"); storage.mkdirs();
        File install = new File(storage, "install"); fixture(install);
        Files.write(new File(install, "original-save.d2s").toPath(), new byte[]{7});
        File invalid = new File(storage, "invalid"); invalid.mkdirs();
        rejects(() -> Pd2ImportIO.promoteValidated(invalid, storage), "failed validation does not replace active installation");
        check(new File(install, "original-save.d2s").isFile(), "original save survives failed import");
        File replacement = new File(storage, "replacement"); fixture(replacement);
        Files.write(new File(replacement, "replacement.txt").toPath(), new byte[]{8});
        Pd2ImportIO.promoteValidated(replacement, storage);
        check(new File(install, "replacement.txt").isFile(), "validated candidate promoted");
        check(new File(storage, "install.previous/original-save.d2s").isFile(), "previous complete installation retained");
        Pd2ImportIO.deleteTree(install);
        Pd2ImportIO.recoverInstallation(storage);
        check(new File(install, "original-save.d2s").isFile(), "interrupted promotion restores previous installation");
    }

    private static void pathGuards() throws Exception {
        for (String path : new String[]{"../x", "a/../../x", "/x", "a\\x", "C:/x", "a//b", "a/./b",
                "a/..", "NUL.txt", "CON", "LPT1", "name.", "name ", "bad\u0000file"})
            rejects(() -> Pd2ImportIO.requireSafePath(path), "path rejected: " + path);
        check(Pd2ImportIO.requireSafePath("Diablo II/ProjectD2/Game.exe").equals("Diablo II/ProjectD2/Game.exe"), "valid Windows path accepted");
        Pd2ImportIO.PathRegistry paths = new Pd2ImportIO.PathRegistry();
        paths.reserve("ProjectD2/Game.exe", false);
        rejects(() -> paths.reserve("projectd2/game.exe", false), "case collision rejected");
        rejects(() -> paths.reserve("ProjectD2/Game.exe", false), "duplicate file rejected");
        rejects(() -> paths.reserve("ProjectD2/Game.exe/subfolder", true), "file/directory collision rejected");
        Pd2ImportIO.PathRegistry unicode = new Pd2ImportIO.PathRegistry();
        unicode.reserve("caf\u00e9.txt", false);
        rejects(() -> unicode.reserve("cafe\u0301.txt", false), "Unicode normalization collision rejected");
    }

    private static void fixture(File root) throws IOException {
        File mod = new File(root, "ProjectD2"); mod.mkdirs();
        writePe(new File(mod, "Game.exe"), 0x014c, 0x010b);
        writePe(new File(mod, "ProjectDiablo.dll"), 0x014c, 0x010b);
        for (String name : new String[]{"d2data.mpq", "d2char.mpq", "d2sfx.mpq", "d2exp.mpq"}) writeMpq(new File(root, name));
        writeMpq(new File(mod, "pd2data.mpq"));
    }

    private static void writeMpq(File file) throws IOException {
        byte[] bytes = new byte[32]; bytes[0] = 'M'; bytes[1] = 'P'; bytes[2] = 'Q'; bytes[3] = 0x1a;
        Files.write(file.toPath(), bytes);
    }

    private static void writePe(File file, int architecture, int magic) throws IOException {
        byte[] bytes = new byte[384]; bytes[0] = 'M'; bytes[1] = 'Z'; bytes[0x3c] = 0x40;
        bytes[0x40] = 'P'; bytes[0x41] = 'E';
        put16(bytes, 0x44, architecture); put16(bytes, 0x54, 0xe0); put16(bytes, 0x58, magic);
        Files.write(file.toPath(), bytes);
    }

    private static void put16(byte[] target, int offset, int value) { target[offset] = (byte) value; target[offset + 1] = (byte) (value >>> 8); }

    private static File zip(File root, String filename, String path, String content) throws IOException {
        File file = new File(root, filename);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            output.putNextEntry(new ZipEntry(path)); output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
        }
        return file;
    }

    private static File asZip64(File archive, File destination) throws IOException {
        byte[] original = Files.readAllBytes(archive.toPath());
        int eocd = original.length - 22;
        ByteBuffer old = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
        int count = Short.toUnsignedInt(old.getShort(eocd + 10));
        long size = Integer.toUnsignedLong(old.getInt(eocd + 12));
        long start = Integer.toUnsignedLong(old.getInt(eocd + 16));
        ByteBuffer updated = ByteBuffer.allocate(original.length + 76).order(ByteOrder.LITTLE_ENDIAN);
        updated.put(original, 0, eocd);
        updated.putInt(0x06064b50).putLong(44).putShort((short)45).putShort((short)45);
        updated.putInt(0).putInt(0).putLong(count).putLong(count).putLong(size).putLong(start);
        updated.putInt(0x07064b50).putInt(0).putLong(eocd).putInt(1);
        updated.putInt(0x06054b50).putShort((short)0).putShort((short)0);
        updated.putShort((short)65535).putShort((short)65535).putInt(-1).putInt(-1).putShort((short)0);
        Files.write(destination.toPath(), updated.array());
        return destination;
    }

    private static void patchCentral(File zip, int relative, int size, long value) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(zip, "rw")) {
            for (long offset = 0; offset < file.length() - 4; offset++) {
                file.seek(offset);
                if (file.readInt() == 0x504b0102) {
                    file.seek(offset + relative);
                    for (int i = 0; i < size; i++) file.write((int) (value >>> (i * 8)) & 255);
                    return;
                }
            }
            throw new IOException("No ZIP central record");
        }
    }

    private static void rejects(Throwing action, String description) throws Exception {
        try { action.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError(description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
