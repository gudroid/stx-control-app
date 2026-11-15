package br.com.guucds.stxcontrol;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * Bluetooth (BLE) nativo do STX Control.
 * Canal do STX: serviço 0427, envio pelo 0429 (escrita com confirmação), respostas pelo 0428 (notificação).
 * Todo pacote enviado/recebido vai para o logcat com a etiqueta STXBLE (diagnóstico pelo cabo).
 */
@SuppressLint("MissingPermission")
public class BleBridge {
    static final String TAG = "STXBLE";
    static final UUID SVC = UUID.fromString("00000427-0000-1000-8000-00805f9b34fb");
    static final UUID RX = UUID.fromString("00000428-0000-1000-8000-00805f9b34fb");
    static final UUID TX = UUID.fromString("00000429-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final MainActivity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txChar, rxChar;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private volatile boolean ready = false, manual = false;
    private String address = null;

    // uma operação GATT por vez (o Android exige)
    private static class Op { final int kind; final byte[] data; final int id; Op(int k, byte[] d, int i) { kind = k; data = d; id = i; } }
    private final ArrayDeque<Op> queue = new ArrayDeque<>();
    private Op current = null;
    private Runnable opTimeout;

    BleBridge(MainActivity a) {
        act = a;
        prefs = a.getSharedPreferences("stx", Context.MODE_PRIVATE);
        BluetoothManager bm = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
    }

    // ---------- ponte para a tela ----------
    void emit(String ev, String data) {
        final String js = "window.StxBridge&&StxBridge.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")";
        main.post(() -> { try { act.web.evaluateJavascript(js, null); } catch (Exception ignored) {} });
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x & 255));
        return s.toString();
    }

    private static byte[] unhex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    @JavascriptInterface public String version() { return BuildConfigVersion.name(act); }
    @JavascriptInterface public boolean available() { return adapter != null; }
    @JavascriptInterface public boolean enabled() { return adapter != null && adapter.isEnabled(); }
    @JavascriptInterface public boolean permitted() { return act.hasPermissions(); }
    @JavascriptInterface public void askPermissions() { main.post(act::askPermissions); }
    @JavascriptInterface public String lastAddress() { return prefs.getString("addr", ""); }
    @JavascriptInterface public String lastName() { return prefs.getString("name", ""); }
    @JavascriptInterface public void forget() { prefs.edit().remove("addr").remove("name").apply(); }
    @JavascriptInterface public boolean isReady() { return ready; }
    @JavascriptInterface public void log(String msg) { Log.i(TAG, "JS " + msg); }

    @JavascriptInterface public void enableBluetooth() {
        main.post(() -> { try { act.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception e) { Log.w(TAG, "enable: " + e); } });
    }

    @JavascriptInterface public void openSettings() {
        main.post(() -> { try { act.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) {} });
    }

    // ---------- busca de aparelhos ----------
    @JavascriptInterface public void startScan() {
        main.post(() -> {
            if (adapter == null || !adapter.isEnabled()) { emit("scanerr", "bluetooth-off"); return; }
            if (!act.hasPermissions()) { act.askPermissions(); emit("scanerr", "permission"); return; }
            stopScanInternal();
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { emit("scanerr", "no-scanner"); return; }
            scanCb = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult r) {
                    try {
                        BluetoothDevice d = r.getDevice();
                        String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                        if (name == null) name = d.getName();
                        JSONObject o = new JSONObject();
                        o.put("address", d.getAddress()); o.put("name", name == null ? "" : name); o.put("rssi", r.getRssi());
                        emit("found", o.toString());
                    } catch (Exception ignored) {}
                }
                @Override public void onScanFailed(int code) { emit("scanerr", "fail-" + code); }
            };
            ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            try { scanner.startScan(null, st, scanCb); Log.i(TAG, "scan start"); }
            catch (Exception e) { emit("scanerr", String.valueOf(e.getMessage())); }
            main.postDelayed(this::stopScanInternal, 15000);
        });
    }

    @JavascriptInterface public void stopScan() { main.post(this::stopScanInternal); }

    private void stopScanInternal() {
        try { if (scanner != null && scanCb != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        scanCb = null;
    }

    // ---------- conexão ----------
    @JavascriptInterface public void connect(final String addr) {
        main.post(() -> {
            stopScanInternal();
            if (adapter == null || !adapter.isEnabled()) { emit("state", "off:bluetooth-off"); return; }
            if (!act.hasPermissions()) { act.askPermissions(); emit("state", "off:permission"); return; }
            closeGatt();
            manual = false; address = addr;
            BluetoothDevice dev;
            try { dev = adapter.getRemoteDevice(addr); } catch (Exception e) { emit("state", "off:bad-address"); return; }
            Log.i(TAG, "connect " + addr);
            emit("state", "connecting");
            if (Build.VERSION.SDK_INT >= 23) gatt = dev.connectGatt(act, false, cb, BluetoothDevice.TRANSPORT_LE);
            else gatt = dev.connectGatt(act, false, cb);
        });
    }

    @JavascriptInterface public void disconnect() {
        main.post(() -> { manual = true; closeGatt(); emit("state", "off:manual"); });
    }

    private void closeGatt() {
        ready = false; txChar = rxChar = null;
        synchronized (queue) { queue.clear(); current = null; }
        if (gatt != null) { try { gatt.disconnect(); } catch (Exception ignored) {} try { gatt.close(); } catch (Exception ignored) {} }
        gatt = null;
    }

    void shutdown() { manual = true; stopScanInternal(); closeGatt(); }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            Log.i(TAG, "connState status=" + status + " state=" + state);
            if (g != gatt) return;
            if (state == BluetoothProfile.STATE_CONNECTED) {
                main.post(() -> { if (gatt != null) gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); });
                main.postDelayed(() -> { if (gatt != null && !gatt.requestMtu(251)) gatt.discoverServices(); }, 150);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                main.post(() -> { if (g == gatt) { try { g.close(); } catch (Exception ignored) {} gatt = null; } });
                synchronized (queue) { failAll(); }
                emit("state", "off:" + status + (manual ? ":manual" : ""));
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            Log.i(TAG, "mtu " + mtu + " status=" + status);
            main.post(() -> { if (g == gatt) g.discoverServices(); });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (g != gatt) return;
            BluetoothGattService s = g.getService(SVC);
            if (s == null) { Log.w(TAG, "service 0427 missing"); emit("state", "off:no-service"); main.post(() -> closeGatt()); return; }
            txChar = s.getCharacteristic(TX); rxChar = s.getCharacteristic(RX);
            if (txChar == null || rxChar == null) { emit("state", "off:no-char"); main.post(() -> closeGatt()); return; }
            g.setCharacteristicNotification(rxChar, true);
            BluetoothGattDescriptor d = rxChar.getDescriptor(CCCD);
            if (d != null) enqueue(new Op(1, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, -1));
            else markReady();
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Log.i(TAG, "cccd status=" + status);
            finishOp(status);
            markReady();
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            finishOp(status);
        }

        // Android 13+
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onRx(value);
        }

        // Android 12 e anteriores
        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) onRx(c.getValue());
        }
    };

    private void markReady() {
        if (ready) return;
        ready = true;
        if (gatt != null && gatt.getDevice() != null) {
            String name = gatt.getDevice().getName();
            prefs.edit().putString("addr", gatt.getDevice().getAddress()).putString("name", name == null ? "STX" : name).apply();
        }
        Log.i(TAG, "ready");
        emit("state", "ready");
    }

    private void onRx(byte[] v) {
        if (v == null) return;
        String h = hex(v);
        // os avisos de tensão chegam várias vezes por segundo: não poluem o logcat
        if (!(v.length > 1 && (v[1] & 255) == 0xCB)) Log.d(TAG, "RX " + h);
        emit("data", h);
    }

    // ---------- envio ----------
    @JavascriptInterface public boolean write(String h, int id) {
        if (!ready || gatt == null || txChar == null) { emit("w", id + ":-1"); return false; }
        byte[] b;
        try { b = unhex(h); } catch (Exception e) { emit("w", id + ":-2"); return false; }
        enqueue(new Op(0, b, id));
        return true;
    }

    private void enqueue(Op op) {
        synchronized (queue) { queue.add(op); }
        main.post(this::pump);
    }

    @SuppressWarnings("deprecation")
    private void pump() {
        Op op;
        synchronized (queue) {
            if (current != null || queue.isEmpty()) return;
            op = current = queue.poll();
        }
        boolean ok = false;
        try {
            if (gatt == null) ok = false;
            else if (op.kind == 0) {
                Log.d(TAG, "TX " + hex(op.data));
                if (Build.VERSION.SDK_INT >= 33) ok = gatt.writeCharacteristic(txChar, op.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0;
                else { txChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); txChar.setValue(op.data); ok = gatt.writeCharacteristic(txChar); }
            } else {
                BluetoothGattDescriptor d = rxChar.getDescriptor(CCCD);
                if (Build.VERSION.SDK_INT >= 33) ok = gatt.writeDescriptor(d, op.data) == 0;
                else { d.setValue(op.data); ok = gatt.writeDescriptor(d); }
            }
        } catch (Exception e) { Log.w(TAG, "write ex " + e); }
        if (!ok) { finishOp(-3); if (op.kind == 1) markReady(); return; }
        final Op mine = op;
        opTimeout = () -> { synchronized (queue) { if (current != mine) return; } Log.w(TAG, "op timeout"); finishOp(-4); };
        main.postDelayed(opTimeout, 2500);
    }

    private void finishOp(int status) {
        Op op;
        synchronized (queue) { op = current; current = null; }
        if (opTimeout != null) main.removeCallbacks(opTimeout);
        if (op != null && op.kind == 0) emit("w", op.id + ":" + status);
        main.post(this::pump);
    }

    private void failAll() {
        if (current != null && current.kind == 0) emit("w", current.id + ":-5");
        for (Op o : queue) if (o.kind == 0) emit("w", o.id + ":-5");
        queue.clear(); current = null;
    }
}
