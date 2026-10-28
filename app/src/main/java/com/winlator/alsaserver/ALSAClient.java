package com.winlator.alsaserver;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import com.winlator.contentdialog.AudioDriverConfigDialog;
import com.winlator.core.KeyValueSet;
import com.winlator.sysvshm.SysVSharedMemory;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.LongSupplier;

public class ALSAClient {
    public static final boolean USE_SHARED_MEMORY = true;
    public static final byte BUFFER_OFFSET = 4;
    public static final int MAX_BUFFER_BYTES = 16 * 1024 * 1024;
    public enum DataType {
        U8(1), S16LE(2), S16BE(2), FLOATLE(4), FLOATBE(4);
        public final byte byteCount;
        DataType(int byteCount) { this.byteCount = (byte)byteCount; }
    }
    /** Tests exercise the real write/lifecycle code without an Android audio device. */
    interface Track {
        int write(ByteBuffer data, int bytes);
        void play(); void pause(); void stop(); void flush(); void release();
        int playState(); int sampleRate(); int bufferSize(); int bufferCapacity();
        int underruns(); int resize(int frames);
    }
    interface TrackFactory { Track create(AudioFormat format, int bytes, Options options); }
    private static final class AndroidTrack implements Track {
        private final AudioTrack track;
        AndroidTrack(AudioFormat format, int bytes, Options options) {
            track = new AudioTrack.Builder().setPerformanceMode(options.performanceMode)
                    .setAudioFormat(format).setBufferSizeInBytes(bytes).build();
            if (options.volume != 1.0f) track.setVolume(options.volume);
        }
        public int write(ByteBuffer data, int bytes) { return track.write(data, bytes, AudioTrack.WRITE_BLOCKING); }
        public void play() { track.play(); }
        public void pause() { track.pause(); }
        public void stop() { track.stop(); }
        public void flush() { track.flush(); }
        public void release() { track.release(); }
        public int playState() { return track.getPlayState(); }
        public int sampleRate() { return track.getSampleRate(); }
        public int bufferSize() { return track.getBufferSizeInFrames(); }
        public int bufferCapacity() { return track.getBufferCapacityInFrames(); }
        public int underruns() { return track.getUnderrunCount(); }
        public int resize(int frames) { return track.setBufferSizeInFrames(frames); }
    }
    private DataType dataType = DataType.U8;
    private int channels = 2, sampleRate, bufferSize, frameBytes;
    private volatile long positionBytes;
    private volatile Track audioTrack;
    private volatile boolean closing;
    private final Object lifecycle = new Object(), statistics = new Object();
    private final TrackFactory factory;
    private final LongSupplier clock;
    private ByteBuffer sharedBuffer, auxBuffer;
    protected final Options options;
    private static volatile int framesPerBuffer = 256;
    static final String[] COUNTER_NAMES = {"preparedTracks", "prepareFailures", "writeRequests", "writeCalls",
            "requestedBytes", "writtenBytes", "shortWrites", "zeroWrites", "writeErrors", "writeExceptions",
            "writeNanos", "maxWriteNanos", "observedUnderruns", "bufferGrowths", "shutdowns"};
    private final long[] counters = new long[COUNTER_NAMES.length];
    private int actualSampleRate, activeFrames, capacityFrames, currentUnderruns, previousUnderruns, handledUnderruns;
    private int lastWriteError;

