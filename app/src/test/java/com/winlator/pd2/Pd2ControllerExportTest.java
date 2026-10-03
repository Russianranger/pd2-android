package com.winlator.pd2;

import android.app.Application;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2ControllerExportTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test public void supportExportIncludesOnlyTheCurrentLaunchControllerReport() throws Exception {
        File controller = directory.newFile("controller.json");
        File launch = directory.newFile("launch.json");
        write(launch, new JSONObject().put("launchId", "current").toString());
        write(controller, new JSONObject().put("launchId", "previous").put("counts", 99).toString());
        assertEmpty(export(controller, launch));

        String current = new JSONObject().put("launchId", "current").put("counts", 2).toString();
        write(controller, current);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(export(controller, launch)))) {
            assertEquals("controller.json", zip.getNextEntry().getName());
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int read;
            while ((read = zip.read(buffer)) != -1) content.write(buffer, 0, read);
            assertEquals(current, new String(content.toByteArray(), StandardCharsets.UTF_8));
            assertNull(zip.getNextEntry());
        }
    }

    @Test public void corruptMissingOrOversizeReportsDoNotPolluteTheSupportZip() throws Exception {
        File controller = directory.newFile("controller.json");
        File launch = directory.newFile("launch.json");
        write(launch, "{\"launchId\":\"current\"}");
        write(controller, "broken json");
        assertEmpty(export(controller, launch));
        Files.write(controller.toPath(), new byte[Pd2ControllerDiagnostics.MAX_REPORT_BYTES + 1]);
        assertEmpty(export(controller, launch));
        write(controller, "{\"launchId\":\"current\"}");
        assertTrue(launch.delete());
        assertEmpty(export(controller, launch));
    }

    private static byte[] export(File controller, File launch) throws Exception {
        Method method = Pd2Activity.class.getDeclaredMethod("zipControllerDiagnostics", ZipOutputStream.class, File.class, File.class);
        method.setAccessible(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { method.invoke(null, zip, controller, launch); }
        return bytes.toByteArray();
    }

    private static void assertEmpty(byte[] bytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) { assertNull(zip.getNextEntry()); }
    }

    private static void write(File file, String value) throws Exception {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
