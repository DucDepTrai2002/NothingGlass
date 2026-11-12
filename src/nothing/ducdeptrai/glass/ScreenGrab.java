package nothing.ducdeptrai.glass;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/**
 * Chụp nội dung màn hình NẰM DƯỚI cửa sổ hiện tại (loại trừ chính cửa sổ CC/volume)
 * qua IWindowManager.captureDisplay (API ẩn, SystemUI có quyền READ_FRAME_BUFFER).
 * Mọi thứ qua reflection, lỗi thì trả null để quay về dùng hình nền.
 */
final class ScreenGrab {
    interface Callback { void onBitmap(Bitmap softwareBitmap); }

    static volatile boolean sBroken;

    private ScreenGrab() {}

    static boolean request(View host, float scale, final Callback cb) {
        if (sBroken) return false;
        try {
            Object wm = Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService").invoke(null);

            Class<?> builderCls = Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
            Object builder = builderCls.getConstructor().newInstance();
            builderCls.getMethod("setFrameScale", float.class).invoke(builder, scale);
            SurfaceControl sc = windowSurface(host);
            if (sc != null) {
                builderCls.getMethod("setExcludeLayers", SurfaceControl[].class)
                        .invoke(builder, (Object) new SurfaceControl[]{sc});
            } else {
                Log.w(Backdrop.TAG, "no window SurfaceControl, capture would include CC itself");
                return false;
            }
            Object args = builderCls.getMethod("build").invoke(builder);

            Class<?> listenerCls = Class.forName("android.window.ScreenCapture$ScreenCaptureListener");
            Object listener = null;
            for (Constructor<?> c : listenerCls.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length != 1) continue;
                if (p[0] == ObjIntConsumer.class) {
                    ObjIntConsumer<Object> f = (buf, status) -> deliver(buf, cb);
                    listener = c.newInstance(f);
                    break;
                } else if (p[0] == Consumer.class) {
                    Consumer<Object> f = buf -> deliver(buf, cb);
                    listener = c.newInstance(f);
                    break;
                }
            }
            if (listener == null) throw new IllegalStateException("no ScreenCaptureListener ctor");

            int displayId = Display.DEFAULT_DISPLAY;
            Display d = host.getDisplay();
            if (d != null) displayId = d.getDisplayId();

            Method capture = null;
            for (Method m : wm.getClass().getMethods()) {
                if (m.getName().equals("captureDisplay") && m.getParameterTypes().length == 3) {
                    capture = m;
                    break;
                }
            }
            if (capture == null) throw new IllegalStateException("no captureDisplay");
            capture.invoke(wm, displayId, args, listener);
            return true;
        } catch (Throwable t) {
            sBroken = true;
            Log.w(Backdrop.TAG, "screen capture unavailable, fallback to wallpaper", t);
            return false;
        }
    }

    private static void deliver(Object buf, Callback cb) {
        Bitmap out = null;
        try {
            if (buf != null) {
                Bitmap hw = (Bitmap) buf.getClass().getMethod("asBitmap").invoke(buf);
                if (hw != null) {
                    out = hw.copy(Bitmap.Config.ARGB_8888, false);
                    hw.recycle();
                }
            }
        } catch (Throwable t) {
            Log.w(Backdrop.TAG, "convert capture failed", t);
        }
        cb.onBitmap(out);
    }

    private static SurfaceControl windowSurface(View host) {
        try {
            Object vri = View.class.getMethod("getViewRootImpl").invoke(host);
            if (vri == null) return null;
            Object sc = vri.getClass().getMethod("getSurfaceControl").invoke(vri);
            return sc instanceof SurfaceControl ? (SurfaceControl) sc : null;
        } catch (Throwable t) {
            Log.w(Backdrop.TAG, "get window surface failed", t);
            return null;
        }
    }
}
