package com.winlator.pd2;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import androidx.preference.PreferenceManager;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.WineInfo;
import com.winlator.xenvironment.RootFS;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real private filesystem removal and ownership checks; no Wine/native runtime runs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2ContainerMaintenanceTest {
    private Context context;
    private File home;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        home = new File(RootFS.find(context).getRootDir(), "home");
        assertTrue(home.mkdirs() || home.isDirectory());
        prefix(1, false);
        prefix(2, true);
        PreferenceManager.getDefaultSharedPreferences(context).edit().putInt("pd2_container_id", 2).commit();
    }

    @Test public void listSelectionUsesTheSameReadyManagedPrefixAsPlayWithoutChangingPreferences() throws Exception {
        ContainerManager manager = new ContainerManager(context);
        assertEquals(2, Pd2Runtime.findCurrentContainer(context, manager).id);
        PreferenceManager.getDefaultSharedPreferences(context).edit().putInt("pd2_container_id", 99).commit();
        assertEquals(2, Pd2Runtime.findCurrentContainer(context, manager).id);
        assertEquals(99, Pd2Runtime.savedContainerId(context));
        assertTrue(new File(home, "xuser-1").isDirectory());
    }

    @Test public void currentContainerAndPersistedSelectionAreProtected() throws Exception {
        assertNotNull(Pd2ContainerMaintenance.check(context, 2, () -> false));
        PreferenceManager.getDefaultSharedPreferences(context).edit().putInt("pd2_container_id", 1).commit();
        assertNotNull(Pd2ContainerMaintenance.check(context, 1, () -> false));
        assertTrue(new File(home, "xuser-1").isDirectory());
        assertTrue(new File(home, "xuser-2").isDirectory());
    }

    @Test public void copyingOrCreatingAContainerBlocksDeletionUntilItsWorkerFinishes() throws Exception {
        Container old = new ContainerManager(context).getContainerById(1);
        assertNull(Pd2ContainerMaintenance.deletionBlockReason(context, old));
        assertTrue(Pd2ContainerMaintenance.beginContainerWork());
        try {
            assertNotNull(Pd2ContainerMaintenance.deletionBlockReason(context, old));
            assertTrue(new File(home, "xuser-1/.container").isFile());
        } finally { Pd2ContainerMaintenance.endContainerWork(); }
        assertNull(Pd2ContainerMaintenance.deletionBlockReason(context, old));
    }

    @Test public void aQueuedRuntimeActivityRefusesStartupBeforeContainerActivationDuringDeletion() throws Exception {
        java.lang.reflect.Field deleting = Pd2ContainerMaintenance.class.getDeclaredField("deleting");
        deleting.setAccessible(true);
        deleting.setBoolean(null, true);
        com.winlator.XServerDisplayActivity.setPd2LaunchPending(true);
        Intent launch = new Intent(context, com.winlator.XServerDisplayActivity.class).putExtra("container_id", 1);
        try (ActivityController<com.winlator.XServerDisplayActivity> controller =
                     Robolectric.buildActivity(com.winlator.XServerDisplayActivity.class, launch)) {
            com.winlator.XServerDisplayActivity activity = controller.create().get();
            assertTrue("Every launch entry must finish before activation while deletion runs", activity.isFinishing());
            assertFalse("A refused launch cannot leave the runtime reserved", com.winlator.XServerDisplayActivity.isPd2RuntimeWorkInProgress());
            assertFalse("Refusal must not activate or retarget a container", Files.exists(new File(home, RootFS.USER).toPath()));
            assertTrue(new File(home, "xuser-1/.wine/user.reg").isFile());
        } finally {
            deleting.setBoolean(null, false);
            com.winlator.XServerDisplayActivity.setPd2LaunchPending(false);
        }
    }

    @Test public void runtimeSelectedOlderContainerCannotBeDeleted() throws Exception {
        Files.createSymbolicLink(new File(home, RootFS.USER).toPath(), new File(home, "xuser-1").toPath());
        assertNotNull(Pd2ContainerMaintenance.check(context, 1, () -> false));
        assertTrue(new File(home, "xuser-1/.wine/user.reg").isFile());
    }

    @Test public void removalUnlinksMappedDrivesAndBrokenLinksButKeepsImportedGameAndSaves() throws Exception {
        File importedSave = new File(Pd2Installer.installedDirectory(context), "ProjectD2/save/hero.d2s");
        write(importedSave, "saved hero");
        File mapped = Files.createTempDirectory("pd2-mapped-files").toFile();
        File mappedFile = new File(mapped, "keep.txt");
        write(mappedFile, "mapped file");
        File old = new File(home, "xuser-1");
        Files.createSymbolicLink(new File(old, "mapped-game").toPath(), Pd2Installer.installedDirectory(context).toPath());
        Files.createSymbolicLink(new File(old, "mapped-directory").toPath(), mapped.toPath());
        Files.createSymbolicLink(new File(old, "broken").toPath(), new File(mapped, "missing").toPath());
        Pd2ContainerMaintenance.Ticket ticket = ticket(1);
        assertNull(Pd2ContainerMaintenance.deleteNow(context, ticket, () -> false));
        assertFalse(old.exists());
        assertEquals("saved hero", read(importedSave));
        assertEquals("mapped file", read(mappedFile));
        assertTrue(new File(home, "xuser-2/.wine/user.reg").isFile());
    }

    @Test public void startupOrCleanupBegunAfterConfirmationKeepsTheOlderContainer() throws Exception {
        Pd2ContainerMaintenance.Ticket ticket = ticket(1);
        assertNotNull(Pd2ContainerMaintenance.deleteNow(context, ticket, () -> true));
        assertTrue(new File(home, "xuser-1/.container").isFile());
        AtomicInteger checks = new AtomicInteger();
        assertNotNull(Pd2ContainerMaintenance.deleteNow(context, ticket, () -> checks.incrementAndGet() >= 2));
        assertEquals(2, checks.get());
        assertTrue(new File(home, "xuser-1/.container").isFile());
    }

    @Test public void changedSelectionIsRecheckedByTheWorker() throws Exception {
        Pd2ContainerMaintenance.Ticket ticket = ticket(1);
        prefixConfig(1, true);
        PreferenceManager.getDefaultSharedPreferences(context).edit().putInt("pd2_container_id", 1).commit();
        assertNotNull(Pd2ContainerMaintenance.deleteNow(context, ticket, () -> false));
        assertTrue(new File(home, "xuser-1/.wine/user.reg").isFile());
    }

    @Test public void replacedDirectoryIdentityIsRejectedEvenAtTheSameContainerPath() throws Exception {
        Pd2ContainerMaintenance.Ticket ticket = ticket(1);
        File displaced = new File(home, "retained-old-prefix");
        assertTrue(new File(home, "xuser-1").renameTo(displaced));
        prefix(1, false);
        assertNotNull(Pd2ContainerMaintenance.deleteNow(context, ticket, () -> false));
        assertTrue(new File(home, "xuser-1/.container").isFile());
        assertTrue(new File(displaced, ".container").isFile());
    }

    @Test public void linkedContainerRootOrHomeIsRejectedWithoutFollowingIt() throws Exception {
        File old = new File(home, "xuser-1");
        File retained = new File(home, "retained");
        assertTrue(old.renameTo(retained));
        Files.createSymbolicLink(old.toPath(), retained.toPath());
        assertNotNull(Pd2ContainerMaintenance.check(context, 1, () -> false));
        assertTrue(new File(retained, ".container").isFile());
        File movedHome = new File(home.getParentFile(), "retained-home");
        assertTrue(home.renameTo(movedHome));
        Files.createSymbolicLink(home.toPath(), movedHome.toPath());
        assertNotNull(Pd2ContainerMaintenance.check(context, 2, () -> false));
        assertTrue(new File(movedHome, "xuser-2/.container").isFile());
    }

    @Test public void unknownCurrentSelectionPreservesAllContainers() throws Exception {
        Files.delete(new File(home, "xuser-2/.wine/user.reg").toPath());
        assertNotNull(Pd2ContainerMaintenance.check(context, 1, () -> false));
        assertTrue(new File(home, "xuser-1/.container").isFile());
    }

    @Test public void importedStorageResolvedInsideAnOlderContainerIsProtected() throws Exception {
        File shared = new File(context.getFilesDir(), "pd2");
        File protectedFiles = new File(home, "xuser-1/shared-files");
        assertTrue(protectedFiles.mkdir());
        Files.createSymbolicLink(shared.toPath(), protectedFiles.toPath());
        write(new File(shared, "install/ProjectD2/save/hero.d2s"), "saved hero");
        assertNotNull(Pd2ContainerMaintenance.check(context, 1, () -> false));
        assertEquals("saved hero", read(new File(protectedFiles, "install/ProjectD2/save/hero.d2s")));
    }

    private Pd2ContainerMaintenance.Ticket ticket(int id) throws Exception {
        Container container = new ContainerManager(context).getContainerById(id);
        assertNotNull(container);
        return new Pd2ContainerMaintenance.Ticket(container);
    }

    private void prefix(int id, boolean current) throws Exception {
        File root = new File(home, "xuser-" + id);
        assertTrue(root.mkdir());
        for (String path : new String[]{".wine/user.reg", ".wine/system.reg",
                ".wine/drive_c/windows/system32/kernel32.dll", ".wine/drive_c/windows/syswow64/ntdll.dll",
                ".wine/drive_c/windows/syswow64/xinput1_3.dll", ".wine/drive_c/windows/system32/drivers/winexinput.sys"})
            write(new File(root, path), "existing prefix");
        prefixConfig(id, current);
    }

    private void prefixConfig(int id, boolean current) throws Exception {
        write(new File(home, "xuser-" + id + "/.container"), new JSONObject()
                .put("name", Pd2Runtime.CONTAINER_NAME)
                .put("wineVersion", WineInfo.MAIN_WINE_INFO.identifier())
                .put("extraData", new JSONObject().put("pd2Managed", "1")
                        .put("pd2RuntimeRevision", current ? Pd2Runtime.RUNTIME_REVISION : "wine-10.10-pd2-1"))
                .toString());
    }

    private static void write(File file, String value) throws Exception {
        assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
