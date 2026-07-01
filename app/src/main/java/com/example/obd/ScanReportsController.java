package com.example.obd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.FileProvider;

import com.example.obd.db.AppDatabase;
import com.example.obd.db.ScanReport;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Drawer-screen controller for the Scan Reports list + detail view.
 *
 * List view: shows every saved scan (DTC, module, AI estimate), newest first.
 * Tapping a row replaces the content with a WebView detail screen that renders
 * the stored HTML. The Share button on the detail screen exports the HTML
 * through a FileProvider so the user can hand it to a mechanic via
 * WhatsApp / email / drive.
 *
 * No bottom-sheet / fragment plumbing — the controller swaps its own root
 * subtree between list and detail by re-inflating in place.
 */
public class ScanReportsController {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private View root;
    private Activity activity;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MMM dd, HH:mm", Locale.US);

    public void attach(View view, Activity act) {
        this.root = view;
        this.activity = act;
        showList();
    }

    public void detach() {
        root = null;
        activity = null;
        ui.removeCallbacksAndMessages(null);
    }

    public boolean isAttached() { return root != null; }

    private void showList() {
        if (root == null || activity == null) return;
        final View v = root;
        final Activity act = activity;

        AppCompatButton btnClear = v.findViewById(R.id.btnReportsClear);
        TextView tvCount = v.findViewById(R.id.tvReportsCount);
        LinearLayout container = v.findViewById(R.id.reportsContainer);
        if (container == null) return;
        container.removeAllViews();

        new Thread(() -> {
            final List<ScanReport> rows;
            try {
                rows = AppDatabase.get(act.getApplicationContext())
                        .scanReports().recentMeta(500);
            } catch (Exception e) {
                ObdLogger.get().log(ObdLogger.Level.ERROR,
                        "ScanReport list query failed: " + e.getMessage());
                return;
            }
            ui.post(() -> {
                if (root == null) return;
                tvCount.setText(rows.size() + " saved");
                if (rows.isEmpty()) {
                    TextView empty = new TextView(act);
                    empty.setText("No scans saved yet. Run a Full Scan from Fault Codes, or generate an AI estimate.");
                    empty.setTextColor(Color.parseColor("#9098A8"));
                    empty.setTextSize(13f);
                    empty.setPadding(dp(act, 8), dp(act, 24), dp(act, 8), 0);
                    container.addView(empty);
                    return;
                }
                for (ScanReport r : rows) container.addView(buildRow(act, r));
            });
        }, "ScanReportList").start();

        if (btnClear != null) {
            btnClear.setOnClickListener(view ->
                    new AlertDialog.Builder(act)
                            .setTitle("Clear all reports?")
                            .setMessage("This removes every saved scan from the local database. The HTML can't be recovered.")
                            .setPositiveButton("Clear", (d, w) -> clearAll())
                            .setNegativeButton("Cancel", null)
                            .show());
        }
    }

