package com.example.obd;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single thread-pool plus a UI-marshalling helper that replaces the ad-hoc
 * {@code new Thread(...).start()} calls scattered across the controllers.
 *
 * Why: every controller previously spawned its own anonymous thread for DTC
 * reads, garage refreshes, etc. That made it easy to leak Activity references
 * when the user navigated away mid-call, and it produced no shared logging or
 * back-pressure. Now there is one executor for the whole app, and {@link #postUi}
 * is a no-op if the Activity has been torn down — so a stale callback from a
 * background read can't NPE on a detached view.
 */
public final class WorkDispatcher {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger(1);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ObdWork-" + n.getAndIncrement());
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.setDaemon(true);
            return t;
        }
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WorkDispatcher() {}

    /** Submit a background task. Caller is responsible for catching its own exceptions. */
    public static void io(Runnable task) {
        POOL.execute(task);
    }

    /**
     * Submit a background task that returns a result, then marshals the result onto the
     * UI thread — but only if {@code activity} is non-null and not finishing/destroyed.
     * Exceptions are caught and forwarded to {@code onError} on the UI thread.
     */
    public static <T> void io(Activity activity, IoCall<T> call, OnResult<T> onResult, OnError onError) {
        POOL.execute(() -> {
            T result;
            try {
                result = call.run();
            } catch (Exception e) {
                postUi(activity, () -> { if (onError != null) onError.handle(e); });
                return;
            }
            postUi(activity, () -> { if (onResult != null) onResult.handle(result); });
        });
    }

    /** Post a Runnable to the UI thread iff the activity is still usable. */
    public static void postUi(Activity activity, Runnable r) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        MAIN.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            r.run();
        });
    }

    public interface IoCall<T>  { T run() throws Exception; }
    public interface OnResult<T> { void handle(T result); }
    public interface OnError    { void handle(Exception e); }
}
