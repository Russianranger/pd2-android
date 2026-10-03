package com.winlator.pd2;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded, read-only validation of a user-supplied classic Diablo II + PD2 install. */
public final class Pd2InstallValidator {
    private static final String[] BASE_ARCHIVES = {"d2data.mpq", "d2char.mpq", "d2sfx.mpq", "d2exp.mpq"};

    private Pd2InstallValidator() {}

    public static final class Result {
        public final boolean valid;
        public final String message;
        public final String gameExecutableRelativePath;
        public final String clientRootRelativePath;
        public final String details;

        private Result(boolean valid, String message, String executable, String root, String details) {
            this.valid = valid;
            this.message = message;
            this.gameExecutableRelativePath = executable;
            this.clientRootRelativePath = root;
            this.details = details;
        }

        private static Result failure(String message) {
            return new Result(false, message, null, null, message);
        }
    }

    public static Result validate(File installation) {
        if (installation == null || !installation.isDirectory())
            return Result.failure("Import a complete Diablo II: Lord of Destruction folder containing ProjectD2.");
        try {
            List<File> candidates = new ArrayList<>();
            int[] count = {0};
            collect(installation, installation.getCanonicalFile(), candidates, 0, count);
            if (candidates.isEmpty())
                return Result.failure("ProjectD2/Game.exe was not found. Select the complete Diablo II folder, not just the mod folder.");
            List<Result> valid = new ArrayList<>();
            String failure = null;
            for (File mod : candidates) {
                Result result = inspect(installation, mod);
                if (result.valid) valid.add(result);
                else if (failure == null) failure = result.message;
            }
            if (valid.size() > 1)
                return Result.failure("Multiple complete PD2 installations were found. Import only the installation you want to play.");
            return valid.size() == 1 ? valid.get(0) : Result.failure(failure);
        } catch (IOException | SecurityException exception) {
            return Result.failure("Installation validation failed: " + exception.getMessage());
        }
    }

    private static void collect(File directory, File boundary, List<File> candidates, int depth, int[] count) throws IOException {
        if (depth > Pd2ImportIO.MAX_DEPTH) throw new IOException("Directory nesting is too deep.");
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Cannot read " + directory.getName());
        Map<String, Boolean> names = new HashMap<>();
        for (File child : children) {
            if (++count[0] > Pd2ImportIO.MAX_ENTRIES) throw new IOException("The installation contains too many files.");
            Pd2ImportIO.requireSafePath(child.getName());
            String folded = Pd2ImportIO.collisionKey(child.getName());
            if (names.put(folded, true) != null) throw new IOException("Names differ only by case: " + child.getName());
            if (Files.isSymbolicLink(child.toPath())) throw new IOException("Symbolic links are not accepted: " + child.getName());
            String path = child.getCanonicalPath();
            if (!path.startsWith(boundary.getPath() + File.separator)) throw new IOException("A file escapes the installation directory.");
            if (child.isDirectory()) {
                if (child.getName().equalsIgnoreCase("ProjectD2") && findChild(child, "Game.exe") != null) candidates.add(child);
                collect(child, boundary, candidates, depth + 1, count);
            } else if (!child.isFile()) throw new IOException("Unsupported file type: " + child.getName());
        }
    }

    private static Result inspect(File installation, File mod) throws IOException {
        File exe = findChild(mod, "Game.exe");
        if (exe == null || !isX86Pe32(exe))
            return Result.failure("ProjectD2/Game.exe is not a valid 32-bit x86 Windows executable.");
        File dll = findChild(mod, "ProjectDiablo.dll");
        if (dll == null || !isX86Pe32(dll))
            return Result.failure("ProjectD2/ProjectDiablo.dll is missing or is not a 32-bit x86 PD2 DLL.");
        File pd2data = findChild(mod, "pd2data.mpq");
        if (pd2data == null || !isMpq(pd2data))
            return Result.failure("ProjectD2/pd2data.mpq is missing or has an invalid MPQ header. Import your updated, working PD2 installation.");
        File base = mod.getParentFile();
        while (base != null && contained(installation, base)) {
            boolean complete = true;
            for (String archive : BASE_ARCHIVES) {
                File file = findChild(base, archive);
                if (file == null || !isMpq(file)) { complete = false; break; }
            }
            if (complete) {
                String root = relative(installation, base);
                String game = relative(installation, exe);
                return new Result(true, "PD2 installation ready", game, root,
                        "32-bit x86 Game.exe and ProjectDiablo.dll; PD2 data and base Diablo II/LoD MPQ archives present. "
                                + "Launch path: " + game + ". File presence and headers are checked; gameplay compatibility requires a device test.");
            }
            if (base.getCanonicalFile().equals(installation.getCanonicalFile())) break;
            base = base.getParentFile();
        }
        return Result.failure("Base Diablo II/LoD archives are missing. Include d2data.mpq, d2char.mpq, d2sfx.mpq and d2exp.mpq beside the ProjectD2 folder.");
    }

    private static boolean contained(File root, File child) throws IOException {
        String boundary = root.getCanonicalPath();
        String path = child.getCanonicalPath();
        return path.equals(boundary) || path.startsWith(boundary + File.separator);
    }

    private static File findChild(File directory, String name) {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children)
            if (child.getName().equalsIgnoreCase(name)) return child;
        return null;
    }

    private static String relative(File root, File child) throws IOException {
        return root.getCanonicalFile().toPath().relativize(child.getCanonicalFile().toPath()).toString().replace(File.separatorChar, '/');
    }

    public static boolean isX86Pe32(File file) throws IOException {
        if (!file.isFile() || file.length() < 90) return false;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.readUnsignedByte() != 'M' || input.readUnsignedByte() != 'Z') return false;
            input.seek(0x3c);
            long offset = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
            if (offset > file.length() - 26 || offset < 0x40) return false;
            input.seek(offset);
            if (input.readInt() != 0x50450000) return false;
            if (Short.reverseBytes(input.readShort()) != 0x014c) return false;
            input.seek(offset + 20);
            int optionalSize = Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
            if (optionalSize < 2 || offset + 24 + optionalSize > file.length()) return false;
            input.seek(offset + 24);
            return Short.reverseBytes(input.readShort()) == 0x010b;
        }
    }

    private static boolean isMpq(File file) throws IOException {
        if (!file.isFile() || file.length() < 32) return false;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            // MPQ archive headers may follow a user-data header, on a 512-byte boundary.
            long end = Math.min(file.length() - 4, 1024 * 1024);
            for (long offset = 0; offset <= end; offset += 512) {
                input.seek(offset);
                if (input.readInt() == 0x4d50511a) return true;
            }
            return false;
        }
    }
}
