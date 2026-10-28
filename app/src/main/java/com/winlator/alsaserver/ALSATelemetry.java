package com.winlator.alsaserver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/** One server's bounded diagnostics and pre-join audio cancellation. No PCM data is retained. */
public final class ALSATelemetry {
    private static final int MAX_CLIENT_DETAILS = 8;
    private final ArrayList<ALSAClient> clients = new ArrayList<>();
    private final ArrayList<JSONObject> retired = new ArrayList<>();
    private final long[] totals = new long[ALSAClient.COUNTER_NAMES.length];
    private boolean stopping;
    private long connections, closedConnections;

    public void register(ALSAClient client) {
        boolean cancel;
        synchronized (this) {
            clients.add(client);
            connections = ALSAClient.saturatedAdd(connections, 1);
            cancel = stopping;
        }
        if (cancel) client.stopForShutdown();
    }

    public void unregister(ALSAClient client) {
        JSONObject detail = client.performanceSnapshot();
        long[] counters = client.counterSnapshot();
        synchronized (this) {
            if (!clients.remove(client)) return;
            closedConnections = ALSAClient.saturatedAdd(closedConnections, 1);
            for (int i = 0; i < totals.length; i++) {
                totals[i] = i == 11 ? Math.max(totals[i], counters[i])
                        : ALSAClient.saturatedAdd(totals[i], counters[i]);
            }
            if (retired.size() == MAX_CLIENT_DETAILS) retired.remove(0);
            retired.add(detail);
        }
    }

    public void stopClients() {
        ArrayList<ALSAClient> copy;
        synchronized (this) { stopping = true; copy = new ArrayList<>(clients); }
        for (ALSAClient client : copy) client.stopForShutdown();
    }

    public JSONObject performanceSnapshot() {
        // Retire/register and copying counters share this monitor; blocking writes never do.
        synchronized (this) {
            JSONObject result = new JSONObject();
            JSONArray details = new JSONArray();
            long[] aggregate = totals.clone();
            int detailCount = 0;
            for (ALSAClient client : clients) {
                JSONObject detail = client.performanceSnapshot();
                long[] counters = client.counterSnapshot();
                for (int i = 0; i < aggregate.length; i++) {
                    aggregate[i] = i == 11 ? Math.max(aggregate[i], counters[i])
                            : ALSAClient.saturatedAdd(aggregate[i], counters[i]);
                }
                if (detailCount++ < MAX_CLIENT_DETAILS) details.put(detail);
            }
            if (clients.isEmpty()) for (JSONObject detail : retired) details.put(detail);
            try {
                result.put("backend", "AudioTrack").put("running", !stopping)
                        .put("connections", connections).put("closedConnections", closedConnections)
                        .put("activeClients", clients.size()).put("clients", details)
                        .put("omittedClientDetails", Math.max(0, clients.size() - MAX_CLIENT_DETAILS));
                for (int i = 0; i < aggregate.length; i++) result.put(ALSAClient.COUNTER_NAMES[i], aggregate[i]);
            }
            catch (JSONException ignored) {}
            return result;
        }
    }
}
