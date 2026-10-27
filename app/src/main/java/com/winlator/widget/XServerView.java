package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;
import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.xserver.extensions.SyncExtension;
import org.json.JSONException;
import org.json.JSONObject;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private final GLRenderer renderer;
    private final SurfaceFrameRateRequest frameRateRequest;
    private final XServer xServer;

    public XServerView(Context context, XServer xServer) {
        this(context, xServer, 0f);
    }

    public XServerView(Context context, XServer xServer, float requestedFrameRate) {
        super(context);
        this.xServer = xServer;
        frameRateRequest = new SurfaceFrameRateRequest(requestedFrameRate);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                frameRateRequest.surfaceAvailable(holder.getSurface());
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                frameRateRequest.surfaceAvailable(holder.getSurface());
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { frameRateRequest.surfaceDestroyed(); }
        });
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public JSONObject frameRateSnapshot() {
        JSONObject snapshot = frameRateRequest.snapshot();
        // Sampled by the existing diagnostics worker, never serialized per frame.
        try {
            snapshot.put("presentation", ((PresentExtension) xServer.getExtensionByName("Present")).performanceSnapshot());
            snapshot.put("synchronization", ((SyncExtension) xServer.getExtensionByName("SYNC")).performanceSnapshot());
            snapshot.put("compositor", renderer.performanceSnapshot());
        } catch (JSONException ignored) { }
        return snapshot;
    }
}
