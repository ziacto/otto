package com.example.obd;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

public class DebugLogController {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private View root;
    private Runnable refresher;

    public void attach(View view) {
        this.root = view;
        TextView log = view.findViewById(R.id.tvLogContent);
        log.setMovementMethod(new ScrollingMovementMethod());

        TextView path = view.findViewById(R.id.tvLogFilePath);
        java.io.File diag = ObdLogger.get().getDiagFile();
        if (path != null) {
            path.setText(diag == null
                    ? "File log: (not initialized)"
                    : "File log: " + diag.getAbsolutePath());
        }

        TextView rawPath = view.findViewById(R.id.tvRawFilePath);
        java.io.File raw = RawFrameLogger.get().getRawFile();
        if (rawPath != null) {
            rawPath.setText(raw == null
                    ? "Raw log: (not initialized)"
                    : "Raw log: " + raw.getAbsolutePath());
        }

        SwitchCompat swRaw = view.findViewById(R.id.swRawCapture);
        if (swRaw != null) {
            swRaw.setChecked(RawFrameLogger.get().isEnabled());
            swRaw.setOnCheckedChangeListener((b, on) -> {
                RawFrameLogger.get().setEnabled(on);
                Toast.makeText(b.getContext(),
                        on ? "Raw capture ON" : "Raw capture OFF",
                        Toast.LENGTH_SHORT).show();
            });
        }

        Button btnRefresh = view.findViewById(R.id.btnRefreshLog);
        Button btnClear = view.findViewById(R.id.btnClearLog);
        Button btnShare = view.findViewById(R.id.btnShareLog);

        refreshNow(log);

        btnRefresh.setOnClickListener(v -> refreshNow(log));
        btnClear.setOnClickListener(v -> {
            ObdLogger.get().clear();
            refreshNow(log);
            Toast.makeText(v.getContext(), "Log cleared", Toast.LENGTH_SHORT).show();
        });
        btnShare.setOnClickListener(v -> share(v.getContext()));

        // Auto-refresh every 2 seconds while visible
        refresher = new Runnable() {
            @Override
            public void run() {
                if (root == null) return;
                refreshNow(log);
                ui.postDelayed(this, 2000);
            }
        };
        ui.postDelayed(refresher, 2000);
    }

    public void detach() {
        root = null;
        if (refresher != null) ui.removeCallbacks(refresher);
    }

    private void refreshNow(TextView log) {
        String text = ObdLogger.get().render();
        log.setText(text.isEmpty() ? "Log is empty. Use the app and OBD events will appear here." : text);
    }

    private void share(Context ctx) {
        String text = ObdLogger.get().render();
        if (text.isEmpty()) {
            Toast.makeText(ctx, "Log is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "OBD debug log");
        send.putExtra(Intent.EXTRA_TEXT, text);
        ctx.startActivity(Intent.createChooser(send, "Share OBD log"));
    }
}
