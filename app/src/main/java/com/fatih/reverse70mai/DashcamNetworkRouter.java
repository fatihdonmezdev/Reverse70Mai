package com.fatih.reverse70mai;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 70mai Dashcam Wi-Fi Network & Hardware Router.
 * 
 * Uses ConnectivityManager.requestNetwork without NET_CAPABILITY_INTERNET so Android:
 * 1. Does not disconnect Wi-Fi when detecting "no internet" (captive portal bypass).
 * 2. Keeps the Wi-Fi interface alive for the app even when tbox0 cellular is active.
 * 3. Explicitly binds process and socket routing to Wi-Fi.
 */
public class DashcamNetworkRouter {
    private static final String TAG = "DashcamNetworkRouter";
    public static final int DASHCAM_HTTP_PORT = 80;
    public static final int DASHCAM_RTSP_PORT = 554;

    private static volatile Network currentWifiNetwork = null;
    private static ConnectivityManager.NetworkCallback wifiNetworkCallback = null;

    /**
     * Actively requests the OS to keep the Wi-Fi connection alive and bound,
     * specifically without NET_CAPABILITY_INTERNET requirement.
     * This eliminates captive portal dropouts without needing root/adb settings.
     */
    public static synchronized void startWifiKeepAlive(Context context) {
        if (wifiNetworkCallback != null) return;

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        wifiNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                currentWifiNetwork = network;
                Log.i(TAG, "Wi-Fi Network available -> " + network);
                // Do NOT bind entire process here permanently.
                // Binding is only done specifically when streaming RTSP/CGI to dashcam,
                // so OTA check, GitHub, and backend logs can always route through internet.
            }

            @Override
            public void onLost(Network network) {
                Log.w(TAG, "Wi-Fi Network lost -> " + network);
                if (currentWifiNetwork != null && currentWifiNetwork.equals(network)) {
                    currentWifiNetwork = null;
                    unbindProcess(context);
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                currentWifiNetwork = network;
            }
        };

        try {
            cm.requestNetwork(request, wifiNetworkCallback);
            Log.i(TAG, "Active Wi-Fi keep-alive request registered.");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to register active Wi-Fi request: " + t.getMessage(), t);
        }
    }

    public static synchronized void stopWifiKeepAlive(Context context) {
        if (wifiNetworkCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(wifiNetworkCallback);
            } catch (Throwable ignored) {}
        }
        wifiNetworkCallback = null;
        currentWifiNetwork = null;
        unbindProcess(context);
    }

    /**
     * Finds the active Wi-Fi Network interface on the system.
     */
    public static Network getWifiNetwork(Context context) {
        if (currentWifiNetwork != null) return currentWifiNetwork;

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;

        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                currentWifiNetwork = net;
                return net;
            }
        }
        return null;
    }

    /**
     * Locks the current process's network traffic to the Wi-Fi network interface.
     * Essential for LibVLC to route RTSP packets to 192.168.0.1 instead of T-Box cellular.
     */
    public static boolean bindProcessToWifi(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        Network wifiNet = getWifiNetwork(context);
        if (wifiNet != null) {
            boolean success = cm.bindProcessToNetwork(wifiNet);
            Log.i(TAG, "Process bound to Wi-Fi network: " + success + " (" + wifiNet + ")");
            return success;
        } else {
            Log.w(TAG, "No Wi-Fi network found to bind process!");
            return false;
        }
    }

    /**
     * Unbinds the process from Wi-Fi, returning to system default routing.
     */
    public static void unbindProcess(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            cm.bindProcessToNetwork(null);
            Log.d(TAG, "Process unbound from Wi-Fi (default routing restored)");
        }
    }

    /**
     * Creates a socket explicitly bound to the Wi-Fi interface to bypass tbox0 routing.
     */
    public static Socket createWifiSocket(Context context) throws Exception {
        Network wifiNet = getWifiNetwork(context);
        if (wifiNet != null) {
            return wifiNet.getSocketFactory().createSocket();
        }
        Log.w(TAG, "Wi-Fi network not detected, falling back to standard socket");
        return new Socket();
    }

    /**
     * Resolves the connected dashcam's IP (DHCP gateway with family fallback).
     */
    public static String resolveDashcamIp(Context context) {
        DashcamModel.ModelInfo model = DashcamModel.detect(context);
        return DashcamModel.resolveCameraIp(context, model.family);
    }

    /**
     * Sends hardware sensor switch command to 70mai:
     * sensor=1 -> Rear camera sensor
     * sensor=0 -> Front camera sensor
     *
     * Hisi family: plain CGI first (proven on A410), then the official app's
     * MD5-signed variant (empty connectKey) if the camera enforces signatures.
     * Mstar family: no CGI command exists; switching happens via RTSP channel
     * change (/liveRTSP/av5 rear), handled by the candidate list in the service.
     * Legacy family: plain CGI attempted best-effort.
     */
    public static boolean switchSensorHardware(Context context, int sensor) {
        DashcamModel.ModelInfo model = DashcamModel.detect(context);

        if (model.family == DashcamModel.Family.MSTAR) {
            AppLogger.i(context, model.displayName + " (MSTAR): setwifistream.cgi yok, RTSP kanal degisimi kullaniliyor");
            return false;
        }

        String ip = DashcamModel.resolveCameraIp(context, model.family);
        AppLogger.i(context, "Sensör geçişi -> " + model.displayName + " (" + model.family + ") @ " + ip + " sensor=" + sensor);

        // Attempt 1: plain request (works on A410 and most Hisi firmware)
        String status = sendCgiGet(context, ip, "/cgi-bin/setwifistream.cgi?sensor=" + sensor, 2500);
        AppLogger.i(context, "70mai setwifistream sensor=" + sensor + " (plain) -> " + (status != null ? status : "YANIT YOK"));
        boolean ok = status != null && status.contains("200");

        // Attempt 2: official app's signed request (empty connectKey) for firmware that requires it
        if (!ok && model.family == DashcamModel.Family.HISI) {
            String signedPath = DashcamModel.buildSignedSensorPath(sensor, "");
            String signedStatus = sendCgiGet(context, ip, signedPath, 2500);
            AppLogger.i(context, "70mai setwifistream sensor=" + sensor + " (imzalı) -> " + (signedStatus != null ? signedStatus : "YANIT YOK"));
            ok = signedStatus != null && signedStatus.contains("200");
        }

        // Also send setrearview.cgi for A810/A810 Lite models
        try {
            String rearStatus = sendCgiGet(context, ip,
                    "/cgi-bin/setrearview.cgi?enable=" + (sensor == 1 ? "1" : "0"), 1500);
            AppLogger.i(context, "70mai setrearview enable=" + (sensor == 1 ? "1" : "0") + " -> " + (rearStatus != null ? rearStatus : "YANIT YOK"));
        } catch (Throwable t2) {
            // Not all firmware models support setrearview, ignore
        }

        try {
            Thread.sleep(400); // Sensor settling delay
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return ok;
    }

    /**
     * Sends a raw HTTP GET over a Wi-Fi-bound socket and returns the status line.
     */
    private static String sendCgiGet(Context context, String ip, String path, int timeoutMs) {
        Socket socket = null;
        try {
            socket = createWifiSocket(context);
            socket.connect(new InetSocketAddress(ip, DASHCAM_HTTP_PORT), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            OutputStream os = socket.getOutputStream();
            String request = "GET " + path + " HTTP/1.1\r\n" +
                    "Host: " + ip + "\r\n" +
                    "Connection: close\r\n\r\n";
            os.write(request.getBytes());
            os.flush();

            BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            return br.readLine();
        } catch (Throwable t) {
            return null;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
