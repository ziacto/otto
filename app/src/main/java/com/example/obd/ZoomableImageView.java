package com.example.obd;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * Image view with pinch-to-zoom, pan, and double-tap-to-toggle-fit/2x.
 *
 * Designed for diagram/PDF viewing where the whole image should fit on screen by
 * default and the user can zoom in for details. Uses Matrix scaleType so the
 * underlying transform composes scale + translation without re-scaling the bitmap.
 *
 * Behaviour:
 *  - Initial: image scaled to fit centre, no crop.
 *  - Pinch: scales between 1x (fit) and 6x.
 *  - Drag (1 finger): pans when zoomed in; clamped to image bounds.
 *  - Double-tap: toggles between fit and 2x zoom centred on tap point.
 */
public class ZoomableImageView extends AppCompatImageView {

    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 6.0f;
    private static final float DOUBLE_TAP_ZOOM = 2.0f;

    private final Matrix matrix = new Matrix();
    private float currentScale = 1.0f;
    private float baseScale = 1.0f;
    private final PointF lastPan = new PointF();
    private boolean panning = false;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    public ZoomableImageView(Context context) { super(context); init(); }
    public ZoomableImageView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ZoomableImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle); init();
    }

    private void init() {
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
        gestureDetector = new GestureDetector(getContext(), new GestureListener());
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) fitImage();
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        if (getWidth() > 0 && getHeight() > 0) fitImage();
    }

    /** Reset transform to fit the bitmap centred within the view bounds. */
    public void fitImage() {
        if (getDrawable() == null) return;
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW == 0 || viewH == 0) return;
        int imgW = getDrawable().getIntrinsicWidth();
        int imgH = getDrawable().getIntrinsicHeight();
        if (imgW <= 0 || imgH <= 0) return;

        float sx = (float) viewW / imgW;
        float sy = (float) viewH / imgH;
        baseScale = Math.min(sx, sy);
        currentScale = 1.0f;

        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        // centre
        float dx = (viewW - imgW * baseScale) / 2f;
        float dy = (viewH - imgH * baseScale) / 2f;
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        if (!scaleDetector.isInProgress()) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastPan.set(event.getX(), event.getY());
                    panning = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (panning && currentScale > 1.001f) {
                        float dx = event.getX() - lastPan.x;
                        float dy = event.getY() - lastPan.y;
                        matrix.postTranslate(dx, dy);
                        clampPan();
                        setImageMatrix(matrix);
                        lastPan.set(event.getX(), event.getY());
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    panning = false;
                    break;
            }
        }
        return true;
    }

    /** Keep image edges within view bounds when zoomed in. */
    private void clampPan() {
        if (getDrawable() == null) return;
        float[] values = new float[9];
        matrix.getValues(values);
        float tx = values[Matrix.MTRANS_X];
        float ty = values[Matrix.MTRANS_Y];
        float scaleNow = values[Matrix.MSCALE_X];

        int imgW = getDrawable().getIntrinsicWidth();
        int imgH = getDrawable().getIntrinsicHeight();
        float scaledW = imgW * scaleNow;
        float scaledH = imgH * scaleNow;
        int viewW = getWidth();
        int viewH = getHeight();

        float minX, maxX, minY, maxY;
        if (scaledW <= viewW) {
            // centre horizontally
            minX = maxX = (viewW - scaledW) / 2f;
        } else {
            minX = viewW - scaledW;
            maxX = 0;
        }
        if (scaledH <= viewH) {
            minY = maxY = (viewH - scaledH) / 2f;
        } else {
            minY = viewH - scaledH;
            maxY = 0;
        }
        float fixX = 0, fixY = 0;
        if (tx < minX) fixX = minX - tx;
        else if (tx > maxX) fixX = maxX - tx;
        if (ty < minY) fixY = minY - ty;
        else if (ty > maxY) fixY = maxY - ty;
        if (fixX != 0 || fixY != 0) matrix.postTranslate(fixX, fixY);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            float next = currentScale * factor;
            if (next < MIN_SCALE) {
                factor = MIN_SCALE / currentScale;
                next = MIN_SCALE;
            } else if (next > MAX_SCALE) {
                factor = MAX_SCALE / currentScale;
                next = MAX_SCALE;
            }
            currentScale = next;
            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            clampPan();
            setImageMatrix(matrix);
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (currentScale > 1.1f) {
                fitImage();
            } else {
                float factor = DOUBLE_TAP_ZOOM / currentScale;
                currentScale = DOUBLE_TAP_ZOOM;
                matrix.postScale(factor, factor, e.getX(), e.getY());
                clampPan();
                setImageMatrix(matrix);
            }
            return true;
        }
    }
}
