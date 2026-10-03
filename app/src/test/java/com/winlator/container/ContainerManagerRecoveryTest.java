package com.winlator.container;

import android.app.Application;
import android.content.Context;

import com.winlator.xenvironment.RootFS;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** Filesystem recovery tests use an extractor fixture; no Wine or native assets run. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class ContainerManagerRecoveryTest {
    private Context context;
    private File home;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        home = new File(RootFS.find(context).getRootDir(), "home");
        assertTrue(home.mkdirs() || home.isDirectory());
    }

    @Test public void damagedAndPartialPrefixesDoNotHideValidContainersOrLoseFiles() throws Exception {
        File partial = directory("xuser-1");
        File retained = write(new File(partial, "unfinished.txt"), "keep partial");
        File damaged = directory("xuser-2");
        File config = write(new File(damaged, ".container"), "{ interrupted");
        valid(3, "Existing game");

        ContainerManager manager = new ContainerManager(context);
        assertEquals(1, manager.getContainers().size());
        assertEquals("Existing game", manager.getContainerById(3).getName());
        assertNull(manager.getContainerById(1));
        assertNull(manager.getContainerById(2));
        assertEquals(4, manager.getNextContainerId());
        assertEquals("keep partial", read(retained));
        assertEquals("{ interrupted", read(config));
    }

    @Test public void runtimeInvalidConfigAndMalformedDirectoryNamesAreIsolated() throws Exception {
        File bad = directory("xuser-7");
        write(new File(bad, ".container"), new JSONObject()
                .put("name", "Bad settings")
                .put("extraData", new JSONObject().put("appVersion", "broken"))
                .toString());
        for (String name : new String[]{"xuser-backup", "xuser-", "xuser--1",
                "xuser-+1", "xuser-0005", "xuser-2147483648"}) directory(name);
        valid(9, "Still readable");

        ContainerManager manager = new ContainerManager(context);
        assertEquals(1, manager.getContainers().size());
        assertNotNull(manager.getContainerById(9));
        assertNull(manager.getContainerById(7));
        assertEquals(10, manager.getNextContainerId());
        assertTrue(bad.isDirectory());
        assertTrue(new File(home, "xuser-backup").isDirectory());
    }

    @Test public void occupiedFilesAndLinksAreReservedWithoutFollowingThem() throws Exception {
        write(new File(home, "xuser-2"), "occupied file");
        File outside = Files.createTempDirectory("container-outside").toFile();
        write(new File(outside, ".container"), new JSONObject().put("name", "Outside").toString());
        Files.createSymbolicLink(new File(home, "xuser-5").toPath(), outside.toPath());
        Files.createSymbolicLink(new File(home, "xuser-6").toPath(), new File(home, "missing").toPath());
        File linkedConfig = directory("xuser-7");
        Files.createSymbolicLink(new File(linkedConfig, ".container").toPath(), new File(outside, ".container").toPath());

        ContainerManager manager = new ContainerManager(context);
        assertTrue(manager.getContainers().isEmpty());
        assertEquals(8, manager.getNextContainerId());
        assertEquals("occupied file", read(new File(home, "xuser-2")));
        assertTrue(Files.isSymbolicLink(new File(home, "xuser-6").toPath()));
        assertTrue(new File(outside, ".container").isFile());
    }

    @Test public void allocationRechecksFilesystemAfterManagerConstruction() throws Exception {
        FixtureManager manager = new FixtureManager(context);
        File later = directory("xuser-4");
        write(new File(later, "existing.txt"), "preserve");

        Container created = manager.createContainer(new JSONObject().put("name", "Fresh"));
        assertNotNull(created);
        assertEquals(5, created.id);
        assertEquals(6, manager.getNextContainerId());
        assertEquals("preserve", read(new File(later, "existing.txt")));
    }

    @Test public void failedExtractionRetainsPrefixAndRetryUsesAnotherId() throws Exception {
        FixtureManager manager = new FixtureManager(context);
        manager.failExtraction = true;
        assertNull(manager.createContainer(new JSONObject().put("name", "First attempt")));
        File partial = new File(home, "xuser-1/extraction.txt");
        assertEquals("partial", read(partial));
        assertEquals(2, manager.getNextContainerId());

        manager.failExtraction = false;
        Container retry = manager.createContainer(new JSONObject().put("name", "Retry"));
        assertNotNull(retry);
        assertEquals(2, retry.id);
        assertEquals("partial", read(partial));
        assertEquals(1, manager.getContainers().size());
        assertEquals(3, new ContainerManager(context).getNextContainerId());
    }

    @Test public void exhaustedIdSpaceFailsWithoutChangingExistingOrImportedFiles() throws Exception {
        File last = directory("xuser-2147483647");
        File sentinel = write(new File(last, "retain.txt"), "last prefix");
        File game = new File(context.getFilesDir(), "pd2/install/ProjectD2/save/hero.d2s");
        assertTrue(game.getParentFile().mkdirs() || game.getParentFile().isDirectory());
        write(game, "saved hero");
        FixtureManager manager = new FixtureManager(context);

        assertEquals(0, manager.getNextContainerId());
        assertNull(manager.createContainer(new JSONObject().put("name", "Cannot allocate")));
        assertEquals(0, manager.extractions);
        assertEquals("last prefix", read(sentinel));
        assertEquals("saved hero", read(game));
        assertFalse(new File(home, "xuser--2147483648").exists());
    }

    private File directory(String name) {
        File file = new File(home, name);
        assertTrue(file.mkdir());
        return file;
    }

    private void valid(int id, String name) throws Exception {
        write(new File(directory("xuser-" + id), ".container"), new JSONObject().put("name", name).toString());
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File write(File file, String value) throws IOException {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static final class FixtureManager extends ContainerManager {
        boolean failExtraction;
        int extractions;
        FixtureManager(Context context) { super(context); }

        @Override boolean extractContainerPatternFile(String wineVersion, File containerDir) {
            extractions++;
            try { write(new File(containerDir, "extraction.txt"), failExtraction ? "partial" : "complete"); }
            catch (IOException error) { throw new IllegalStateException(error); }
            return !failExtraction;
        }
    }
}