    public static class Options {
        public int latencyMillis = AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS;
        public byte performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
        public float volume = AudioDriverConfigDialog.DEFAULT_VOLUME;
        public static Options fromKeyValueSet(KeyValueSet config) {
            Options result = new Options();
            if (config == null || config.isEmpty()) return result;
            int mode = config.getInt("performanceMode", AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            if (mode >= AudioTrack.PERFORMANCE_MODE_NONE && mode <= AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                result.performanceMode = (byte)mode;
            float volume = config.getFloat("volume", AudioDriverConfigDialog.DEFAULT_VOLUME);
            if (Float.isFinite(volume)) result.volume = Math.max(0, Math.min(1, volume));
            int latency = config.getInt("latencyMillis", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS);
            if (latency >= 8 && latency <= 256) result.latencyMillis = latency;
            return result;
        }
    }
    public ALSAClient(Options options) { this(options, AndroidTrack::new, System::nanoTime); }
    ALSAClient(Options options, TrackFactory factory, LongSupplier clock) {
        this.options = options; this.factory = factory; this.clock = clock;
    }
    /** Pause before the connector joins its writer; keep SHM mapped until that join finishes. */
    public void stopForShutdown() {
        Track track;
        synchronized (lifecycle) {
            if (closing) return;
            closing = true; track = audioTrack;
        }
        increment(14, 1);
        if (track != null) try { track.pause(); } catch (RuntimeException ignored) {}
    }
    public void release() {
        Track track;
        synchronized (lifecycle) {
            track = audioTrack;
            if (track != null) refreshHardware(track);
            audioTrack = null;
        }
        if (track != null) {
            try { track.pause(); } catch (RuntimeException ignored) {}
            try { track.flush(); } catch (RuntimeException ignored) {}
            try { track.release(); } catch (RuntimeException ignored) {}
        }
        if (sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
            sharedBuffer = null;
        }
        auxBuffer = null;
    }
    public static int getPCMEncoding(DataType type) {
        switch (type) {
            case U8: return AudioFormat.ENCODING_PCM_8BIT;
            case S16LE: case S16BE: return AudioFormat.ENCODING_PCM_16BIT;
            case FLOATLE: case FLOATBE: return AudioFormat.ENCODING_PCM_FLOAT;
            default: return AudioFormat.ENCODING_DEFAULT;
        }
    }
    public static int getChannelConfig(int channels) {
        return channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
    }
    public static boolean validFormat(int channels, DataType type, int rate) {
        return (channels == 1 || channels == 2) && type != null && rate >= 4000 && rate <= 192000;
    }
    public static boolean validBuffer(int frames, int channels, DataType type, int rate) {
        return validFormat(channels, type, rate) && frames > 0
                && (long)frames * channels * type.byteCount <= MAX_BUFFER_BYTES;
    }
    public void prepare() {
        release(); positionBytes = 0;
        frameBytes = dataType == null ? 0 : channels * dataType.byteCount;
        synchronized (statistics) {
            previousUnderruns = currentUnderruns = handledUnderruns = 0;
            actualSampleRate = activeFrames = capacityFrames = 0;
        }
        if (closing || !validBuffer(bufferSize, channels, dataType, sampleRate)) {
            increment(1, 1); return;
        }
        Track created = null;
        try {
            AudioFormat format = new AudioFormat.Builder().setEncoding(getPCMEncoding(dataType))
                    .setSampleRate(sampleRate).setChannelMask(getChannelConfig(channels)).build();
            created = factory.create(format, getBufferSizeInBytes(), options);
            Track current = created;
            synchronized (lifecycle) {
                if (!closing) {
                    audioTrack = created;
                    created = null;
                    // A blocking WRITE must not wait for START on its own request thread.
                    current.play();
                }
            }
            if (created != null) { created.release(); return; }
            increment(0, 1); refreshHardware(current);
        }
        catch (RuntimeException failure) {
            increment(1, 1); release();
            if (created != null) try { created.release(); } catch (RuntimeException ignored) {}
        }
    }
    public void start() {
        synchronized (lifecycle) {
            Track track = audioTrack;
            if (!closing && track != null && track.playState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
        }
    }
    public void stop() { Track track = audioTrack; if (track != null) { track.stop(); track.flush(); } }
    public void pause() { Track track = audioTrack; if (track != null) track.pause(); }
    public void drain() { Track track = audioTrack; if (track != null) track.flush(); }
    public void writeDataToTrack(ByteBuffer data) {
        Track track = audioTrack;
        if (closing || track == null || data == null) return;
        data.order(dataType == DataType.S16BE || dataType == DataType.FLOATBE
                ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        data.position(0); increment(2, 1); increment(4, data.remaining());
        while (data.hasRemaining() && !closing && audioTrack == track) {
            int requested = data.remaining(), before = data.position();
            long started = clock.getAsLong();
            int result;
            try { result = track.write(data, requested); }
            catch (RuntimeException failure) { result = Integer.MIN_VALUE; increment(9, 1); }
            long elapsed = Math.max(0, clock.getAsLong() - started);
            int progress = Math.max(0, data.position() - before);
            synchronized (statistics) {
                add(3, 1); add(5, progress); add(10, elapsed);
                counters[11] = Math.max(counters[11], elapsed);
                if (result < 0) { add(8, 1); lastWriteError = result; }
                else if (result == 0 || progress == 0) add(7, 1);
                else if (result < requested) add(6, 1);
            }
            positionBytes += progress;
            // Zero progress returns to the request loop instead of retrying forever.
            if (result <= 0 || progress == 0) break;
            observeUnderruns(track); growAfterUnderrun(track);
        }
        data.rewind();
    }
    private void refreshHardware(Track track) {
        try {
            int rate = track.sampleRate(), size = track.bufferSize(), capacity = track.bufferCapacity();
            synchronized (statistics) {
                if (audioTrack != track) return;
                actualSampleRate = rate;
                // This client only grows an active track. A pre-resize sampler cannot shrink its view.
                activeFrames = Math.max(activeFrames, size);
                capacityFrames = capacity;
            }
            observeUnderruns(track);
        }
        catch (RuntimeException ignored) {}
    }
    private void observeUnderruns(Track track) {
        try {
            int underruns = Math.max(0, track.underruns());
            synchronized (statistics) {
                if (audioTrack != track) return;
                currentUnderruns = Math.max(currentUnderruns, underruns);
                if (underruns > previousUnderruns) add(12, underruns - previousUnderruns);
                previousUnderruns = Math.max(previousUnderruns, underruns);
            }
        }
        catch (RuntimeException ignored) {}
    }
    private void growAfterUnderrun(Track track) {
        int target;
        synchronized (statistics) {
            if (currentUnderruns <= handledUnderruns) return;
            handledUnderruns = currentUnderruns;
            if (activeFrames >= capacityFrames) return;
            target = (int)Math.min((long)capacityFrames, (long)activeFrames + framesPerBuffer);
        }
        try {
            if (track.resize(target) > 0) { increment(13, 1); refreshHardware(track); }
        }
        catch (RuntimeException ignored) {}
    }
    private void increment(int index, long amount) { synchronized (statistics) { add(index, amount); } }
    private void add(int index, long amount) { counters[index] = saturatedAdd(counters[index], amount); }
    static long saturatedAdd(long value, long amount) {
        return amount > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + Math.max(0, amount);
    }
    long[] counterSnapshot() { synchronized (statistics) { return counters.clone(); } }
    /** No lock used here is held across a blocking write. */
    public JSONObject performanceSnapshot() {
        Track track = audioTrack;
        if (track != null) refreshHardware(track);
        JSONObject result = new JSONObject();
        synchronized (statistics) {
            try {
                result.put("prepared", track != null).put("closing", closing)
                        .put("requestedLatencyMillis", options.latencyMillis).put("performanceMode", options.performanceMode)
                        .put("requestedSampleRate", sampleRate).put("sampleRate", actualSampleRate)
                        .put("channels", channels).put("encoding", dataType == null ? "invalid" : dataType.name())
                        .put("requestedBufferFrames", bufferSize).put("requestedBufferBytes", getBufferSizeInBytes())
                        .put("bufferSizeFrames", activeFrames)
                        .put("bufferCapacityFrames", capacityFrames).put("framesPerBurst", framesPerBuffer)
                        .put("bufferSizeMillis", actualSampleRate > 0 ? activeFrames * 1000.0 / actualSampleRate : 0)
                        .put("bufferCapacityMillis", actualSampleRate > 0 ? capacityFrames * 1000.0 / actualSampleRate : 0)
                        .put("underrunCount", currentUnderruns).put("lastWriteError", lastWriteError);
                for (int i = 0; i < counters.length; i++) result.put(COUNTER_NAMES[i], counters[i]);
            }
            catch (JSONException ignored) {}
        }
        return result;
    }
    /** Preserve the driver's 32-bit frame counter ABI without an overflowing signed byte counter. */
    public int pointer() { return audioTrack != null && frameBytes > 0 ? (int)(positionBytes / frameBytes) : 0; }
    public void setDataType(DataType type) { dataType = type; }
    public void setChannels(int value) { channels = value; }
    public void setSampleRate(int value) { sampleRate = value; }
    public void setBufferSize(int value) { bufferSize = value; }
    public ByteBuffer getSharedBuffer() { return sharedBuffer; }
    public void setSharedBuffer(ByteBuffer buffer) {
        auxBuffer = buffer == null ? null : ByteBuffer.allocateDirect(getBufferSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
        sharedBuffer = buffer == null ? null : buffer.order(ByteOrder.LITTLE_ENDIAN);
    }
    public ByteBuffer getAuxBuffer() { return auxBuffer; }
    public DataType getDataType() { return dataType; }
    public byte getChannels() { return (byte)channels; }
    public int getSampleRate() { return sampleRate; }
    public int getBufferSize() { return bufferSize; }
    public int getBufferSizeInBytes() {
        return validBuffer(bufferSize, channels, dataType, sampleRate) ? bufferSize * channels * dataType.byteCount : 0;
    }
    public static int bufferSizeToLatencyMillis(int bytes, int channels, DataType type, int rate) {
        if (bytes < 0 || !validFormat(channels, type, rate)) return 0;
        return (int)((long)bytes * 1000 / (channels * type.byteCount) / rate);
    }
    public static int latencyMillisToBufferSize(int latency, int channels, DataType type, int rate) {
        if (latency < 8 || latency > 256 || !validFormat(channels, type, rate)) return 0;
        // Round upward to a hardware burst, never below the requested latency.
        long frames = ((long)latency * rate + 999) / 1000;
        int burst = framesPerBuffer;
        frames = ((frames + burst - 1) / burst) * burst;
        long bytes = frames * channels * type.byteCount;
        return bytes <= MAX_BUFFER_BYTES ? (int)bytes : 0;
    }
    public static void assignFramesPerBuffer(Context context) {
        int frames = 256;
        try {
            AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            frames = Integer.parseInt(manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
        }
        catch (RuntimeException ignored) {}
        framesPerBuffer = frames > 0 && frames <= 8192 ? frames : 256;
    }
}
