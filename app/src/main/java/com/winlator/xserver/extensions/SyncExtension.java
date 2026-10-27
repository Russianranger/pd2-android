package com.winlator.xserver.extensions;

import android.util.SparseBooleanArray;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadFence;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.XRequestError;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

public class SyncExtension extends Extension {
    private final SparseBooleanArray fences = new SparseBooleanArray();
    private static final long WAIT_RECHECK_MS = 50;
    // Fence state, lifecycle and counters share the predicate/wait monitor.
    private boolean stopping;
    private long generation;
    private long awaitRequests, blockingWaits, completedAwaits, cancelledAwaits, interruptedAwaits, waitedNanos;
    private int waitingClients;

    private static abstract class ClientOpcodes {
        private static final byte CREATE_FENCE = 14;
        private static final byte TRIGGER_FENCE = 15;
        private static final byte RESET_FENCE = 16;
        private static final byte DESTROY_FENCE = 17;
        private static final byte AWAIT_FENCE = 19;
    }

    public SyncExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "SYNC";
    }

    @Override
    public byte getErrorCount() {
        return 1;
    }

    public void setTriggered(int id) {
        synchronized (fences) {
            if (fences.indexOfKey(id) >= 0) {
                fences.put(id, true);
                fences.notifyAll();
            }
        }
    }

    /** Stop waiters before the connector joins its request thread. */
    public void cancelAwaiters() {
        synchronized (fences) {
            stopping = true;
            generation++;
            fences.notifyAll();
        }
    }

    /** A restarted connector must not revive a waiter from the previous generation. */
    public void startAwaiters() {
        synchronized (fences) {
            stopping = false;
            generation++;
            fences.notifyAll();
        }
    }

    public JSONObject performanceSnapshot() {
        synchronized (fences) {
            JSONObject report = new JSONObject();
            try {
                report.put("awaitRequests", awaitRequests).put("blockingWaits", blockingWaits)
                        .put("completedAwaits", completedAwaits).put("cancelledAwaits", cancelledAwaits)
                        .put("interruptedAwaits", interruptedAwaits).put("waitedMillis", waitedNanos / 1_000_000)
                        .put("waitingClients", waitingClients).put("running", !stopping)
                        .put("maxRecheckMillis", WAIT_RECHECK_MS)
                        .put("scope", "X Sync fence waits only; counters establish whether this path was used, not a measured temperature benefit.");
            } catch (JSONException ignored) { }
            return report;
        }
    }

    private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1; }

    private boolean isAnyTriggered(int[] ids) throws XRequestError {
        boolean triggered = false;
        for (int id : ids) {
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            triggered |= fences.get(id);
        }
        return triggered;
    }

    void awaitFences(int[] ids) throws IOException, XRequestError {
        synchronized (fences) {
            awaitRequests = increment(awaitRequests);
            if (ids.length == 0) throw new BadValue(0);
            final long startedGeneration = generation;
            for (;;) {
                if (stopping || generation != startedGeneration) {
                    cancelledAwaits = increment(cancelledAwaits);
                    // Abort the request stream rather than dispatching buffered work
                    // from a connector generation that is already shutting down.
                    throw new IOException("X Sync fence wait cancelled");
                }
                if (isAnyTriggered(ids)) {
                    completedAwaits = increment(completedAwaits);
                    return;
                }
                // Checking the predicate and releasing its monitor in wait are atomic.
                // Triggers normally wake immediately; the bounded recheck also handles
                // spurious wakes without returning an untriggered fence as complete.
                blockingWaits = increment(blockingWaits);
                waitingClients++;
                long before = System.nanoTime();
                try {
                    fences.wait(WAIT_RECHECK_MS);
                } catch (InterruptedException interrupted) {
                    interruptedAwaits = increment(interruptedAwaits);
                    Thread.currentThread().interrupt();
                    throw new IOException("X Sync fence wait interrupted", interrupted);
                } finally {
                    waitingClients--;
                    long elapsed = Math.max(0, System.nanoTime() - before);
                    waitedNanos = elapsed > Long.MAX_VALUE - waitedNanos ? Long.MAX_VALUE : waitedNanos + elapsed;
                }
            }
        }
    }

    void createFence(int id, boolean initiallyTriggered) throws XRequestError {
        synchronized (fences) {
            if (fences.indexOfKey(id) >= 0) throw new BadIdChoice(id);
            fences.put(id, initiallyTriggered);
        }
    }

    void triggerFence(int id) throws XRequestError {
        synchronized (fences) {
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            fences.put(id, true);
            fences.notifyAll();
        }
    }

    void resetFence(int id) throws XRequestError {
        synchronized (fences) {
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            if (!fences.get(id)) throw new BadMatch();
            fences.put(id, false);
        }
    }

    void destroyFence(int id) throws XRequestError {
        synchronized (fences) {
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            fences.delete(id);
            fences.notifyAll();
        }
    }

    private void createFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(4);
        int id = inputStream.readInt();
        boolean initiallyTriggered = inputStream.readByte() == 1;
        inputStream.skip(3);
        createFence(id, initiallyTriggered);
    }

    private void triggerFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        triggerFence(inputStream.readInt());
    }

    private void resetFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        resetFence(inputStream.readInt());
    }

    private void destroyFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        destroyFence(inputStream.readInt());
    }

    private void awaitFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int length = client.getRemainingRequestLength();
        if (length <= 0 || (length & 3) != 0) throw new BadValue(length);
        int[] ids = new int[length / 4];
        int i = 0;

        while (length != 0) {
            ids[i++] = inputStream.readInt();
            length -= 4;
        }

        awaitFences(ids);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.CREATE_FENCE :
                createFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.TRIGGER_FENCE:
                triggerFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.RESET_FENCE:
                resetFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_FENCE:
                destroyFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.AWAIT_FENCE:
                awaitFence(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
}