    private View buildRow(Activity act, ScanReport r) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(Color.parseColor("#152633"));
        int pad = dp(act, 12);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(act, 8));
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setFocusable(true);

        TextView kind = new TextView(act);
        kind.setText(prettyKind(r.kind));
        kind.setTextColor(kindColor(r.kind));
        kind.setTextSize(10f);
        kind.setLetterSpacing(0.12f);
        kind.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(kind);

        TextView title = new TextView(act);
        title.setText(r.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(act, 4), 0, 0);
        row.addView(title);

        TextView meta = new TextView(act);
        StringBuilder mb = new StringBuilder(FMT.format(new Date(r.ts)));
        if (r.vin != null && !r.vin.isEmpty()) mb.append(" · VIN ").append(r.vin);
        meta.setText(mb.toString());
        meta.setTextColor(Color.parseColor("#9098A8"));
        meta.setTextSize(11f);
        meta.setPadding(0, dp(act, 4), 0, 0);
        row.addView(meta);

        row.setOnClickListener(v -> openDetail(r.id));
        row.setOnLongClickListener(v -> { confirmDelete(r); return true; });
        return row;
    }

    private void openDetail(long id) {
        if (root == null || activity == null) return;
        final Activity act = activity;
        new Thread(() -> {
            final ScanReport r;
            try {
                r = AppDatabase.get(act.getApplicationContext()).scanReports().byId(id);
            } catch (Exception e) { return; }
            if (r == null) return;
            ui.post(() -> {
                if (root == null) return;
                showDetail(r);
            });
        }, "ScanReportLoad").start();
    }

    @SuppressWarnings("deprecation")
    private void showDetail(ScanReport r) {
        if (!(root.getParent() instanceof ViewGroup)) return;
        final ViewGroup parent = (ViewGroup) root.getParent();
        final View detail = activity.getLayoutInflater()
                .inflate(R.layout.layout_scan_report_detail, parent, false);

        ((TextView) detail.findViewById(R.id.tvReportTitle)).setText(r.title);
        WebView wv = detail.findViewById(R.id.reportWebView);
        // No JS, no file access, no network — the report is self-contained inline HTML.
        wv.getSettings().setJavaScriptEnabled(false);
        wv.getSettings().setAllowFileAccess(false);
        wv.getSettings().setAllowContentAccess(false);
        wv.loadDataWithBaseURL(null, r.html, "text/html", "utf-8", null);

        detail.findViewById(R.id.btnReportBack).setOnClickListener(v -> {
            int idx = parent.indexOfChild(detail);
            parent.removeView(detail);
            parent.addView(root, idx);
            showList();
        });
        detail.findViewById(R.id.btnReportShare).setOnClickListener(v -> shareReport(r));

        int idx = parent.indexOfChild(root);
        parent.removeView(root);
        parent.addView(detail, idx);
    }

    private void shareReport(ScanReport r) {
        if (activity == null) return;
        try {
            File out = new File(activity.getExternalFilesDir(null),
                    "scan-report-" + r.id + ".html");
            try (FileWriter w = new FileWriter(out)) { w.write(r.html); }
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", out);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/html");
            send.putExtra(Intent.EXTRA_SUBJECT, r.title);
            send.putExtra(Intent.EXTRA_TEXT,
                    "Attached: " + r.title + " — open the HTML in any browser.");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(send, "Share report"));
        } catch (Exception e) {
            Toast.makeText(activity, "Share failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(ScanReport r) {
        if (activity == null) return;
        new AlertDialog.Builder(activity)
                .setTitle("Delete report?")
                .setMessage(r.title)
                .setPositiveButton("Delete", (d, w) -> {
                    Context app = activity.getApplicationContext();
                    new Thread(() -> {
                        try { AppDatabase.get(app).scanReports().delete(r.id); }
                        catch (Exception ignored) {}
                        ui.post(this::showList);
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearAll() {
        if (activity == null) return;
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            try { AppDatabase.get(app).scanReports().clear(); } catch (Exception ignored) {}
            ui.post(this::showList);
        }).start();
    }

    private static String prettyKind(String k) {
        if (k == null) return "REPORT";
        switch (k) {
            case "DTC_FULL":      return "FULL SCAN";
            case "DTC_STORED":    return "STORED DTCs";
            case "DTC_PENDING":   return "PENDING DTCs";
            case "DTC_PERMANENT": return "PERMANENT DTCs";
            case "MODULE":        return "MODULE SCAN";
            case "AI_ESTIMATE":   return "AI ESTIMATE";
            default:              return k;
        }
    }

    private static int kindColor(String k) {
        if (k == null) return Color.parseColor("#03DAC5");
        switch (k) {
            case "DTC_FULL":      return Color.parseColor("#FFB300");
            case "DTC_STORED":    return Color.parseColor("#FF8A65");
            case "DTC_PENDING":   return Color.parseColor("#FFD600");
            case "DTC_PERMANENT": return Color.parseColor("#F44336");
            case "MODULE":        return Color.parseColor("#9CCC65");
            case "AI_ESTIMATE":   return Color.parseColor("#03DAC5");
            default:              return Color.parseColor("#03DAC5");
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
