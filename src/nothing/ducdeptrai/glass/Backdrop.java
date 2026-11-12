package nothing.ducdeptrai.glass;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Ảnh nền đã làm mờ (thu nhỏ) để shader khúc xạ lấy mẫu.
 * Bản 1: dùng hình nền hệ thống (khớp khi mở CC ở màn hình chính).
 */
final class Backdrop {
    static final String TAG = "NothingGlass";
    private static final int SMALL_W = 72;

    private static BitmapShader sShader;
    private static BitmapShader sEmpty;
    private static int sWallpaperId = Integer.MIN_VALUE;
    private static int sScreenW, sScreenH;
    private static boolean sLoading;
    private static long sLastFail;
    private static final ArrayList<WeakReference<View>> sWaiting = new ArrayList<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private Backdrop() {}

    /** Trả về shader nền theo toạ độ màn hình, hoặc null nếu chưa có. */
    static BitmapShader get(View host) {
        Context ctx = host.getContext();
        int id = Integer.MIN_VALUE;
        try {
            id = WallpaperManager.getInstance(ctx).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        } catch (Throwable ignored) {
        }
        if (sShader != null && id == sWallpaperId) return sShader;
        if (sShader == null && System.currentTimeMillis() - sLastFail < 30000) return null;
        if (!sLoading) load(ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx, id);
        synchronized (sWaiting) {
            sWaiting.add(new WeakReference<>(host));
        }
        return sShader; // có thể là bản cũ trong lúc tải bản mới
    }

    static BitmapShader empty() {
        if (sEmpty == null) {
            Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            sEmpty = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        return sEmpty;
    }

    private static void load(final Context ctx, final int id) {
        sLoading = true;
        new Thread(() -> {
            BitmapShader result = null;
            try {
                Rect bounds = ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE))
                        .getMaximumWindowMetrics().getBounds();
                int sw = Math.max(1, bounds.width());
                int sh = Math.max(1, bounds.height());
                Drawable d = WallpaperManager.getInstance(ctx).getDrawable();
                if (d != null) {
                    int bw = SMALL_W;
                    int bh = Math.max(1, Math.round(SMALL_W * (float) sh / sw));
                    Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(small);
                    int dw = Math.max(1, d.getIntrinsicWidth());
                    int dh = Math.max(1, d.getIntrinsicHeight());
                    // center-crop hình nền theo tỉ lệ màn hình
                    float scale = Math.max(bw / (float) dw, bh / (float) dh);
                    int w = Math.round(dw * scale), h = Math.round(dh * scale);
                    int l = (bw - w) / 2, t = (bh - h) / 2;
                    d.setBounds(l, t, l + w, t + h);
                    d.draw(c);
                    small = blurOnce(small);
                    BitmapShader s = new BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                    Matrix m = new Matrix();
                    m.setScale(sw / (float) bw, sh / (float) bh);
                    s.setLocalMatrix(m);
                    result = s;
                    sScreenW = sw;
                    sScreenH = sh;
                    Log.i(TAG, "wallpaper backdrop ready " + bw + "x" + bh + " for " + sw + "x" + sh);
                } else {
                    Log.w(TAG, "wallpaper drawable null");
                }
            } catch (Throwable t) {
                Log.w(TAG, "load wallpaper failed", t);
            }
            final BitmapShader fr = result;
            sMain.post(() -> {
                if (fr != null) {
                    sShader = fr;
                    sWallpaperId = id;
                } else {
                    sLastFail = System.currentTimeMillis();
                    sWallpaperId = id;
                }
                sLoading = false;
                ArrayList<WeakReference<View>> list;
                synchronized (sWaiting) {
                    list = new ArrayList<>(sWaiting);
                    sWaiting.clear();
                }
                for (WeakReference<View> r : list) {
                    View v = r.get();
                    if (v != null) v.invalidate();
                }
            });
        }, "NothingGlass-bg").start();
    }

    /** Làm mờ thêm bằng box blur 3 lượt trên bitmap nhỏ. */
    private static Bitmap blurOnce(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, tmp, w, h, 2);
            boxV(tmp, px, w, h, 2);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = Math.min(w - 1, Math.max(0, x + k));
                    int c = in[y * w + xx];
                    a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                out[y * w + x] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = Math.min(h - 1, Math.max(0, y + k));
                    int c = in[yy * w + x];
                    a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                out[y * w + x] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
    }
}
