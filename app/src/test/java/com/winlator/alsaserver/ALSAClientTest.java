package com.winlator.alsaserver;

import android.app.Application;
import android.media.AudioTrack;
import com.winlator.core.KeyValueSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class ALSAClientTest {
    private static final class Track implements ALSAClient.Track {
        int[] results = {};
        int index, plays, rate = 44100, frames = 1000, capacity = 1300, underruns, resizes;
        CountDownLatch writing, paused;
        CountDownLatch sampleRead, returnSample;
        String stalled;
        private int sample(String kind, int value) {
            CountDownLatch ready = null, done = null;
            synchronized (this) {
                if (kind.equals(stalled)) {
                    stalled = null;
                    ready = sampleRead;
                    done = returnSample;
                }
            }
            if (ready != null) {
                ready.countDown();
                try { assertTrue("sample not released", done.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            }
            return value;
        }
        public int write(ByteBuffer data, int bytes) {
            if (writing != null) {
                writing.countDown();
                try {
                    if (!paused.await(2, TimeUnit.SECONDS)) throw new AssertionError("pause did not unblock write");
                }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return 0;
            }
            int result = index < results.length ? results[index++] : bytes;
            if (result > 0) data.position(data.position() + result);
            return result;
        }
        public void play() { plays++; }
        public void pause() { if (paused != null) paused.countDown(); }
        public void stop() {}
        public void flush() {}
        public void release() {}
        public int playState() { return AudioTrack.PLAYSTATE_PLAYING; }
        public int sampleRate() { return sample("rate", rate); }
        public int bufferSize() { return frames; }
        public int bufferCapacity() { return capacity; }
        public int underruns() { return sample("underruns", underruns); }
        public int resize(int value) { resizes++; frames = value; return value; }
    }

    private ALSAClient client(Track track) {
        AtomicLong time = new AtomicLong();
        ALSAClient client = new ALSAClient(new ALSAClient.Options(), (format, bytes, options) -> track,
                () -> time.getAndAdd(10));
        client.setChannels(2);
        client.setDataType(ALSAClient.DataType.S16LE);
        client.setSampleRate(44100);
        client.setBufferSize(1024);
        client.prepare();
        return client;
    }

    @Test public void zeroProgressEndsWriteAndAccountsOnlyAcceptedFrames() throws Exception {
        Track track = new Track();
        track.results = new int[]{4, 0};
        ALSAClient client = client(track);
        client.writeDataToTrack(ByteBuffer.allocate(16));
        assertEquals(1, track.plays);
        assertEquals(1, client.pointer());
        assertEquals(2, client.performanceSnapshot().getLong("writeCalls"));
        assertEquals(1, client.performanceSnapshot().getLong("shortWrites"));
        assertEquals(1, client.performanceSnapshot().getLong("zeroWrites"));
        assertEquals(4, client.performanceSnapshot().getLong("writtenBytes"));
        assertEquals(20, client.performanceSnapshot().getLong("writeNanos"));
    }

    @Test public void negativeResultDoesNotAdvancePointerOrRetryForever() throws Exception {
        Track track = new Track();
        track.results = new int[]{-6};
        ALSAClient client = client(track);
        client.writeDataToTrack(ByteBuffer.allocate(16));
        assertEquals(0, client.pointer());
        assertEquals(1, client.performanceSnapshot().getLong("writeErrors"));
        assertEquals(-6, client.performanceSnapshot().getInt("lastWriteError"));
    }

    @Test public void underrunsDoNotWrapAtShortLimitAndGrowthStopsAtActualCapacity() throws Exception {
        Track track = new Track();
        ALSAClient client = client(track);
        track.underruns = 40000;
        client.writeDataToTrack(ByteBuffer.allocate(4));
        assertEquals(1256, track.frames);
        track.underruns++;
        client.writeDataToTrack(ByteBuffer.allocate(4));
        assertEquals(1300, track.frames);
        track.underruns++;
        client.writeDataToTrack(ByteBuffer.allocate(4));
        assertEquals(2, track.resizes);
        assertEquals(40002, client.performanceSnapshot().getLong("observedUnderruns"));
        assertEquals(44100, client.performanceSnapshot().getInt("sampleRate"));
    }

    @Test public void snapshotAndShutdownDoNotWaitForBlockingWrite() throws Exception {
        Track track = new Track();
        track.writing = new CountDownLatch(1);
        track.paused = new CountDownLatch(1);
        ALSAClient client = client(track);
        Thread writer = new Thread(() -> client.writeDataToTrack(ByteBuffer.allocate(4)));
        writer.start();
        try {
            assertTrue(track.writing.await(1, TimeUnit.SECONDS));
            assertTrue(client.performanceSnapshot().getBoolean("prepared"));
            client.stopForShutdown();
            writer.join(500);
            assertFalse(writer.isAlive());
            client.start();
            client.prepare();
            assertEquals(1, track.plays);
        }
        finally {
            client.stopForShutdown();
            writer.join(2000);
            client.release();
        }
    }

    @Test public void bufferNegotiationRoundsUpAndRejectsOverflow() {
        int bytes = ALSAClient.latencyMillisToBufferSize(32, 2, ALSAClient.DataType.S16LE, 22050);
        assertEquals(0, bytes % (256 * 4));
        assertTrue((long)bytes * 1000 >= 32L * 22050 * 4);
        assertEquals(0, ALSAClient.latencyMillisToBufferSize(Integer.MAX_VALUE, 2,
                ALSAClient.DataType.FLOATLE, 192000));
        assertFalse(ALSAClient.validBuffer(Integer.MAX_VALUE, 2, ALSAClient.DataType.FLOATLE, 192000));
        assertTrue(ALSAClient.validBuffer(1025, 2, ALSAClient.DataType.S16LE, 44100));
        assertEquals(16, ALSAClient.Options.fromKeyValueSet(new KeyValueSet("latencyMillis=65568")).latencyMillis);
    }

    @Test public void telemetryKeepsTotalsAfterCloseAndBoundsRetainedDetails() throws Exception {
        ALSATelemetry telemetry = new ALSATelemetry();
        for (int index = 0; index < 12; index++) {
            ALSAClient client = client(new Track());
            telemetry.register(client);
            client.writeDataToTrack(ByteBuffer.allocate(4));
            client.release();
            telemetry.unregister(client);
        }
        assertEquals(48, telemetry.performanceSnapshot().getLong("writtenBytes"));
        assertEquals(12, telemetry.performanceSnapshot().getLong("closedConnections"));
        assertEquals(8, telemetry.performanceSnapshot().getJSONArray("clients").length());
    }

    @Test public void staleUnderrunSampleCannotLowerTheBaselineAndDoubleCount() throws Exception {
        Track track = new Track();
        ALSAClient client = client(track);
        track.underruns = 397;
        track.stalled = "underruns";
        track.sampleRead = new CountDownLatch(1);
        track.returnSample = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sampler = new Thread(() -> {
            try { client.performanceSnapshot(); }
            catch (Throwable error) { failure.set(error); }
        });
        sampler.start();
        try {
            assertTrue(track.sampleRead.await(1, TimeUnit.SECONDS));
            track.underruns = 400;
            client.writeDataToTrack(ByteBuffer.allocate(4));
        }
        finally { track.returnSample.countDown(); sampler.join(2000); }
        assertFalse(sampler.isAlive());
        assertNull(failure.get());
        assertEquals(400, client.performanceSnapshot().getInt("underrunCount"));
        assertEquals(400, client.performanceSnapshot().getLong("observedUnderruns"));
    }

    @Test public void retiredTrackObservationCannotOverwriteReplacementHardwareState() throws Exception {
        Track old = new Track(), replacement = new Track();
        replacement.rate = 22050;
        replacement.frames = 256;
        replacement.capacity = 512;
        replacement.underruns = 2;
        AtomicInteger generation = new AtomicInteger();
        ALSAClient client = new ALSAClient(new ALSAClient.Options(), (format, bytes, options) ->
                generation.getAndIncrement() == 0 ? old : replacement, System::nanoTime);
        client.setChannels(2);
        client.setDataType(ALSAClient.DataType.S16LE);
        client.setSampleRate(44100);
        client.setBufferSize(1024);
        client.prepare();
        old.stalled = "rate";
        old.sampleRead = new CountDownLatch(1);
        old.returnSample = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sampler = new Thread(() -> {
            try { client.performanceSnapshot(); }
            catch (Throwable error) { failure.set(error); }
        });
        sampler.start();
        try {
            assertTrue(old.sampleRead.await(1, TimeUnit.SECONDS));
            client.setSampleRate(22050);
            client.setBufferSize(256);
            client.prepare();
            old.underruns = 999;
        }
        finally { old.returnSample.countDown(); sampler.join(2000); }
        assertFalse(sampler.isAlive());
        assertNull(failure.get());
        assertEquals(22050, client.performanceSnapshot().getInt("sampleRate"));
        assertEquals(256, client.performanceSnapshot().getInt("bufferSizeFrames"));
        assertEquals(512, client.performanceSnapshot().getInt("bufferCapacityFrames"));
        assertEquals(2, client.performanceSnapshot().getLong("observedUnderruns"));
    }
}
