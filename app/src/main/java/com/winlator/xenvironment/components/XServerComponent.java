package com.winlator.xenvironment.components;

import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xserver.XClientConnectionHandler;
import com.winlator.xserver.XClientRequestHandler;
import com.winlator.xserver.XServer;
import com.winlator.xserver.extensions.SyncExtension;

public class XServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;

    public XServerComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        xServer.getExtension(SyncExtension.class).startAwaiters();
        connector = new XConnectorEpoll(socketConfig, new XClientConnectionHandler(xServer), new XClientRequestHandler());
        connector.setInitialInputBufferCapacity(4096);
        connector.setInitialOutputBufferCapacity(4096);
        connector.setCanReceiveAncillaryMessages(true);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            // A request thread may be blocked inside SYNC AwaitFence. Cancellation
            // must precede the connector's native stop/join, not client.destroy().
            xServer.getExtension(SyncExtension.class).cancelAwaiters();
            connector.destroy();
            connector = null;
        }
    }

    public XServer getXServer() {
        return xServer;
    }
}
