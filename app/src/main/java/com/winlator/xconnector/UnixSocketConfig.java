package com.winlator.xconnector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

public class UnixSocketConfig {
    public static final String SYSVSHM_SERVER_PATH = "/tmp/.sysvshm/SM0";
    public static final String ALSA_SERVER_PATH = "/tmp/.sound/AS0";
    public static final String PULSE_SERVER_PATH = "/tmp/.sound/PS0";
    public static final String XSERVER_PATH = "/tmp/.X11-unix/X0";
    public static final String VIRGL_SERVER_PATH = "/tmp/.virgl/V0";
    public static final String VORTEK_SERVER_PATH = "/tmp/.vortek/V0";
    public final String path;
    public final String relativePath;
    private final Path root;
    private final Path socket;

    private UnixSocketConfig(Path root, Path socket) {
        this.root = root;
        this.socket = socket;
        this.path = socket.toString();
        this.relativePath = "/" + root.relativize(socket);
    }

    /** Describe the endpoint only. Runtime cleanup may still remove tmp after this call. */
    public static UnixSocketConfig create(String rootPath, String relativePath) {
        if (rootPath == null || rootPath.isEmpty() || relativePath == null || relativePath.isEmpty())
            throw new IllegalArgumentException("Unix socket runtime root and endpoint path are required");
        try {
            Path root = Paths.get(rootPath);
            if (!root.isAbsolute()) throw new IllegalArgumentException("Unix socket runtime root must be absolute");
            root = root.normalize();
            if (root.getParent() == null) throw new IllegalArgumentException("Unix socket runtime root cannot be the filesystem root");
            // Constants use guest-absolute paths; they are relative to the private runtime root.
            String guestPath = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
            Path child = Paths.get(guestPath);
            if (child.isAbsolute()) throw new IllegalArgumentException("Invalid Unix socket endpoint path");
            Path socket = root.resolve(child).normalize();
            if (socket.equals(root) || !socket.startsWith(root))
                throw new IllegalArgumentException("Unix socket endpoint must stay inside the runtime root");
            return new UnixSocketConfig(root, socket);
        }
        catch (java.nio.file.InvalidPathException error) {
            throw new IllegalArgumentException("Invalid Unix socket runtime or endpoint path", error);
        }
    }

    /** Run immediately before bind, after prior session shutdown and tmp cleanup. */
    public void prepareForBind() {
        try {
            BasicFileAttributes rootAttributes = attributes(root);
            if (!rootAttributes.isDirectory() || rootAttributes.isSymbolicLink())
                throw new IOException("Runtime root is not a private directory");
            Path actualRoot = root.toRealPath();
            Path relative = root.relativize(socket);
            Path parent = actualRoot;
            if (relative.getParent() != null) {
                for (Path segment : relative.getParent()) {
                    parent = parent.resolve(segment);
                    if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(parent);
                    BasicFileAttributes parentAttributes = attributes(parent);
                    if (!parentAttributes.isDirectory() || parentAttributes.isSymbolicLink())
                        throw new IOException("Socket parent is not a private directory: /" + actualRoot.relativize(parent));
                    if (!parent.toRealPath().startsWith(actualRoot))
                        throw new IOException("Socket parent escapes the runtime root");
                }
            }
            Path endpoint = parent.resolve(relative.getFileName());
            if (Files.exists(endpoint, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes endpointAttributes = attributes(endpoint);
                if (!endpointAttributes.isOther() || endpointAttributes.isSymbolicLink())
                    throw new IOException("Socket endpoint is occupied by a regular file, directory, or link");
                // Preserve neighboring endpoints and every file in the parent directory.
                Files.delete(endpoint);
            }
        }
        catch (IOException | UnsupportedOperationException | SecurityException error) {
            String reason = error instanceof java.nio.file.NoSuchFileException ? "Required runtime directory is missing"
                    : error instanceof java.nio.file.FileAlreadyExistsException ? "Socket parent path is already occupied"
                    : error instanceof java.nio.file.AccessDeniedException ? "Socket endpoint permission denied"
                    : error.getMessage();
            throw new IllegalStateException("Cannot prepare Unix socket " + relativePath + ": " + reason, error);
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
}
