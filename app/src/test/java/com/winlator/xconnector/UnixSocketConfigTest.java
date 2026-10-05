package com.winlator.xconnector;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.Assert.*;

/** Production path preparation, without loading Android JNI or a runtime. */
public final class UnixSocketConfigTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String[] ENDPOINTS = {
            UnixSocketConfig.SYSVSHM_SERVER_PATH, UnixSocketConfig.XSERVER_PATH,
            UnixSocketConfig.ALSA_SERVER_PATH, UnixSocketConfig.PULSE_SERVER_PATH,
            UnixSocketConfig.VIRGL_SERVER_PATH, UnixSocketConfig.VORTEK_SERVER_PATH};

    @Test public void constructionDoesNotCreateOrDeleteAnyEndpointDirectoryOrNeighbor() throws Exception {
        Path root = temporary.newFolder("runtime").toPath();
        Path sound = Files.createDirectories(root.resolve("tmp/.sound"));
        Path neighbor = sound.resolve("PS0");
        Files.write(neighbor, new byte[]{3, 7});
        UnixSocketConfig config = UnixSocketConfig.create(root.toString(), UnixSocketConfig.ALSA_SERVER_PATH);
        assertEquals(root.resolve("tmp/.sound/AS0").toString(), config.path);
        assertArrayEquals(new byte[]{3, 7}, Files.readAllBytes(neighbor));
        assertFalse(Files.exists(sound.resolve("AS0")));
        UnixSocketConfig.create(root.toString(), UnixSocketConfig.VIRGL_SERVER_PATH);
        assertFalse(Files.exists(root.resolve("tmp/.virgl")));
    }

    @Test public void preparationAfterTmpClearRecreatesEveryRuntimeEndpointParent() throws Exception {
        Path root = temporary.newFolder("runtime").toPath();
        UnixSocketConfig[] configs = new UnixSocketConfig[ENDPOINTS.length];
        for (int i = 0; i < ENDPOINTS.length; i++) {
            configs[i] = UnixSocketConfig.create(root.toString(), ENDPOINTS[i]);
            Files.createDirectories(Path.of(configs[i].path).getParent());
        }
        // PD2 constructs component configs, finishes Wine cleanup, then clears tmp.
        clear(root.resolve("tmp"));
        for (UnixSocketConfig config : configs) assertFalse(Files.exists(Path.of(config.path).getParent()));
        for (UnixSocketConfig config : configs) {
            Path endpoint = Path.of(config.path);
            config.prepareForBind();
            assertTrue(Files.isDirectory(endpoint.getParent()));
            assertFalse(Files.exists(endpoint));
            config.prepareForBind(); // harmless when no stale endpoint is present
        }
    }

    @Test public void occupiedRegularEndpointAndDirectoryAreRefusedWithoutDeletingContents() throws Exception {
        Path root = temporary.newFolder("runtime").toPath();
        UnixSocketConfig config = UnixSocketConfig.create(root.toString(), UnixSocketConfig.SYSVSHM_SERVER_PATH);
        Path endpoint = Path.of(config.path);
        Files.createDirectories(endpoint.getParent());
        Files.write(endpoint, "keep file".getBytes(StandardCharsets.UTF_8));
        refuses(config);
        assertEquals("keep file", new String(Files.readAllBytes(endpoint), StandardCharsets.UTF_8));
        Files.delete(endpoint);
        Files.createDirectory(endpoint);
        Path child = endpoint.resolve("retain");
        Files.write(child, new byte[]{8});
        refuses(config);
        assertArrayEquals(new byte[]{8}, Files.readAllBytes(child));
    }

    @Test public void nonDirectoryParentIsRefusedAndPreserved() throws Exception {
        Path root = temporary.newFolder("runtime").toPath();
        Files.createDirectory(root.resolve("tmp"));
        Path parent = root.resolve("tmp/.X11-unix");
        Files.write(parent, new byte[]{4});
        refuses(UnixSocketConfig.create(root.toString(), UnixSocketConfig.XSERVER_PATH));
        assertArrayEquals(new byte[]{4}, Files.readAllBytes(parent));
    }

    @Test public void escapedParentSymlinkCannotCreateOrRemoveOutsideFiles() throws Exception {
        Path root = temporary.newFolder("runtime").toPath();
        Path outside = temporary.newFolder("outside").toPath();
        Path retained = outside.resolve("SM0");
        Files.write(retained, new byte[]{5});
        Files.createDirectory(root.resolve("tmp"));
        Files.createSymbolicLink(root.resolve("tmp/.sysvshm"), outside);
        refuses(UnixSocketConfig.create(root.toString(), UnixSocketConfig.SYSVSHM_SERVER_PATH));
        assertArrayEquals(new byte[]{5}, Files.readAllBytes(retained));
        assertEquals(1, outside.toFile().list().length);
    }

    @Test public void endpointSymlinkIsRefusedEvenWhenItsTargetIsMissing() throws Exception {
        Path root = temporary.newFolder("runtime").toPath();
        Path outside = temporary.newFolder("outside").toPath();
        Path retained = outside.resolve("keep");
        Files.write(retained, new byte[]{6});
        UnixSocketConfig config = UnixSocketConfig.create(root.toString(), UnixSocketConfig.ALSA_SERVER_PATH);
        Path endpoint = Path.of(config.path);
        Files.createDirectories(endpoint.getParent());
        Files.createSymbolicLink(endpoint, retained);
        refuses(config);
        assertTrue(Files.isSymbolicLink(endpoint));
        assertArrayEquals(new byte[]{6}, Files.readAllBytes(retained));
        Files.delete(endpoint);
        Files.createSymbolicLink(endpoint, outside.resolve("missing"));
        refuses(config);
        assertTrue(Files.isSymbolicLink(endpoint));
        assertFalse(Files.exists(outside.resolve("missing")));
    }

    @Test public void preparationRetainsFilesBesideItsOwnEndpoint() throws Exception {
        Path root = temporary.newFolder("runtime").toPath();
        UnixSocketConfig config = UnixSocketConfig.create(root.toString(), UnixSocketConfig.ALSA_SERVER_PATH);
        Path sound = Files.createDirectories(Path.of(config.path).getParent());
        Path pulse = sound.resolve("PS0");
        Path unrelated = sound.resolve("retain.txt");
        Files.write(pulse, new byte[]{7});
        Files.write(unrelated, new byte[]{9});
        config.prepareForBind();
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(pulse));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(unrelated));
    }

    @Test public void missingOrLinkedRuntimeRootFailsBeforeCreatingDirectories() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("missing-runtime");
        refuses(UnixSocketConfig.create(root.toString(), UnixSocketConfig.XSERVER_PATH));
        assertFalse(Files.exists(root));
        Path outside = temporary.newFolder("outside").toPath();
        Files.createSymbolicLink(root, outside);
        refuses(UnixSocketConfig.create(root.toString(), UnixSocketConfig.XSERVER_PATH));
        assertEquals(0, outside.toFile().list().length);
    }

    @Test public void invalidAndEscapingPathsFailWithoutCreatingAnything() throws Exception {
        Path root = temporary.newFolder("runtime").toPath();
        for (String endpoint : new String[]{"", "/", "/../../escaped/S0", "\u0000"}) {
            try { UnixSocketConfig.create(root.toString(), endpoint); fail("Invalid endpoint accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        for (String invalidRoot : new String[]{"", "/", "relative-runtime"}) {
            try { UnixSocketConfig.create(invalidRoot, UnixSocketConfig.XSERVER_PATH); fail("Invalid root accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        assertEquals(0, root.toFile().list().length);
    }

    private static void refuses(UnixSocketConfig config) {
        try { config.prepareForBind(); fail("Unsafe socket path accepted"); }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(config.relativePath));
            assertFalse(expected.getMessage().contains(config.path));
        }
    }

    private static void clear(Path directory) throws Exception {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, java.io.IOException error) throws java.io.IOException {
                if (error != null) throw error;
                if (!path.equals(directory)) Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
