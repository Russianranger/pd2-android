package com.winlator.pd2;

import com.winlator.container.Container;
import com.winlator.core.WineInfo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** The downgrade creates a fresh prefix; it must never reuse a Wine 10 prefix. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class Pd2RuntimeMigrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void oldManagedPrefixIsPreservedAndRejectedEvenWithTheNewDefaultWineVersion() throws Exception {
        Container old = completePrefix();
        old.putExtra("pd2Managed", "1");
        // Older .container files omit wineVersion, so the loader supplies the
        // current default. Only the explicit prefix revision disambiguates them.
        old.setWineVersion(WineInfo.MAIN_WINE_INFO.identifier());
        assertFalse(Pd2Runtime.isManagedAndReady(old));
        assertTrue(new File(old.getRootDir(), ".wine/user.reg").isFile());
    }

    @Test public void wrongWineVersionCannotBePublishedAsTheCurrentPrefix() throws Exception {
        Container old = completePrefix();
        markCurrent(old);
        old.setWineVersion("wine-10.10-custom");
        assertFalse(Pd2Runtime.isManagedAndReady(old));
    }

    @Test public void freshCompletePrefixIsReady() throws Exception {
        Container fresh = completePrefix();
        markCurrent(fresh);
        assertTrue(Pd2Runtime.isManagedAndReady(fresh));
    }

    @Test public void interruptedPrefixWithoutControllerDriverCannotBeReused() throws Exception {
        Container partial = completePrefix();
        markCurrent(partial);
        Files.delete(new File(partial.getRootDir(), ".wine/drive_c/windows/system32/drivers/winexinput.sys").toPath());
        assertFalse(Pd2Runtime.isManagedAndReady(partial));
        assertTrue(new File(partial.getRootDir(), ".wine/user.reg").isFile());
    }

    private Container completePrefix() throws Exception {
        File root = temporary.newFolder();
        for (String name : new String[]{".container", ".wine/user.reg", ".wine/system.reg",
                ".wine/drive_c/windows/system32/kernel32.dll", ".wine/drive_c/windows/syswow64/ntdll.dll",
                ".wine/drive_c/windows/syswow64/xinput1_3.dll", ".wine/drive_c/windows/system32/drivers/winexinput.sys"}) {
            File file = new File(root, name);
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), new byte[]{1});
        }
        Container container = new Container(1);
        container.setRootDir(root);
        container.setWineVersion(WineInfo.MAIN_WINE_INFO.identifier());
        return container;
    }

    private void markCurrent(Container container) {
        container.putExtra("pd2Managed", "1");
        container.putExtra("pd2RuntimeRevision", Pd2Runtime.RUNTIME_REVISION);
    }
}
