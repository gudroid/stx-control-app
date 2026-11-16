package br.com.guucds.stxcontrol;

import android.util.Log;

/** Log que só funciona em build depurável (no APK final não sai nada, sem pistas para quem analisa). */
final class Lg {
    static boolean on = false;
    private Lg() {}
    static void i(String t, String m) { if (on) Log.i(t, m); }
    static void d(String t, String m) { if (on) Log.d(t, m); }
    static void w(String t, String m) { if (on) Log.w(t, m); }
    static void e(String t, String m) { if (on) Log.e(t, m); }
    static void v(String t, String m) { if (on) Log.v(t, m); }
}
