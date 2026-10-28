package com.winlator.pd2;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import com.winlator.core.EnvVars;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2AudioRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cleanupMustPassBeforeContextAssetsOrEnvironmentAreTouched() throws Exception {
        for (Pd2WineSession.Result cleanup : new Pd2WineSession.Result[]{null,
                cleanup("afterStop", 0, 0, true, ""), cleanup("beforeLaunch", -1, 0, true, ""),
                cleanup("beforeLaunch", 0, 1, true, ""), cleanup("beforeLaunch", 0, 0, false, ""),
                cleanup("beforeLaunch", 0, 0, true, "unknown process")}) {
            for (boolean enabled : new boolean[]{false, true}) {
                try { Pd2AudioRuntime.prepareAfterCleanup(null, cleanup, enabled, null); fail(); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("cleanup")); }
            }
        }
    }

    @Test public void normalModeDoesNotCreateRuntimeFiles() throws Exception {
        File files = temporary.newFolder();
        EnvVars env = new EnvVars();
        JSONObject status = Pd2AudioRuntime.prepareAfterCleanup(context(files), goodCleanup(), false, env);
        assertFalse(status.getBoolean("enabled"));
        assertFalse(status.getBoolean("installedVerified"));
        assertEquals(0, status.getInt("requestedMixerRateHz"));
        assertEquals(0, files.list().length);
        assertFalse(env.has("ALSA_CONFIG_PATH"));
    }

    @Test public void ecoInstallsActualVerifiedArm64ModuleAndPrivateConfiguration() throws Exception {
        File files = temporary.newFolder();
        File original = new File(files, "original-alsa.so");
        Files.write(original.toPath(), new byte[]{1, 2, 3});
        EnvVars env = new EnvVars("UNRELATED=kept");
        JSONObject status = Pd2AudioRuntime.prepareAfterCleanup(context(files), goodCleanup(), true, env);
        assertTrue(status.getBoolean("requested"));
        assertTrue(status.getBoolean("enabled"));
        assertTrue(status.getBoolean("installedVerified"));
        assertEquals(22050, status.getInt("requestedMixerRateHz"));
        assertEquals(Pd2AudioRuntime.MODULE_SHA256, status.getString("moduleSha256"));
        assertArrayEquals(payload(), Files.readAllBytes(module(files).toPath()));
        assertEquals(config(files).getCanonicalPath(), env.get("ALSA_CONFIG_PATH"));
        String body = new String(Files.readAllBytes(config(files).toPath()), StandardCharsets.UTF_8);
        assertTrue(body.contains("pcm_type.pd2_eco"));
        assertTrue(body.contains(module(files).getCanonicalPath()));
        assertTrue(body.contains("pcm.!default { type pd2_eco }"));
        assertEquals("kept", env.get("UNRELATED"));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(original.toPath()));
        assertNoTemporary(files);
    }

    @Test public void repeatedEcoIsIdempotentAndBalancedRestoresOriginalEnvironmentRoute() throws Exception {
        File files = temporary.newFolder();
        EnvVars env = new EnvVars();
        Context context = context(files);
        Pd2AudioRuntime.prepareAfterCleanup(context, goodCleanup(), true, env);
        byte[] originalConfig = Files.readAllBytes(config(files).toPath());
        long moduleModified = module(files).lastModified(), configModified = config(files).lastModified();
        Pd2AudioRuntime.prepareAfterCleanup(context, goodCleanup(), true, env);
        assertEquals(moduleModified, module(files).lastModified());
        assertEquals(configModified, config(files).lastModified());
        JSONObject status = Pd2AudioRuntime.prepareAfterCleanup(context, goodCleanup(), false, env);
        assertFalse(status.getBoolean("enabled"));
        assertFalse(env.has("ALSA_CONFIG_PATH"));
        assertArrayEquals(originalConfig, Files.readAllBytes(config(files).toPath()));
        assertArrayEquals(payload(), Files.readAllBytes(module(files).toPath()));
    }

    @Test public void normalModePreservesAnUnrelatedAlsaConfiguration() throws Exception {
        File files = temporary.newFolder();
        EnvVars env = new EnvVars().put("ALSA_CONFIG_PATH", "/user/other.conf");
        Pd2AudioRuntime.prepareAfterCleanup(context(files), goodCleanup(), false, env);
        assertEquals("/user/other.conf", env.get("ALSA_CONFIG_PATH"));
        assertEquals(0, files.list().length);
    }

    @Test public void corruptedTruncatedOrOversizedPayloadCannotPublishOrCreateFiles() throws Exception {
        byte[] original = payload();
        byte[] corrupt = original.clone(); corrupt[256] ^= 1;
        for (byte[] image : new byte[][]{corrupt, Arrays.copyOf(original, original.length - 1),
                Arrays.copyOf(original, original.length + 1)}) {
            File files = temporary.newFolder();
            EnvVars env = new EnvVars().put("ALSA_CONFIG_PATH", "/user/other.conf");
            expectRejected(files, image, env);
            assertEquals("/user/other.conf", env.get("ALSA_CONFIG_PATH"));
            assertEquals(0, files.list().length);
        }
    }

    @Test public void unknownModuleIsPreservedAndConfigurationIsNotInstalled() throws Exception {
        File files = temporary.newFolder();
        assertTrue(module(files).getParentFile().mkdirs());
        byte[] unknown = payload(); unknown[500] ^= 1;
        Files.write(module(files).toPath(), unknown);
        EnvVars env = new EnvVars();
        expectRejected(files, payload(), env);
        assertArrayEquals(unknown, Files.readAllBytes(module(files).toPath()));
        assertFalse(config(files).exists());
        assertFalse(env.has("ALSA_CONFIG_PATH"));
        assertNoTemporary(files);
    }

    @Test public void unknownConfigurationIsPreservedBeforeAnyModuleInstall() throws Exception {
        File files = temporary.newFolder();
        assertTrue(config(files).getParentFile().mkdirs());
        byte[] unknown = "pcm.!default { type null }\n".getBytes(StandardCharsets.UTF_8);
        Files.write(config(files).toPath(), unknown);
        expectRejected(files, payload(), new EnvVars());
        assertArrayEquals(unknown, Files.readAllBytes(config(files).toPath()));
        assertFalse(module(files).exists());
        assertNoTemporary(files);
    }

    @Test public void moduleAndConfigSymlinksCannotEscapePrivateFiles() throws Exception {
        for (boolean linkModule : new boolean[]{true, false}) {
            File files = temporary.newFolder();
            assertTrue(module(files).getParentFile().mkdirs());
            File external = temporary.newFile();
            byte[] externalBytes = new byte[]{9, 7, 5};
            Files.write(external.toPath(), externalBytes);
            File link = linkModule ? module(files) : config(files);
            Files.createSymbolicLink(link.toPath(), external.toPath());
            expectRejected(files, payload(), new EnvVars());
            assertTrue(Files.isSymbolicLink(link.toPath()));
            assertArrayEquals(externalBytes, Files.readAllBytes(external.toPath()));
            assertFalse((linkModule ? config(files) : module(files)).exists());
        }
    }

    @Test public void parentSymlinksAreRejectedAndNormalModeCanStillBeSelected() throws Exception {
        File files = temporary.newFolder();
        File external = temporary.newFolder();
        Files.createSymbolicLink(new File(files, "pd2").toPath(), external.toPath());
        expectRejected(files, payload(), new EnvVars());
        assertEquals(0, external.list().length);
        EnvVars env = new EnvVars().put("ALSA_CONFIG_PATH", config(files).getPath());
        assertFalse(Pd2AudioRuntime.prepareAfterCleanup(context(files), goodCleanup(), false, env).getBoolean("enabled"));
        assertFalse(env.has("ALSA_CONFIG_PATH"));
        assertEquals(0, external.list().length);
    }

    @Test public void directoryCollisionAndInjectedConfigPathAreRejected() throws Exception {
        File files = temporary.newFolder();
        assertTrue(module(files).mkdirs());
        expectRejected(files, payload(), new EnvVars());
        assertTrue(module(files).isDirectory());
        assertFalse(config(files).exists());
        File newline = temporary.newFolder("bad\nconfig");
        expectRejected(newline, payload(), new EnvVars());
        assertEquals(0, newline.list().length);
    }

    @Test public void payloadReadCannotRaceAPathIntoAnExternalDirectory() throws Exception {
        File files = temporary.newFolder();
        File external = temporary.newFolder();
        ByteArrayInputStream source = new ByteArrayInputStream(payload()) {
            boolean changed;
            @Override public synchronized int read(byte[] bytes, int offset, int count) {
                if (!changed) {
                    changed = true;
                    try { Files.createSymbolicLink(new File(files, "pd2").toPath(), external.toPath()); }
                    catch (IOException error) { throw new IllegalStateException(error); }
                }
                return super.read(bytes, offset, count);
            }
        };
        EnvVars env = new EnvVars();
        try { Pd2AudioRuntime.install(files, source, env); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("symbolic")); }
        assertEquals(0, external.list().length);
        assertFalse(env.has("ALSA_CONFIG_PATH"));
    }

    @Test public void shippedManifestMatchesInstallerPins() throws Exception {
        try (InputStream input = RuntimeEnvironment.getApplication().getAssets().open("pd2/audio/manifest.json")) {
            JSONObject manifest = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(Pd2AudioRuntime.REVISION, manifest.getString("revision"));
            assertEquals(Pd2AudioRuntime.MODULE_BYTES, manifest.getInt("bytes"));
            assertEquals(Pd2AudioRuntime.MODULE_SHA256, manifest.getString("sha256"));
            assertEquals(22050, manifest.getInt("sampleRate"));
        }
    }

    private void expectRejected(File files, byte[] image, EnvVars env) throws Exception {
        try { Pd2AudioRuntime.install(files, new ByteArrayInputStream(image), env); fail("Unsafe installation accepted"); }
        catch (IOException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }

    private static Context context(File files) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getFilesDir() { return files; }
        };
    }

    private static byte[] payload() throws IOException {
        try (InputStream input = RuntimeEnvironment.getApplication().getAssets().open("pd2/audio/" + Pd2AudioRuntime.MODULE)) {
            return input.readAllBytes();
        }
    }

    private static File module(File files) { return new File(files, "pd2/runtime/audio/" + Pd2AudioRuntime.MODULE); }
    private static File config(File files) { return new File(files, "pd2/runtime/audio/alsa-eco.conf"); }
    private static Pd2WineSession.Result goodCleanup() { return cleanup("beforeLaunch", 0, 0, true, ""); }
    private static Pd2WineSession.Result cleanup(String phase, int kill, int wait, boolean free, String error) {
        return new Pd2WineSession.Result(phase, kill, wait, free, 1, error);
    }
    private static void assertNoTemporary(File files) {
        File directory = module(files).getParentFile();
        String[] entries = directory.list();
        if (entries != null) for (String name : entries) assertFalse(name.startsWith(".pd2-audio-"));
    }
}
