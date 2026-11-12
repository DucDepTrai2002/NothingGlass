package nothing.ducdeptrai.glass;

import android.content.res.Configuration;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.View;

/**
 * Lớp kính đặt làm foreground của ô CC.
 * Viền ô khúc xạ ảnh nền phía sau (đã làm mờ) + tách màu + viền sáng.
 * Giữ nguyên API static apply(View, float) / clear(View) để smali cũ gọi được.
 */
public final class GlassFgDrawable extends Drawable {

    private static final String SKSL =
            "uniform shader uBg;\n"
          + "uniform float2 uSize;\n"
          + "uniform float uRadius;\n"
          + "uniform float2 uOffset;\n"
          + "uniform float uHasBg;\n"
          + "uniform float uDark;\n"
          + "uniform float4 uP1;\n"   // x: độ rộng vùng khúc xạ (tỉ lệ r), y: lực khúc xạ, z: tách màu px, w: độ phủ nền
          + "uniform float4 uP2;\n"
          + "uniform float4 uP3;\n"   // x: sáng góc trên-trái, y: sáng góc dưới-phải, z: mép sắc, w: độ bão hoà
          + "half4 main(float2 p) {\n"
          + "  float2 c = uSize * 0.5;\n"
          + "  float r = max(min(uRadius, min(c.x, c.y)), 1.0);\n"
          + "  float2 q = abs(p - c) - (c - r);\n"
          + "  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
          + "  if (d > 0.0) { return half4(0.0); }\n"
          + "  float e = -d;\n"
          + "  float band = max(r * uP1.x, 6.0);\n"
          + "  float t = clamp(e / band, 0.0, 1.0);\n"
          + "  float bend = (1.0 - t) * (1.0 - t) * (1.0 - t);\n"
          + "  float2 dir = p - clamp(p, float2(r, r), uSize - float2(r, r));\n"
          + "  float L = length(dir);\n"
          + "  float2 n = L > 0.001 ? dir / L : float2(0.0, 0.0);\n"
          + "  float2 sp = uOffset + p - n * bend * band * uP1.y;\n"
          + "  float2 ca = n * bend * uP1.z;\n"
          + "  float4 bg = float4(uBg.eval(sp));\n"
          + "  float rr = float4(uBg.eval(sp - ca)).r;\n"
          + "  float bb = float4(uBg.eval(sp + ca)).b;\n"
          + "  float3 col = float3(rr, bg.g, bb);\n"
          + "  float lum = dot(col, float3(0.299, 0.587, 0.114));\n"
          + "  col = mix(float3(lum, lum, lum), col, uP2.w);\n"
          + "  col = col * mix(1.0, uP3.x, uDark);\n"
          + "  float lit = dot(n, float2(-0.6, -0.8));\n"
          + "  float rim = exp(-e / uP3.y);\n"
          + "  float spec = rim * (uP2.x * max(lit, 0.0) + uP2.y * max(-lit, 0.0)) + exp(-e / 1.0) * uP2.z;\n"
          + "  float bgLum = dot(bg.rgb, float3(0.299, 0.587, 0.114));\n"
          + "  float aBg = bend * uP1.w * uHasBg * mix(1.0, uP3.z, smoothstep(0.55, 0.95, bgLum));\n"
          + "  float3 outc = clamp(col * uHasBg + spec, 0.0, 1.0);\n"
          + "  float a = clamp(max(aBg, spec), 0.0, 1.0);\n"
          + "  return half4(half3(outc * a), half(a));\n"
          + "}\n";

    private final RuntimeShader mShader;
    private final Paint mPaint;
    private final RectF mRect = new RectF();
    private final int[] mLoc = new int[2];
    private View mHost;
    private float mRadius;

    // Thông số chỉnh được
    public static float BAND = 0.45f;        // độ rộng vùng khúc xạ so với bán kính bo góc
    public static float REFRACT = 0.8f;     // lực khúc xạ
    public static float CHROMA = 4.0f;      // tách màu (px)
    public static float BG_ALPHA = 0.55f;   // độ phủ nền khúc xạ ở mép
    public static float LIGHT_TL = 0.60f;   // viền sáng góc trên trái
    public static float LIGHT_BR = 0.30f;   // viền sáng góc dưới phải
    public static float EDGE = 0.22f;       // mép sắc
    public static float SATURATION = 1.10f; // độ bão hoà màu nền
    public static float DARK_DIM = 0.50f;   // hạ sáng nền khúc xạ ở chế độ tối
    public static float RIM_WIDTH = 2.0f;   // độ rộng viền sáng (px)
    public static float BRIGHT_BG_ALPHA = 0.25f; // giảm khúc xạ ở viền khi nền phía sau rất sáng (tránh ô bị phồng)

    public GlassFgDrawable(float radius) {
        mRadius = radius;
        mShader = new RuntimeShader(SKSL);
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setShader(mShader);
    }

    public static void apply(View v, float radius) {
        if (v == null || Build.VERSION.SDK_INT < 33) return;
        try {
            Drawable fg = v.getForeground();
            if (fg instanceof GlassFgDrawable) {
                GlassFgDrawable g = (GlassFgDrawable) fg;
                g.mRadius = radius;
                g.mHost = v;
                g.invalidateSelf();
                return;
            }
            GlassFgDrawable g = new GlassFgDrawable(radius);
            g.mHost = v;
            v.setForeground(g);
        } catch (Throwable t) {
            Log.w(Backdrop.TAG, "apply failed", t);
        }
    }

    public static void clear(View v) {
        if (v == null) return;
        try {
            if (v.getForeground() instanceof GlassFgDrawable) v.setForeground(null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        try {
            BitmapShader bg = null;
            boolean dark = false;
            if (mHost != null) {
                bg = Backdrop.forDraw(mHost);
                mHost.getLocationOnScreen(mLoc);
                dark = (mHost.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            }
            mShader.setInputShader("uBg", bg != null ? bg : Backdrop.empty());
            mShader.setFloatUniform("uSize", b.width(), b.height());
            mShader.setFloatUniform("uRadius", mRadius);
            mShader.setFloatUniform("uOffset", mLoc[0] + b.left, mLoc[1] + b.top);
            mShader.setFloatUniform("uHasBg", bg != null ? 1f : 0f);
            mShader.setFloatUniform("uDark", dark ? 1f : 0f);
            mShader.setFloatUniform("uP1", BAND, REFRACT, CHROMA, BG_ALPHA);
            mShader.setFloatUniform("uP2", LIGHT_TL, LIGHT_BR, EDGE, SATURATION);
            mShader.setFloatUniform("uP3", DARK_DIM, RIM_WIDTH, BRIGHT_BG_ALPHA, 0f);
            mPaint.setShader(mShader);
            int save = canvas.save();
            canvas.translate(b.left, b.top);
            mRect.set(0, 0, b.width(), b.height());
            canvas.drawRoundRect(mRect, mRadius, mRadius, mPaint);
            canvas.restoreToCount(save);
        } catch (Throwable t) {
            Log.w(Backdrop.TAG, "draw failed", t);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mPaint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
