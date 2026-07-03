package com.example.obd;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * "Car &amp; Sensors" screen: rotatable orthographic views (Side / Front / Top / Engine)
 * with tappable sensor pins. Pin data comes from assets/sensor_pins.json so adding
 * sensors needs no Java change.
 *
 * Rotation is by swipe (GestureDetector horizontal fling) OR by the 4 view-switch buttons.
 * Pin positions are stored as percentages of the image — they re-anchor correctly after
 * the ImageView lays out.
 */
public class SensorsController {

    private static final String[] VIEW_ORDER = { "side", "front", "top", "engine" };
    private static final int[] DRAWABLES = {
            R.drawable.car_view_side,
            R.drawable.car_view_front,
            R.drawable.car_view_top,
            R.drawable.car_view_engine
    };

    private View root;
    private FrameLayout stage;
    private ImageView image;
    private Button bSide, bFront, bTop, bEngine;
    private JSONObject viewsRoot;
    private int currentIndex = 0;

    public void attach(View view) {
        this.root = view;
        Context ctx = view.getContext();

        stage = view.findViewById(R.id.sensorStage);
        image = view.findViewById(R.id.carImage);
        bSide   = view.findViewById(R.id.btnViewSide);
        bFront  = view.findViewById(R.id.btnViewFront);
        bTop    = view.findViewById(R.id.btnViewTop);
        bEngine = view.findViewById(R.id.btnViewEngine);

        viewsRoot = loadJson(ctx);

        bSide.setOnClickListener(v   -> showView(0));
        bFront.setOnClickListener(v  -> showView(1));
        bTop.setOnClickListener(v    -> showView(2));
        bEngine.setOnClickListener(v -> showView(3));

        GestureDetector gd = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (Math.abs(vX) < Math.abs(vY)) return false;
                if (vX < -800) { showView((currentIndex + 1) % VIEW_ORDER.length); return true; }
                if (vX >  800) { showView((currentIndex - 1 + VIEW_ORDER.length) % VIEW_ORDER.length); return true; }
                return false;
            }
        });
        // Must return true: SimpleOnGestureListener.onDown() returns false and the
        // stage FrameLayout is not clickable, so forwarding the detector's return
        // value would drop ACTION_DOWN — MOVE/UP never arrive and onFling can't fire.
        stage.setOnTouchListener((v, ev) -> {
            gd.onTouchEvent(ev);
            return true;
        });

        showView(0);
    }

    public void detach() {
        root = null;
        stage = null;
        image = null;
        viewsRoot = null;
    }

    private void showView(int index) {
        if (image == null || stage == null) return;
        currentIndex = index;
        image.setImageResource(DRAWABLES[index]);
        highlightButton(index);
        // After the ImageView re-measures itself, lay out the pins.
        stage.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (stage == null) return;
                stage.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                layoutPins(VIEW_ORDER[currentIndex]);
            }
        });
    }

    private void highlightButton(int index) {
        Button[] all = { bSide, bFront, bTop, bEngine };
        for (int i = 0; i < all.length; i++) {
            if (all[i] == null) continue;
            all[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    Color.parseColor(i == index ? "#FFB300" : "#03DAC5")));
            all[i].setTextColor(Color.parseColor("#000000"));
        }
    }

    /** Re-build hotspot pins on top of the stage every time the view changes. */
    private void layoutPins(String viewKey) {
        if (stage == null || viewsRoot == null) return;
        // Remove old pins (preserve the ImageView at index 0)
        for (int i = stage.getChildCount() - 1; i > 0; i--) stage.removeViewAt(i);

        JSONObject viewObj = viewsRoot.optJSONObject("views");
        if (viewObj == null) return;
        JSONObject v = viewObj.optJSONObject(viewKey);
        if (v == null) return;
        JSONArray pins = v.optJSONArray("pins");
        if (pins == null) return;

        int W = stage.getWidth(), H = stage.getHeight();
        int dot = (int) (28 * stage.getResources().getDisplayMetrics().density);

        for (int i = 0; i < pins.length(); i++) {
            JSONObject p = pins.optJSONObject(i);
            if (p == null) continue;
            float x = (float) p.optDouble("x", 0.5);
            float y = (float) p.optDouble("y", 0.5);

            TextView dotView = new TextView(stage.getContext());
            dotView.setText(String.valueOf(i + 1));
            dotView.setTextColor(Color.BLACK);
            dotView.setGravity(android.view.Gravity.CENTER);
            dotView.setTextSize(12);
            dotView.setTypeface(null, android.graphics.Typeface.BOLD);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.parseColor("#FFB300"));
            bg.setStroke(2, Color.parseColor("#000000"));
            dotView.setBackground(bg);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dot, dot);
            lp.leftMargin = Math.max(0, (int) (W * x) - dot / 2);
            lp.topMargin  = Math.max(0, (int) (H * y) - dot / 2);
            dotView.setLayoutParams(lp);

            final int idx = i;
            dotView.setOnClickListener(view -> showPinDialog(viewKey, idx));
            stage.addView(dotView);
        }
    }

    private void showPinDialog(String viewKey, int pinIndex) {
        JSONObject viewObj = viewsRoot.optJSONObject("views");
        if (viewObj == null) return;
        JSONObject v = viewObj.optJSONObject(viewKey);
        if (v == null) return;
        JSONArray pins = v.optJSONArray("pins");
        if (pins == null || pinIndex >= pins.length()) return;
        JSONObject p = pins.optJSONObject(pinIndex);
        if (p == null) return;

        String name = p.optString("name", "Sensor");
        String function = p.optString("function", "");
        String symptoms = p.optString("symptoms", "");
        JSONArray codes = p.optJSONArray("codes");

        StringBuilder body = new StringBuilder();
        if (!function.isEmpty()) body.append("WHAT IT DOES\n").append(function).append("\n\n");
        if (!symptoms.isEmpty()) body.append("WHEN IT FAILS\n").append(symptoms).append("\n\n");
        if (codes != null && codes.length() > 0) {
            body.append("RELATED CODES\n");
            for (int i = 0; i < codes.length(); i++) {
                String c = codes.optString(i);
                DtcDictionary.Entry e = DtcDictionary.get().lookup(c);
                body.append("  • ").append(c);
                if (e != null) body.append("  ").append(e.title);
                body.append('\n');
            }
        }

        new AlertDialog.Builder(stage.getContext())
                .setTitle(name)
                .setMessage(body.toString())
                .setPositiveButton("Close", null)
                .show();
    }

    private static JSONObject loadJson(Context ctx) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("sensor_pins.json"), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
