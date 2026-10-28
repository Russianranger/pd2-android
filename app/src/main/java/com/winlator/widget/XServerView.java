package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
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
    private volatile ContentFrameCoalescer contentFrames;
    private boolean pacingAttached, pacingResumed = true;

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

    /** Configure only the managed PD2 session. This does not cap guest rendering. */
    public void configureContentFramePacing(int fps) {
        if (contentFrames == null) {
            contentFrames = new ContentFrameCoalescer(fps, System::nanoTime,
                    new MainScheduler(), this::forwardContentRender);
            contentFrames.setResumed(pacingResumed);
            contentFrames.setAttached(pacingAttached);
        } else contentFrames.setLimit(fps);
    }

    /** Guest content updates may merge; cursor and control changes use requestRender directly. */
    public void requestContentRender() {
        ContentFrameCoalescer frames = contentFrames;
        if (frames == null) super.requestRender();
        else frames.request();
    }

    private void forwardContentRender() { super.requestRender(); }

    @Override public void requestRender() {
        ContentFrameCoalescer frames = contentFrames;
        if (frames != null) frames.immediate();
        super.requestRender();
    }

    @Override public void onPause() {
        pacingResumed = false;
        if (contentFrames != null) contentFrames.setResumed(false);
        super.onPause();
    }

    @Override public void onResume() {
        super.onResume();
        pacingResumed = true;
        if (contentFrames != null) contentFrames.setResumed(true);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pacingAttached = true;
        if (contentFrames != null) contentFrames.setAttached(true);
    }

    @Override protected void onDetachedFromWindow() {
        pacingAttached = false;
        if (contentFrames != null) contentFrames.setAttached(false);
        super.onDetachedFromWindow();
    }

    public void closeRenderPacing() {
        ContentFrameCoalescer frames = contentFrames;
        if (frames != null) frames.close();
    }

    private static final class MainScheduler implements ContentFrameCoalescer.Scheduler {
        private final Handler handler = new Handler(Looper.getMainLooper());
        @Override public boolean postDelayed(Runnable callback, long milliseconds) {
            return handler.postDelayed(callback, milliseconds);
        }
        @Override public void remove(Runnable callback) { handler.removeCallbacks(callback); }
    }

    public JSONObject frameRateSnapshot() {
        JSONObject snapshot = frameRateRequest.snapshot();
        // Sampled by the existing diagnostics worker, never serialized per frame.
        try {
            snapshot.put("presentation", ((PresentExtension) xServer.getExtensionByName("Present")).performanceSnapshot());
            snapshot.put("synchronization", ((SyncExtension) xServer.getExtensionByName("SYNC")).performanceSnapshot());
            snapshot.put("compositor", renderer.performanceSnapshot());
            ContentFrameCoalescer frames = contentFrames;
            if (frames != null) {
                ContentFrameCoalescer.Snapshot counters = frames.snapshot();
                snapshot.put("contentRedraw", new JSONObject().put("requestedFps", counters.fps)
                        .put("requests", counters.requests).put("coalescedRequests", counters.coalesced)
                        .put("deferredCallbacks", counters.deferredCallbacks).put("forwardedRequests", counters.forwarded)
                        .put("immediateRequests", counters.immediateRequests).put("cancellations", counters.cancellations)
                        .put("postFailures", counters.postFailures).put("attached", counters.attached)
                        .put("resumed", counters.resumed).put("closed", counters.closed)
                        .put("pending", counters.scheduled).put("dirty", counters.dirty)
                        .put("scope", "Android content invalidation coalescing only; cursor/control redraws stay immediate. Not guest FPS, GPU work or measured frames saved."));
            }
        } catch (JSONException ignored) { }
        return snapshot;
    }
}
