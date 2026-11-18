package br.com.guucds.stxcontrol;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Proteção contra adulteração (@guucds).
 *  - confere se o APK foi assinado com a chave original (se alguém reempacotar/modificar, a assinatura muda);
 *  - confere se o app web embutido (index.html) é idêntico ao da compilação (hash gravado no build);
 *  - detecta depurador, app marcado como depurável e ferramentas de injeção (Frida) carregadas.
 * Nenhuma senha ou chave privada fica aqui: só o hash PÚBLICO do certificado, mascarado.
 */
final class Guard {
    private Guard() {}

    // SHA-256 do certificado de assinatura, mascarado (XOR) para não aparecer como texto no APK
    private static final byte[] M = { (byte)0x35, (byte)0x07, (byte)0xD1, (byte)0x50, (byte)0x5C, (byte)0x4B, (byte)0xA9, (byte)0xAD, (byte)0x97, (byte)0x47, (byte)0x60, (byte)0x75, (byte)0x19, (byte)0xB8, (byte)0x52, (byte)0x5C, (byte)0xDD, (byte)0xCA, (byte)0x60, (byte)0x47, (byte)0x8B, (byte)0xEE, (byte)0x98, (byte)0x25, (byte)0xE4, (byte)0x23, (byte)0x3D, (byte)0x0C, (byte)0x20, (byte)0x06, (byte)0xB5, (byte)0xD5 };

    static final int OK = 0, BAD_SIG = 1, BAD_WEB = 2, DEBUG = 4, HOOK = 8;

    private static byte[] expected() {
        byte[] r = new byte[32];
        for (int i = 0; i < 32; i++) r[i] = (byte) (M[i] ^ ((0x5A + i * 7) & 0xFF));
        return r;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    @SuppressWarnings("deprecation")
    private static boolean signatureOk(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi.signingInfo == null || pi.signingInfo.hasMultipleSigners()) return false;
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                sigs = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs == null || sigs.length != 1) return false;
            byte[] got = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
            return MessageDigest.isEqual(got, expected());
        } catch (Throwable t) { return false; }
    }

    private static boolean webOk(Context c) {
        String want = Integrity.WEB_SHA256;
        if (want == null || want.isEmpty()) return true; // compilação local sem hash
        try (InputStream in = c.getAssets().open("www/index.html")) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) d.update(buf, 0, n);
            return hex(d.digest()).equalsIgnoreCase(want);
        } catch (Throwable t) { return false; }
    }

    private static boolean debugged(Context c) {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
                || (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static boolean hooked() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String l;
            while ((l = r.readLine()) != null) {
                String s = l.toLowerCase();
                if (s.contains("frida") || s.contains("gum-js") || s.contains("xposed") || s.contains("substrate")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Resultado em bits (0 = tudo certo). */
    static int check(Context c) {
        int r = OK;
        if (!signatureOk(c)) r |= BAD_SIG;
        if (!webOk(c)) r |= BAD_WEB;
        if (debugged(c)) r |= DEBUG;
        if (hooked()) r |= HOOK;
        return r;
    }
}
