package com.winlator.pd2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Android-independent bounded import I/O. Neither archives nor game files are read into RAM. */
public final class Pd2ImportIO {
    public static final int MAX_ENTRIES = 50000;
    public static final int MAX_DEPTH = 64;
    public static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024;
    public static final long MAX_ZIP_BYTES = 12L * 1024 * 1024 * 1024;
    public static final long FREE_SPACE_RESERVE = 64L * 1024 * 1024;

    private Pd2ImportIO() {}

    public interface Progress {
        void onProgress(String relativePath, long bytesCopied, int filesCopied);
    }

    public static String requireSafePath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.length() > 1024 || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0)
            throw new IOException("Unsafe import path: " + path);
        String[] components = path.split("/", -1);
        if (components.length > MAX_DEPTH) throw new IOException("Directory nesting is too deep.");
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")
                    || component.length() > 255 || component.endsWith(".") || component.endsWith(" "))
                throw new IOException("Unsafe import path: " + path);
            for (int i = 0; i < component.length(); i++) {
                char value = component.charAt(i);
                if (value < 32 || value == 127 || "<>\"|?*".indexOf(value) >= 0)
                    throw new IOException("Invalid Windows filename: " + path);
            }
            String stem = component.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (stem.equals("CON") || stem.equals("PRN") || stem.equals("AUX") || stem.equals("NUL")
                    || stem.matches("(?:COM|LPT)[1-9]"))
                throw new IOException("Reserved Windows filename: " + path);
        }
        return path;
    }

    static String collisionKey(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    public static final class PathRegistry {
        private final Map<String, Reservation> paths = new HashMap<>();
        private static final class Reservation {
            final String path;
            final boolean directory;
            Reservation(String path, boolean directory) { this.path = path; this.directory = directory; }
        }

        public void reserve(String path, boolean directory) throws IOException {
            requireSafePath(path);
            int slash = path.indexOf('/');
            while (slash >= 0) {
                reserveOne(path.substring(0, slash), true);
                slash = path.indexOf('/', slash + 1);
            }
            reserveOne(path, directory);
        }

        private void reserveOne(String path, boolean directory) throws IOException {
            String key = collisionKey(path);
            Reservation previous = paths.get(key);
            if (previous != null) {
                if (!previous.path.equals(path) || !previous.directory || !directory)
                    throw new IOException("Duplicate or conflicting import path: " + path);
                return;
            }
            if (paths.size() >= MAX_ENTRIES) throw new IOException("The import contains too many files or directories.");
            paths.put(key, new Reservation(path, directory));
        }
    }

    public static final class Budget {
        public long bytes;
        public int files;
        private final File storage;
        private final Progress progress;
        public Budget(File storage, Progress progress) { this.storage = storage; this.progress = progress; }

        public void copy(InputStream input, File destination, String path, long expectedBytes, long expectedCrc) throws IOException {
            if (expectedBytes > MAX_FILE_BYTES || (expectedBytes >= 0 && expectedBytes > MAX_TOTAL_BYTES - bytes))
                throw new IOException("The import exceeds the supported file or installation size.");
            if (++files > MAX_ENTRIES) throw new IOException("The import contains too many files.");
            checkSpace(Math.max(0, expectedBytes));
            CRC32 crc = expectedCrc >= 0 ? new CRC32() : null;
            byte[] buffer = new byte[64 * 1024];
            long fileBytes = 0;
            long nextSpaceCheck = 0;
            long lastUpdate = 0;
            try (FileOutputStream output = new FileOutputStream(destination)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled.");
                    if (read == 0) continue;
                    if (read > MAX_FILE_BYTES - fileBytes || read > MAX_TOTAL_BYTES - bytes)
                        throw new IOException("The import exceeds the supported file or installation size.");
                    output.write(buffer, 0, read);
                    if (crc != null) crc.update(buffer, 0, read);
                    fileBytes += read;
                    bytes += read;
                    if (fileBytes >= nextSpaceCheck) { checkSpace(0); nextSpaceCheck = fileBytes + 8L * 1024 * 1024; }
                    long now = System.nanoTime();
                    if (progress != null && now - lastUpdate > 250000000L) {
                        progress.onProgress(path, bytes, files);
                        lastUpdate = now;
                    }
                }
                output.getFD().sync();
            }
            if (expectedBytes >= 0 && fileBytes != expectedBytes) throw new IOException("Incomplete file: " + path);
            if (crc != null && crc.getValue() != expectedCrc) throw new IOException("ZIP checksum failed: " + path);
            if (progress != null) progress.onProgress(path, bytes, files);
        }

        private void checkSpace(long upcoming) throws IOException {
            long usable = storage.getUsableSpace();
            if (usable > 0 && (usable < FREE_SPACE_RESERVE || upcoming > usable - FREE_SPACE_RESERVE))
                throw new IOException("Not enough internal storage. Free space for a complete copy of the game and a 64 MB reserve.");
        }
    }

    public static File target(File root, String relativePath, boolean directory) throws IOException {
        requireSafePath(relativePath);
        File result = new File(root, relativePath);
        if (!result.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("A file escapes the import directory.");
        File parent = directory ? result : result.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create import directory.");
        return result;
    }

    public static void extractZip(File archive, File destination, Progress progress) throws IOException {
        inspectZipDirectory(archive);
        PathRegistry paths = new PathRegistry();
        Budget budget = new Budget(destination, progress);
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                if (++entryCount > MAX_ENTRIES) throw new IOException("The ZIP contains too many entries.");
                ZipEntry entry = entries.nextElement();
                boolean directory = entry.isDirectory();
                String path = entry.getName();
                if (directory) path = path.substring(0, path.length() - 1);
                paths.reserve(path, directory);
                File output = target(destination, path, directory);
                if (!directory) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        budget.copy(input, output, path, entry.getSize(), entry.getCrc());
                    }
                }
            }
        }
    }

    /** Bound metadata before ZipFile allocates it, and reject Unix links and encrypted entries. */
    private static void inspectZipDirectory(File archive) throws IOException {
        if (archive.length() > MAX_ZIP_BYTES) throw new IOException("The ZIP is larger than 12 GB.");
        try (RandomAccessFile input = new RandomAccessFile(archive, "r")) {
            long length = input.length();
            long eocd = -1;
            for (long offset = length - 22, minimum = Math.max(0, length - 65557); offset >= minimum; offset--) {
                input.seek(offset);
                if (u32(input) == 0x06054b50L) {
                    input.seek(offset + 20);
                    if (offset + 22 + u16(input) == length) { eocd = offset; break; }
                }
            }
            if (eocd < 0) throw new IOException("Not a complete ZIP file.");
            input.seek(eocd + 4);
            if (u16(input) != 0 || u16(input) != 0) throw new IOException("Split ZIP archives are not supported.");
            long diskEntries = u16(input), count = u16(input);
            long size = u32(input), start = u32(input);
            long directoryEnd = eocd;
            if (count == 65535 || size == 0xffffffffL || start == 0xffffffffL) {
                if (eocd < 20) throw new IOException("Invalid ZIP64 archive.");
                input.seek(eocd - 20);
                if (u32(input) != 0x07064b50L || u32(input) != 0) throw new IOException("Invalid ZIP64 locator.");
                long record = u64(input);
                if (u32(input) != 1 || record < 0 || record > eocd - 76) throw new IOException("Invalid ZIP64 locator.");
                input.seek(record);
                if (u32(input) != 0x06064b50L || u64(input) < 44) throw new IOException("Invalid ZIP64 record.");
                input.skipBytes(4);
                if (u32(input) != 0 || u32(input) != 0) throw new IOException("Split ZIP64 archives are not supported.");
                diskEntries = u64(input); count = u64(input); size = u64(input); start = u64(input);
                directoryEnd = record;
            }
            if (count < 0 || count != diskEntries || count > MAX_ENTRIES || size < 0 || size > 64L * 1024 * 1024
                    || start < 0 || start > directoryEnd || size > directoryEnd - start)
                throw new IOException("ZIP directory size or entry count exceeds the supported limits.");
            long cursor = start;
            for (long index = 0; index < count; index++) {
                if (cursor > start + size - 46) throw new IOException("Truncated ZIP directory.");
                input.seek(cursor);
                if (u32(input) != 0x02014b50L) throw new IOException("Invalid ZIP directory entry.");
                input.skipBytes(4);
                int flags = u16(input);
                if ((flags & 1) != 0) throw new IOException("Encrypted ZIP files are not supported.");
                input.seek(cursor + 28);
                int nameLength = u16(input), extraLength = u16(input), commentLength = u16(input);
                if (nameLength == 0 || nameLength > 4096) throw new IOException("Invalid ZIP filename length.");
                if (u16(input) != 0) throw new IOException("Split ZIP entries are not supported.");
                input.skipBytes(2);
                long attrs = u32(input);
                int type = (int) ((attrs >>> 16) & 0170000);
                if (type != 0 && type != 0100000 && type != 0040000)
                    throw new IOException("ZIP symbolic links and special files are not supported.");
                cursor += 46L + nameLength + extraLength + commentLength;
                if (cursor > start + size) throw new IOException("Truncated ZIP directory entry.");
            }
            if (cursor != start + size) throw new IOException("Unexpected data in ZIP directory.");
        }
    }

    private static int u16(RandomAccessFile input) throws IOException { return Short.toUnsignedInt(Short.reverseBytes(input.readShort())); }
    private static long u32(RandomAccessFile input) throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt())); }
    private static long u64(RandomAccessFile input) throws IOException { return Long.reverseBytes(input.readLong()); }

    public static void recoverInstallation(File parent) throws IOException {
        File install = new File(parent, "install"), previous = new File(parent, "install.previous");
        if (!install.exists() && previous.exists() && !previous.renameTo(install))
            throw new IOException("Cannot recover the previous PD2 installation.");
    }

    /** Candidate and parent must be on the same filesystem, as all Android staging directories are. */
    public static Pd2InstallValidator.Result promoteValidated(File candidate, File parent) throws IOException {
        Pd2InstallValidator.Result result = Pd2InstallValidator.validate(candidate);
        if (!result.valid) throw new IOException(result.message);
        File install = new File(parent, "install"), previous = new File(parent, "install.previous");
        // Never retire a backup until its replacement has passed validation.
        deleteTree(previous);
        boolean hadPrevious = install.exists();
        if (hadPrevious && !install.renameTo(previous)) throw new IOException("Cannot preserve the previous installation.");
        if (!candidate.renameTo(install)) {
            if (hadPrevious && !previous.renameTo(install))
                throw new IOException("Import promotion failed. The previous installation is preserved in install.previous and will recover next launch.");
            throw new IOException("Cannot activate the imported installation; the previous installation was retained.");
        }
        // Retain the previous complete installation until another validated replacement is imported.
        return result;
    }

    public static void deleteTree(File file) throws IOException {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return;
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot read temporary import directory.");
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("Cannot remove " + file.getName());
    }
}
