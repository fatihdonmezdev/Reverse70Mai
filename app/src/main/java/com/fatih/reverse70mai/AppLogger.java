package com.fatih.reverse70mai;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppLogger {
    private static final String TAG = "Reverse70Mai";
    private static final String CACHE_FILE = "reverse70mai_logs.json";
    private static final String ENDPOINT = "https://mg-web-app-eta.vercel.app/api/logs";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat ISO_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);

    public interface LogListener {
        void onNewLog(String line);
    }

    private static final List<LogListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<String> RECENT_LOGS = new ArrayList<>();

    static {
        ISO_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private AppLogger() {}

    public static void addListener(LogListener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(LogListener listener) {
        LISTENERS.remove(listener);
    }

    public static synchronized List<String> getRecentLogs() {
        return new ArrayList<>(RECENT_LOGS);
    }

    public static void i(Context context, String message) {
        log(context, "INFO", message, null);
    }

    public static void w(Context context, String message) {
        log(context, "WARN", message, null);
    }

    public static void e(Context context, String message, Throwable t) {
        String details = (t != null) ? Log.getStackTraceString(t) : null;
        log(context, "ERROR", message, details);
    }

    public static void log(Context context, String level, String message, String details) {
        String formatted = String.format(Locale.getDefault(), "[%s] %s%s",
                level, message, details != null ? " (" + details + ")" : "");
        Log.println(
                "ERROR".equals(level) ? Log.ERROR : ("WARN".equals(level) ? Log.WARN : Log.INFO),
                TAG,
                formatted
        );

        synchronized (RECENT_LOGS) {
            RECENT_LOGS.add(formatted);
            if (RECENT_LOGS.size() > 100) {
                RECENT_LOGS.remove(0);
            }
        }

        for (LogListener l : LISTENERS) {
            try {
                l.onNewLog(formatted);
            } catch (Throwable ignored) {}
        }

        if (context == null) return;
        final Context appContext = context.getApplicationContext();

        EXECUTOR.execute(() -> {
            try {
                JSONObject entry = new JSONObject();
                entry.put("tag", "Reverse70Mai");
                entry.put("level", level);
                entry.put("message", message);

                JSONObject netDiag = collectNetworkDiagnostics(appContext);
                if (details != null && !details.isEmpty()) {
                    netDiag.put("errorDetails", details);
                }
                entry.put("details", netDiag);

                synchronized (ISO_FORMAT) {
                    entry.put("timestamp", ISO_FORMAT.format(new Date()));
                }

                appendCache(appContext, entry);

                if (isNetworkConnected(appContext)) {
                    flushCache(appContext);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Log buffering failed: " + t.getMessage());
            }
        });
    }

    private static JSONObject collectNetworkDiagnostics(Context context) {
        JSONObject diag = new JSONObject();
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    String ssid = info.getSSID();
                    if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    diag.put("wifiSsid", ssid);
                    diag.put("bssid", info.getBSSID());
                    diag.put("rssi", info.getRssi());

                    int ip = info.getIpAddress();
                    String ipStr = String.format(Locale.US, "%d.%d.%d.%d",
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    diag.put("deviceIp", ipStr);
                }

                android.net.DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null) {
                    int gw = dhcp.gateway;
                    String gwStr = String.format(Locale.US, "%d.%d.%d.%d",
                            (gw & 0xff), (gw >> 8 & 0xff), (gw >> 16 & 0xff), (gw >> 24 & 0xff));
                    diag.put("gatewayIp", gwStr);
                }
            }

            DashcamModel.ModelInfo model = DashcamModel.detect(context);
            String camIp = DashcamModel.resolveCameraIp(context, model.family);
            diag.put("cameraModel", model.displayName);
            diag.put("cameraFamily", model.family.name());
            diag.put("cameraIp", camIp);
            diag.put("rtsp554Reachable", testSocket(camIp, 554, 400));
            diag.put("http80Reachable", testSocket(camIp, 80, 400));
        } catch (Throwable ignored) {}
        return diag;
    }

    public static boolean testSocket(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized void appendCache(Context context, JSONObject entry) {
        try {
            File file = new File(context.getCacheDir(), CACHE_FILE);
            JSONArray array;
            if (file.exists() && file.length() > 0) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] data = new byte[(int) file.length()];
                    fis.read(data);
                    array = new JSONArray(new String(data, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    array = new JSONArray();
                }
            } else {
                array = new JSONArray();
            }

            if (array.length() >= 250) {
                JSONArray pruned = new JSONArray();
                for (int i = 50; i < array.length(); i++) {
                    pruned.put(array.get(i));
                }
                array = pruned;
            }

            array.put(entry);

            try (FileOutputStream fos = new FileOutputStream(file, false)) {
                fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to write cache: " + e.getMessage());
        }
    }

    public static synchronized void flushCache(Context context) {
        File file = new File(context.getCacheDir(), CACHE_FILE);
        if (!file.exists() || file.length() == 0) return;

        try {
            JSONArray array;
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                array = new JSONArray(new String(data, StandardCharsets.UTF_8));
            }

            if (array.length() == 0) return;

            JSONObject payload = new JSONObject();
            payload.put("installationId", "reverse70mai-sami-or-fatih");
            payload.put("logs", array);

            URL url = new URL(ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "Reverse70Mai/1.0.1");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            byte[] jsonBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                file.delete();
                Log.i(TAG, "Flushed " + array.length() + " diagnostic logs to backend.");
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "Could not flush diagnostic logs: " + e.getMessage());
        }
    }

    private static boolean isNetworkConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        } catch (Exception e) {
            return false;
        }
    }
}
