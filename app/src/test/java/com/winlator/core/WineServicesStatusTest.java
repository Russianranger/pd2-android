package com.winlator.core;

import android.app.Application;

import com.winlator.container.Container;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Run the real service policy while recording registry I/O without loading JNI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, shadows = WineServicesStatusTest.Registry.class,
        instrumentedPackages = {"com.winlator.core.WineRegistryEditor"})
public final class WineServicesStatusTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();
    @Before public void resetRegistry() { Registry.reset(); }

    @Test public void managedPd2NotificationsKeepPlugPlayAndRpcSsWhileOtherEssentialServicesStayDisabled() throws Exception {
        Container container = container("1", "1");
        WineUtils.changeServicesStatus(container, Container.STARTUP_SELECTION_ESSENTIAL);
        assertEquals(Integer.valueOf(2), start("PlugPlay"));
        assertEquals(Integer.valueOf(3), start("RpcSs"));
        assertEquals(Integer.valueOf(4), start("Eventlog"));
        assertEquals(Integer.valueOf(4), start("NDIS"));
        assertEquals(Integer.valueOf(4), start("BITS"));
        assertEquals(Integer.valueOf(2), start("nsiproxy"));
        assertEquals(Integer.valueOf(3), start("MSIServer"));
        assertEquals(Integer.valueOf(3), start("FontCache"));
        assertRegistryBoundary(container);
    }

    @Test public void retentionRequiresBothMarkersAndDisablingNotificationsRestoresThePriorPolicy() throws Exception {
        for (String managed : new String[]{null, "0", "1"}) {
            for (String enabled : new String[]{null, "0", "1"}) {
                Registry.reset();
                Container container = container(managed, enabled);
                WineUtils.changeServicesStatus(container, Container.STARTUP_SELECTION_ESSENTIAL);
                boolean retain = "1".equals(managed) && "1".equals(enabled);
                assertEquals("managed=" + managed + ", notifications=" + enabled,
                        Integer.valueOf(retain ? 2 : 4), start("PlugPlay"));
                assertEquals(Integer.valueOf(retain ? 3 : 4), start("RpcSs"));
                assertEquals(Integer.valueOf(4), start("Eventlog"));
            }
        }
        Registry.reset();
        Container container = container("1", "1");
        WineUtils.changeServicesStatus(container, Container.STARTUP_SELECTION_ESSENTIAL);
        container.putExtra("pd2ControllerNotifications", "0");
        WineUtils.changeServicesStatus(container, Container.STARTUP_SELECTION_ESSENTIAL);
        assertEquals(Integer.valueOf(4), start("PlugPlay"));
        assertEquals(Integer.valueOf(4), start("RpcSs"));
    }

    @Test public void normalAndAggressiveModesPreserveTheirOtherServiceRules() throws Exception {
        Container container = container(null, null);
        WineUtils.changeServicesStatus(container, Container.STARTUP_SELECTION_NORMAL);
        assertEquals(Integer.valueOf(2), start("PlugPlay"));
        assertEquals(Integer.valueOf(3), start("RpcSs"));
        assertEquals(Integer.valueOf(2), start("Eventlog"));
        assertEquals(Integer.valueOf(3), start("BITS"));
        assertEquals(Integer.valueOf(2), start("nsiproxy"));
        Registry.reset();
        container.putExtra("pd2Managed", "1");
        container.putExtra("pd2ControllerNotifications", "1");
        WineUtils.changeServicesStatus(container, Container.STARTUP_SELECTION_AGGRESSIVE);
        assertEquals(Integer.valueOf(2), start("PlugPlay"));
        assertEquals(Integer.valueOf(3), start("RpcSs"));
        assertEquals(Integer.valueOf(4), start("Eventlog"));
        assertEquals(Integer.valueOf(4), start("nsiproxy"));
        assertEquals(Integer.valueOf(4), start("MSIServer"));
        assertEquals(Integer.valueOf(4), start("FontCache"));
    }

    @Test public void currentControlSetSymlinkSelectsTheRegistryBranchWithoutCreatingMissingKeys() throws Exception {
        Container container = container("1", "1");
        Registry.symlink = "System\\ControlSet002";
        WineUtils.changeServicesStatus(container, Container.STARTUP_SELECTION_ESSENTIAL);
        assertEquals("System\\CurrentControlSet", Registry.linkKey);
        assertEquals("SymbolicLinkValue", Registry.linkName);
        assertEquals(18, Registry.values.size());
        for (String key : Registry.values.keySet()) assertTrue(key, key.startsWith("System\\ControlSet002\\Services\\"));
        assertEquals(Integer.valueOf(2), Registry.values.get("System\\ControlSet002\\Services\\PlugPlay"));
        assertRegistryBoundary(container);
    }

    private Container container(String managed, String enabled) throws Exception {
        Container container = new Container(1);
        container.setRootDir(directory.newFolder());
        if (managed != null) container.putExtra("pd2Managed", managed);
        if (enabled != null) container.putExtra("pd2ControllerNotifications", enabled);
        return container;
    }

    private static Integer start(String name) {
        return Registry.values.get("System\\CurrentControlSet\\Services\\" + name);
    }

    private static void assertRegistryBoundary(Container container) {
        assertEquals(new File(container.getRootDir(), ".wine/system.reg"), Registry.openedFile);
        assertFalse(Registry.createKeys);
        assertTrue(Registry.closed);
        for (Map.Entry<String, Integer> entry : Registry.values.entrySet()) assertNotNull(entry.getValue());
    }

    @Implements(value = WineRegistryEditor.class, callThroughByDefault = false)
    public static final class Registry {
        static final LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        static File openedFile;
        static String symlink, linkKey, linkName;
        static boolean createKeys, closed;
        static void reset() {
            values.clear(); openedFile = null; symlink = linkKey = linkName = null;
            createKeys = true; closed = false;
        }
        @Implementation protected static void __staticInitializer__() { /* Native editor intentionally replaced. */ }
        @Implementation protected void __constructor__(File file) { openedFile = file; }
        @Implementation protected void setCreateKeyIfNotExist(boolean value) { createKeys = value; }
        @Implementation protected String getSymlinkValue(String key, String name) {
            linkKey = key; linkName = name; return symlink;
        }
        @Implementation protected void setDwordValue(String key, String name, int value) {
            assertEquals("Start", name);
            values.put(key, value);
        }
        @Implementation protected void close() { closed = true; }
    }
}
