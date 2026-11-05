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
    public static final String DASHCAM_IP = "192.168.0.1";
    public static final int DASHCAM_HTTP_PORT = 80;
    public static final int DASHCAM_RTSP_PORT = 554;
    public static final String RTSP_URL = "rtsp://192.168.0.1:554/livestream/12";

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
                // Bind process so all LibVLC and socket traffic automatically routes to Wi-Fi
                bindProcessToWifi(context);
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
     * Sends hardware sensor switch command to 70mai:
     * sensor=1 -> Rear camera sensor
     * sensor=0 -> Front camera sensor
     */
    public static boolean switchSensorHardware(Context context, int sensor) {
        Socket socket = null;
        try {
            socket = createWifiSocket(context);
            socket.connect(new InetSocketAddress(DASHCAM_IP, DASHCAM_HTTP_PORT), 2500);
            socket.setSoTimeout(2500);

            OutputStream os = socket.getOutputStream();
            String req1 = "GET /cgi-bin/setwifistream.cgi?sensor=" + sensor + " HTTP/1.1\r\n" +
                    "Host: " + DASHCAM_IP + "\r\n" +
                    "Connection: close\r\n\r\n";
            os.write(req1.getBytes());
            os.flush();

            BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String statusLine = br.readLine();
            AppLogger.i(context, "70mai setwifistream sensor=" + sensor + " -> " + statusLine);
            socket.close();

            // Also send setrearview.cgi for A810/A810 Lite models
            try {
                Socket s2 = createWifiSocket(context);
                s2.connect(new InetSocketAddress(DASHCAM_IP, DASHCAM_HTTP_PORT), 1500);
                s2.setSoTimeout(1500);
                OutputStream os2 = s2.getOutputStream();
                String req2 = "GET /cgi-bin/setrearview.cgi?enable=" + (sensor == 1 ? "1" : "0") + " HTTP/1.1\r\n" +
                        "Host: " + DASHCAM_IP + "\r\n" +
                        "Connection: close\r\n\r\n";
                os2.write(req2.getBytes());
                os2.flush();
                BufferedReader br2 = new BufferedReader(new InputStreamReader(s2.getInputStream()));
                String resp2 = br2.readLine();
                AppLogger.i(context, "70mai setrearview enable=" + (sensor == 1 ? "1" : "0") + " -> " + resp2);
                s2.close();
            } catch (Throwable t2) {
                // Not all firmware models support setrearview, ignore
            }

            Thread.sleep(400); // Sensor settling delay
            return statusLine != null && statusLine.contains("200");
        } catch (Throwable t) {
            AppLogger.e(context, "Failed to switch 70mai sensor hardware to " + sensor, t);
            if (socket != null) {
                try { socket.close(); } catch (Throwable ignored) {}
            }
            return false;
        }
    }
}
