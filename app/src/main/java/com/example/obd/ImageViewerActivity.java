package com.example.obd;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

/**
 * Full-screen zoom/pan image viewer for asset images (wiring diagrams, fuse photos).
 *
 * Loads from assets/<path> via AssetManager.open(). Uses inSampleSize to downscale very
 * large diagrams (>4096 px) so a 30 MP scanned wiring sheet doesn't OOM on phones with
 * limited heap. ZoomableImageView handles pinch/pan/double-tap.
 */
public class ImageViewerActivity extends Activity {

    public static final String EXTRA_ASSET = "asset_path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ZoomableImageView iv = findViewById(R.id.imageView);
        TextView title = findViewById(R.id.imageTitle);

        String asset = getIntent().getStringExtra(EXTRA_ASSET);
        if (asset == null) {
            Toast.makeText(this, "No image specified", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        title.setText(prettyName(asset));

        Bitmap bmp = loadAssetBitmapDownsampled(asset, 4096, 4096);
        if (bmp == null) {
            Toast.makeText(this, "Failed to open image", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        iv.setImageBitmap(bmp);
    }

    private Bitmap loadAssetBitmapDownsampled(String path, int reqW, int reqH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getAssets().open(path)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while ((bounds.outWidth / sample) > reqW || (bounds.outHeight / sample) > reqH) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in2 = getAssets().open(path)) {
                return BitmapFactory.decodeStream(in2, null, opts);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String prettyName(String path) {
        int slash = path.lastIndexOf('/');
        String name = (slash >= 0) ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replace('_', ' ').replace('-', ' ');
    }
}
