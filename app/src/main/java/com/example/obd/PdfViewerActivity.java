package com.example.obd;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Displays a PDF from assets/ in a zoomable, page-flippable viewer.
 *
 * Implementation notes:
 *  - PdfRenderer needs a ParcelFileDescriptor backed by a real file. Assets are
 *    inside the APK zip, so we copy to cacheDir on first open. This is a one-time
 *    cost per launch session — subsequent opens of the same PDF reuse the cached file.
 *  - Page bitmaps are rendered at the screen's resolution (device width) so they look
 *    crisp on the ZoomableImageView. Bumping render size 2x for retina helps.
 *  - Renderer + descriptor + previous page bitmap are closed on each page change to
 *    avoid leaking native handles; cleanup is also done in onDestroy.
 */
public class PdfViewerActivity extends Activity {

    public static final String EXTRA_ASSET = "asset_path";

    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private PdfRenderer.Page currentPage;
    private Bitmap currentBitmap;
    private int pageIndex = 0;

    private ZoomableImageView imageView;
    private TextView pageLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        // Keep screen on while reading
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        imageView = findViewById(R.id.pdfPageImage);
        pageLabel = findViewById(R.id.pdfPageLabel);
        ImageButton prev = findViewById(R.id.pdfPrev);
        ImageButton next = findViewById(R.id.pdfNext);

        prev.setOnClickListener(v -> showPage(pageIndex - 1));
        next.setOnClickListener(v -> showPage(pageIndex + 1));

        String asset = getIntent().getStringExtra(EXTRA_ASSET);
        if (asset == null) {
            Toast.makeText(this, "No PDF specified", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setTitle(prettyName(asset));

        try {
            File f = copyAssetToCache(this, asset);
            descriptor = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            showPage(0);
        } catch (IOException e) {
            Toast.makeText(this, "Failed to open PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void showPage(int index) {
        if (renderer == null) return;
        if (index < 0 || index >= renderer.getPageCount()) return;

        closePageAndBitmap();
        pageIndex = index;
        currentPage = renderer.openPage(pageIndex);

        // Render to a bitmap sized to fit screen width at 2x device density for clarity
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int targetW = (int) (dm.widthPixels * 1.5f);
        int w = currentPage.getWidth();
        int h = currentPage.getHeight();
        float scale = (float) targetW / w;
        int outW = (int) (w * scale);
        int outH = (int) (h * scale);

        currentBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        currentBitmap.eraseColor(0xFFFFFFFF);
        currentPage.render(currentBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

        imageView.setImageBitmap(currentBitmap);
        pageLabel.setText(String.format("Page %d of %d", pageIndex + 1, renderer.getPageCount()));
    }

    private void closePageAndBitmap() {
        if (currentPage != null) {
            try { currentPage.close(); } catch (Exception ignored) {}
            currentPage = null;
        }
        if (currentBitmap != null) {
            try { currentBitmap.recycle(); } catch (Exception ignored) {}
            currentBitmap = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closePageAndBitmap();
        if (renderer != null) {
            try { renderer.close(); } catch (Exception ignored) {}
        }
        if (descriptor != null) {
            try { descriptor.close(); } catch (Exception ignored) {}
        }
    }

    private static File copyAssetToCache(Context ctx, String assetPath) throws IOException {
        String safeName = assetPath.replace('/', '_');
        File out = new File(ctx.getCacheDir(), safeName);
        if (out.exists() && out.length() > 0) return out;
        try (InputStream in = ctx.getAssets().open(assetPath);
             OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        return out;
    }

    private static String prettyName(String path) {
        int slash = path.lastIndexOf('/');
        String name = (slash >= 0) ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replace('_', ' ').replace('-', ' ');
    }
}
