package com.winlator.alsaserver;

import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.ConnectionHandler;

public class ALSAClientConnectionHandler implements ConnectionHandler {
    private final ALSAClient.Options options;
    private final ALSATelemetry telemetry;

    public ALSAClientConnectionHandler(ALSAClient.Options options) {
        this(options, null);
    }

    public ALSAClientConnectionHandler(ALSAClient.Options options, ALSATelemetry telemetry) {
        this.options = options;
        this.telemetry = telemetry;
    }

    @Override
    public void handleNewConnection(ConnectedClient client) {
        ALSAClient audio = new ALSAClient(options);
        client.setTag(audio);
        if (telemetry != null) telemetry.register(audio);
    }

    @Override
    public void handleConnectionShutdown(ConnectedClient client) {
        if (client.getTag() != null) {
            ALSAClient audio = (ALSAClient)client.getTag();
            audio.release();
            if (telemetry != null) telemetry.unregister(audio);
        }
    }
}
