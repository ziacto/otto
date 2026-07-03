package com.example.obd;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FaultCodesController {

    private final ObdManagerFast obdManager;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private View root;

    // Mixed widget types now — the grid entries are clickable LinearLayouts, the
    // rest are Buttons. Held as View[] since setButtonsEnabled only toggles setEnabled().
    private View[] allButtons;

    public FaultCodesController(ObdManagerFast obdManager) {
        this.obdManager = obdManager;
    }

    public void attach(View view) {
        this.root = view;
        Context ctx = view.getContext();
        DtcDictionary.get().loadIfNeeded(ctx);

        TextView result = view.findViewById(R.id.tvDtcResult);
        result.setMovementMethod(new ScrollingMovementMethod());
        result.setText("Tap 'Run Full Scan' for a one-tap go/no-go verdict.\n\n"
                + "Or read individual modes below if you know what you're looking for.");

        View bFull      = view.findViewById(R.id.btnFullScan);
        View bStored    = view.findViewById(R.id.btnReadStored);
        View bPending   = view.findViewById(R.id.btnReadPending);
        View bPermanent = view.findViewById(R.id.btnReadPermanent);
        View bFreeze    = view.findViewById(R.id.btnFreezeFrame);
        View bReady     = view.findViewById(R.id.btnReadiness);
        View bEcu       = view.findViewById(R.id.btnEcuInfo);
        View bClear     = view.findViewById(R.id.btnClearDtcs);
        View bDsc       = view.findViewById(R.id.btnScanDsc);
        View bEgs       = view.findViewById(R.id.btnScanEgs);
        View bShare     = view.findViewById(R.id.btnShareReport);
        allButtons = new View[]{ bFull, bStored, bPending, bPermanent, bFreeze, bReady, bEcu, bClear, bDsc, bEgs, bShare };

        bFull.setOnClickListener(v      -> runFullScan(result));
        bStored.setOnClickListener(v    -> runDtcRead(3,    "STORED (confirmed)",         result));
        bPending.setOnClickListener(v   -> runDtcRead(7,    "PENDING (not yet confirmed)", result));
        bPermanent.setOnClickListener(v -> runDtcRead(0x0A, "PERMANENT (survives Mode 04)", result));
        bFreeze.setOnClickListener(v    -> runFreezeFrame(result));
        bReady.setOnClickListener(v     -> runReadiness(result));
        bEcu.setOnClickListener(v       -> runEcuInfo(result));
        bClear.setOnClickListener(v     -> confirmClear(ctx, result));
        bDsc.setOnClickListener(v       -> runModuleScan(BmwModule.DSC, result));
        bEgs.setOnClickListener(v       -> runModuleScan(BmwModule.EGS, result));
        bShare.setOnClickListener(v     -> shareReport(ctx, result));

        // Make the result text tappable — if any P/B/C/U code is in it, the user can
        // tap once to open the DTC help bottom sheet with parts / video / AI deeplinks.
        result.setOnClickListener(v -> openHelpForFirstCodeIn(ctx, result.getText()));
    }

    /** Scan the visible text, find the first OBD-II code (P/B/C/U + 4 hex), open help. */
    private void openHelpForFirstCodeIn(Context ctx, CharSequence text) {
        if (text == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[PBCU][0-9A-F]{4}")
                .matcher(text);
        if (m.find()) {
            DtcHelpController.show(ctx, m.group(), null);
        } else {
            android.widget.Toast.makeText(ctx,
                    "Tap appears empty — run a scan first to populate codes.",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void runModuleScan(BmwModule module, TextView result) {
        if (!ensureConnected(result)) return;
        setButtonsEnabled(false);
        result.setTextColor(Color.parseColor("#E0E0E0"));
        result.setText("Scanning " + module.label + "...\n(experimental — header switch on D-CAN)");

        final Context appCtx = result.getContext().getApplicationContext();
        new Thread(() -> {
            try {
                List<String> codes = obdManager.readModuleDtcs(module);
                EventLogger.get().recordDtcs(module.name(), null, codes, null);
                StringBuilder sb = new StringBuilder();
                sb.append(module.label).append(" — ").append(codes.size()).append(" code(s)\n\n");
                if (codes.isEmpty()) {
                    sb.append("No codes returned. Either the module is clean or didn't respond.\n\n")
                      .append("If didn't respond: check obd-diag.log for the raw UDS exchange.\n")
                      .append("If you've never paired a module read before, the first attempt sometimes ")
                      .append("times out — try once more.");
                } else {
                    sb.append("(BMW manufacturer codes — 4-hex form. Look up in BMHat / NewTIS.)\n\n");
                    for (String c : codes) sb.append("  • ").append(c).append('\n');
                }
                final String text = sb.toString();
                // Persist from the worker, not the guarded ui.post — a scan
                // that finishes after the user navigated away must still land
                // in Scan Reports.
                ScanReportRepo.saveDtcScan(appCtx, "MODULE",
                        module.label + " scan — " + codes.size() + " code(s)", null, text);
                ui.post(() -> {
                    if (root == null) return;
                    result.setText(text);
                    setButtonsEnabled(true);
                });
            } catch (Exception e) {
                postError(result, module.label + " scan failed: " + e.getMessage());
            }
        }, "ModuleScanThread").start();
    }

    private void shareReport(Context ctx, TextView result) {
        String body = ShareReport.buildHtml(ctx, result.getText().toString());
        try {
            java.io.File out = new java.io.File(ctx.getExternalFilesDir(null), "obd-report.html");
            try (java.io.FileWriter w = new java.io.FileWriter(out)) { w.write(body); }
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("text/html");
            send.putExtra(android.content.Intent.EXTRA_SUBJECT, "BMW OBD report — share with mechanic");
            send.putExtra(android.content.Intent.EXTRA_TEXT,
                    "Attached: full OBD diagnostic scan from my BMW. Open the HTML file in any browser.");
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".fileprovider", out);
            send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(android.content.Intent.createChooser(send, "Share OBD report"));
        } catch (Exception e) {
            android.widget.Toast.makeText(ctx, "Share failed: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // ----- Full Scan: sequenced 03 -> 07 -> 0A -> 02 -> 01-Ready -> 09 -> unified report -----

    private void runFullScan(TextView result) {
        if (!ensureConnected(result)) return;
        setButtonsEnabled(false);
        result.setTextColor(Color.parseColor("#E0E0E0"));
        result.setText("Running full scan...\n(takes 10-30 s on slow adapters)");

        final Context appCtx = result.getContext().getApplicationContext();
        new Thread(() -> {
            StringBuilder rpt = new StringBuilder();
            String verdict = "GREEN";
            List<String> stored = java.util.Collections.emptyList();
            List<String> pending = java.util.Collections.emptyList();
            List<String> permanent = java.util.Collections.emptyList();
            ObdUtil.Readiness readiness = null;
            java.util.LinkedHashMap<String, String> freeze = null;
            String vin = null;

            try { stored = obdManager.readDtcs(3); } catch (Exception e) { rpt.append("  (stored read failed: ").append(e.getMessage()).append(")\n"); }
            try { pending = obdManager.readDtcs(7); } catch (Exception ignored) {}
            try { permanent = obdManager.readDtcs(0x0A); } catch (Exception ignored) {}
            try { readiness = obdManager.readReadiness(); } catch (Exception ignored) {}

            // Module scan across ALL reachable D-CAN modules. Skip DME (covered by Mode 03 above).
            java.util.LinkedHashMap<BmwModule, java.util.List<String>> moduleCodes = new java.util.LinkedHashMap<>();
            for (BmwModule m : BmwModule.dCanModules()) {
                if (m == BmwModule.DME) continue;
                try {
                    java.util.List<String> codes = obdManager.readModuleDtcs(m);
                    moduleCodes.put(m, codes);
                    EventLogger.get().recordDtcs(m.name(), null, codes, null);
                } catch (Exception ignored) {
                    // Module didn't respond — store empty list so we can show "no response" in the report
                    moduleCodes.put(m, null);
                }
            }
            if (!stored.isEmpty()) {
                try { freeze = obdManager.readFreezeFrame(); } catch (Exception ignored) {}
            }
            try { vin = obdManager.readVin(); } catch (Exception ignored) {}

            // Persist any DTC events to obd-events.log for the intermittent-fault diary
            EventLogger.get().recordDtcs("STORED",    vin, stored,    freeze);
            EventLogger.get().recordDtcs("PENDING",   vin, pending,   null);
            EventLogger.get().recordDtcs("PERMANENT", vin, permanent, null);

            // Severity rollup
            boolean anyHigh = anyAtLeast(stored, "high") || anyAtLeast(permanent, "high");
            boolean anyCritical = anyAtLeast(stored, "critical") || anyAtLeast(permanent, "critical");
            if (!stored.isEmpty() || !permanent.isEmpty()) verdict = "AMBER";
            if (anyHigh) verdict = "AMBER";
            if (anyCritical) verdict = "RED";
            if (readiness != null && readiness.milOn) verdict = "RED";

            // Surface the DTC count to the global status bar chip
            final int countForBadge = stored.size() + permanent.size();
            Context activityCtx = result.getContext();
            if (activityCtx instanceof MainActivity) {
                ((MainActivity) activityCtx).runOnUiThread(() ->
                        ((MainActivity) activityCtx).setDtcCount(countForBadge));
            }

            // Render the module section FIRST (into its own builder) because
            // module faults escalate the verdict — the old code printed
            // "VERDICT: GREEN" into the report before the loop below could
            // flip it to AMBER, so body and title/color disagreed.
            StringBuilder moduleSection = new StringBuilder();
            int moduleResponded = 0, moduleFaults = 0;
            moduleSection.append("\nMODULE SCAN (D-CAN):\n");
            for (java.util.Map.Entry<BmwModule, java.util.List<String>> e : moduleCodes.entrySet()) {
                BmwModule m = e.getKey();
                java.util.List<String> codes = e.getValue();
                if (codes == null) {
                    moduleSection.append("  • ").append(m.label).append(" — no response (module may not be present)\n");
                } else if (codes.isEmpty()) {
                    moduleSection.append("  • ").append(m.label).append(" — no codes\n");
                    moduleResponded++;
                } else {
                    moduleSection.append("  • ").append(m.label).append(" — ").append(codes.size()).append(" code(s):\n");
                    for (String c : codes) moduleSection.append("        ").append(c).append('\n');
                    moduleResponded++;
                    moduleFaults += codes.size();
                    if (!"RED".equals(verdict)) verdict = "AMBER";
                }
            }
            moduleSection.append("\nModules that responded: ").append(moduleResponded)
                    .append(" / ").append(moduleCodes.size())
                    .append("   |   Module faults: ").append(moduleFaults).append('\n');

            // Render
            rpt.append("FULL SCAN — ").append(new java.util.Date()).append("\n\n");
            rpt.append("VERDICT: ").append(verdict).append("\n");
            if (vin != null) rpt.append("VIN: ").append(vin).append('\n');
            rpt.append('\n');

            appendCodeList(rpt, "DME — STORED",    stored);
            appendCodeList(rpt, "DME — PENDING",   pending);
            appendCodeList(rpt, "DME — PERMANENT", permanent);
            rpt.append(moduleSection);

            if (freeze != null) {
                rpt.append("\nFREEZE FRAME (at moment of stored DTC):\n");
                for (java.util.Map.Entry<String, String> e : freeze.entrySet()) {
                    rpt.append(String.format("  %-18s %s%n", e.getKey() + ":", e.getValue()));
                }
            }

            if (readiness != null) {
                rpt.append("\nREADINESS:\n");
                rpt.append("  MIL:           ").append(readiness.milOn ? "ON" : "off").append('\n');
                rpt.append("  Stored count:  ").append(readiness.dtcCount).append('\n');
                int notReady = 0;
                for (String v : readiness.monitors.values()) if ("not ready".equals(v)) notReady++;
                rpt.append("  Not-ready:     ").append(notReady).append(" / ").append(readiness.monitors.size()).append('\n');
            }

            if (verdict.equals("GREEN")) {
                rpt.append("\nNo issues reported by any D-CAN module reached.\n");
                rpt.append("(For comfort/audio/seat/cluster modules you'd need a K+DCAN cable — those are on K-CAN and unreachable via standard ELM327.)");
            }

            final String reportText = rpt.toString();
            final String fVerdict = verdict;
            final String fVin = vin;
            // Persist from the worker — a 10-30s scan that finishes after the
            // user navigated away used to be silently dropped because the save
            // lived inside the root==null guard.
            ScanReportRepo.saveDtcScan(appCtx, "DTC_FULL",
                    "Full Scan — " + fVerdict, fVin, reportText);
            ui.post(() -> {
                if (root == null) return;
                result.setText(reportText);
                result.setTextColor(Color.parseColor(verdictColor(fVerdict)));
                setButtonsEnabled(true);
            });
        }, "FullScanThread").start();
    }

    private static String verdictColor(String v) {
        switch (v) {
            case "RED":   return "#F44336";
            case "AMBER": return "#FFB300";
            default:      return "#4CAF50";
        }
    }

    private void appendCodeList(StringBuilder rpt, String header, List<String> codes) {
        rpt.append(header).append(" — ").append(codes.size()).append(" code(s)\n");
        if (codes.isEmpty()) { rpt.append("  (none)\n"); return; }
        for (String c : codes) {
            DtcDictionary.Entry e = DtcDictionary.get().lookup(c);
            if (e == null) {
                String desc = DtcUtil.lookupDescription(c);
                rpt.append("  • ").append(c);
                if (desc != null) rpt.append(" — ").append(desc);
                rpt.append('\n');
            } else {
                rpt.append("  • ").append(c).append(" [").append(e.severity.toUpperCase()).append("]  ")
                   .append(e.title).append('\n');
                if (!e.cause.isEmpty()) rpt.append("        ").append(e.cause).append('\n');
                rpt.append("        DIY: ").append("★★★★★".substring(0, Math.max(0, Math.min(5, e.diy)))).append('\n');
            }
        }
    }

    private boolean anyAtLeast(List<String> codes, String minSeverity) {
        int min = sevRank(minSeverity);
        for (String c : codes) {
            DtcDictionary.Entry e = DtcDictionary.get().lookup(c);
            if (e == null) continue;
            if (sevRank(e.severity) >= min) return true;
        }
        return false;
    }

    private int sevRank(String s) {
        if (s == null) return 0;
        switch (s) {
            case "low": return 1;
            case "medium": return 2;
            case "high": return 3;
            case "critical": return 4;
            default: return 0;
        }
    }

    public void detach() {
        root = null;
        // Drop the button refs too — the singleton controller otherwise pins
        // the previous screen's whole view tree until the next attach.
        allButtons = null;
    }

    // ----- Mode 03 / 07 / 0A -----

    private void runDtcRead(int mode, String header, TextView result) {
        if (!ensureConnected(result)) return;
        setButtonsEnabled(false);
        result.setTextColor(Color.parseColor("#E0E0E0"));
        result.setText("Reading " + header + "...");

        final Context appCtx = result.getContext().getApplicationContext();
        new Thread(() -> {
            try {
                List<String> codes = obdManager.readDtcs(mode);
                ui.post(() -> {
                    if (root == null) return;
                    StringBuilder sb = new StringBuilder();
                    sb.append(header).append(" — ").append(codes.size()).append(" code(s)\n\n");
                    if (codes.isEmpty()) {
                        sb.append("No codes reported by the DME.\n");
                        if (mode == 0x0A) {
                            sb.append("(Permanent codes only clear after the ECU itself verifies the fault is fixed.)");
                        } else {
                            sb.append("(MIL on but no codes? Likely a non-engine module — needs Carly/ISTA/INPA.)");
                        }
                    } else {
                        for (String c : codes) {
                            DtcDictionary.Entry e = DtcDictionary.get().lookup(c);
                            if (e != null) {
                                sb.append("• ").append(c).append(" [").append(e.severity.toUpperCase()).append("]  ")
                                  .append(e.title).append('\n');
                                if (!e.cause.isEmpty()) sb.append("    ").append(e.cause).append('\n');
                            } else {
                                sb.append("• ").append(c);
                                String desc = DtcUtil.lookupDescription(c);
                                if (desc != null) sb.append("  —  ").append(desc);
                                sb.append('\n');
                            }
                        }
                    }
                    result.setText(sb.toString());
                    setButtonsEnabled(true);
                    String kind = (mode == 3) ? "DTC_STORED"
                            : (mode == 7) ? "DTC_PENDING" : "DTC_PERMANENT";
                    // Save with app context — outlives the screen.
                    ScanReportRepo.saveDtcScan(appCtx, kind,
                            header + " — " + codes.size() + " code(s)", null, sb.toString());
                });
            } catch (Exception e) {
                // Catch everything, not just IOException — a RuntimeException
                // from parsing garbage adapter output used to kill this worker
                // after setButtonsEnabled(false), leaving all 11 buttons dead.
                postError(result, "Read failed: " + e.getMessage());
            }
        }, "DtcReadThread").start();
    }

    // ----- Mode 02 freeze frame -----

    private void runFreezeFrame(TextView result) {
        if (!ensureConnected(result)) return;
        setButtonsEnabled(false);
        result.setTextColor(Color.parseColor("#E0E0E0"));
        result.setText("Reading freeze frame...");

        new Thread(() -> {
            try {
                LinkedHashMap<String, String> snap = obdManager.readFreezeFrame();
                ui.post(() -> {
                    if (root == null) return;
                    StringBuilder sb = new StringBuilder();
                    sb.append("FREEZE FRAME — snapshot at time of DTC\n\n");
                    boolean any = false;
                    for (Map.Entry<String, String> e : snap.entrySet()) {
                        sb.append(String.format("  %-18s %s%n", e.getKey() + ":", e.getValue()));
                        if (!"—".equals(e.getValue())) any = true;
                    }
                    if (!any) {
                        sb.append("\nNo freeze frame stored. Either no DTC has triggered since last clear, ")
                          .append("or the DME doesn't support freeze frame PIDs.");
                    }
                    result.setText(sb.toString());
                    setButtonsEnabled(true);
                });
            } catch (Exception e) {
                postError(result, "Freeze frame read failed: " + e.getMessage());
            }
        }, "FreezeFrameThread").start();
    }

    // ----- Mode 01 PID 01 readiness -----

    private void runReadiness(TextView result) {
        if (!ensureConnected(result)) return;
        setButtonsEnabled(false);
        result.setTextColor(Color.parseColor("#E0E0E0"));
        result.setText("Reading readiness monitors...");

        new Thread(() -> {
            try {
                ObdUtil.Readiness r = obdManager.readReadiness();
                ui.post(() -> {
                    if (root == null) return;
                    if (r == null) {
                        result.setText("Readiness read failed: no parseable response.");
                        setButtonsEnabled(true);
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("READINESS MONITORS (Mode 01 PID 01)\n\n");
                    sb.append("MIL (check engine):  ").append(r.milOn ? "ON" : "off").append('\n');
                    sb.append("Stored DTC count:    ").append(r.dtcCount).append("\n\n");
                    for (Map.Entry<String, String> e : r.monitors.entrySet()) {
                        sb.append(String.format("  %-18s %s%n", e.getKey() + ":", e.getValue()));
                    }
                    sb.append("\n'not ready' = monitor hasn't completed its drive-cycle test yet.");
                    result.setText(sb.toString());
                    setButtonsEnabled(true);
                });
            } catch (Exception e) {
                postError(result, "Readiness read failed: " + e.getMessage());
            }
        }, "ReadinessThread").start();
    }

    // ----- VIN + Mode 09 CALID + ECU name -----

    private void runEcuInfo(TextView result) {
        if (!ensureConnected(result)) return;
        setButtonsEnabled(false);
        result.setTextColor(Color.parseColor("#E0E0E0"));
        result.setText("Reading ECU info...");

        new Thread(() -> {
            String vin = null, calid = null, name = null;
            String err = null;
            try { vin = obdManager.readVin(); } catch (Exception e) { err = "VIN: " + e.getMessage(); }
            try { calid = obdManager.readMode09Ascii(0x04); } catch (Exception e) { /* tolerated */ }
            try { name  = obdManager.readMode09Ascii(0x0A); } catch (Exception e) { /* tolerated */ }

            final String fVin = vin, fCal = calid, fName = name, fErr = err;
            ui.post(() -> {
                if (root == null) return;
                StringBuilder sb = new StringBuilder();
                sb.append("ECU INFO (Mode 09)\n\n");
                sb.append(String.format("  %-12s %s%n", "VIN:",   fVin  == null ? "—" : fVin));
                sb.append(String.format("  %-12s %s%n", "CALID:", fCal  == null ? "—" : fCal));
                sb.append(String.format("  %-12s %s%n", "ECU:",   fName == null ? "—" : fName));
                if (fErr != null) sb.append("\nNote: ").append(fErr);
                sb.append("\nE65 DME often returns CALID but not ECU name. — means PID not supported.");
                result.setText(sb.toString());
                setButtonsEnabled(true);
            });
        }, "EcuInfoThread").start();
    }

    // ----- Mode 04 clear -----

    private void confirmClear(Context ctx, TextView result) {
        if (!ensureConnected(result)) return;
        new AlertDialog.Builder(ctx)
                .setTitle("Clear stored fault codes?")
                .setMessage("Sends OBD-II Mode 04 to erase stored DTCs and freeze-frame data.\n\n"
                        + "• Permanent DTCs (Mode 0A) will NOT clear — only the ECU's own self-test can clear those.\n"
                        + "• Underlying faults will reappear if not fixed.\n\n"
                        + "Continue?")
                .setPositiveButton("Clear", (d, w) -> doClear(result))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doClear(TextView result) {
        setButtonsEnabled(false);
        result.setText("Clearing codes...");
        new Thread(() -> {
            try {
                boolean ok = obdManager.clearDtcs();
                ui.post(() -> {
                    if (root == null) return;
                    if (ok) {
                        result.setText("Cleared. Re-read in a minute to confirm.");
                        result.setTextColor(Color.parseColor("#03DAC5"));
                    } else {
                        result.setText("Clear request sent but no ACK from ECU.\n"
                                + "Try again with engine OFF + ignition ON.");
                        result.setTextColor(Color.parseColor("#FFD600"));
                    }
                    setButtonsEnabled(true);
                });
            } catch (Exception e) {
                postError(result, "Clear failed: " + e.getMessage());
            }
        }, "DtcClearThread").start();
    }

    // ----- helpers -----

    private boolean ensureConnected(TextView result) {
        if (obdManager.isConnected()) return true;
        result.setText("Not connected. Tap 'Connect' first.");
        Toast.makeText(result.getContext(), "Not connected", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void postError(TextView result, String msg) {
        ui.post(() -> {
            if (root == null) return;
            result.setText(msg);
            result.setTextColor(Color.parseColor("#F44336"));
            setButtonsEnabled(true);
        });
    }

    private void setButtonsEnabled(boolean enabled) {
        ui.post(() -> {
            if (allButtons == null) return;
            for (View b : allButtons) if (b != null) { b.setEnabled(enabled); b.setAlpha(enabled ? 1f : 0.5f); }
        });
    }
}
