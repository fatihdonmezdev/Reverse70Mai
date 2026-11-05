package com.fatih.reverse70mai;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

public final class StreamConfig {
    public static final String PREF_NAME = "reverse70mai_config";
    public static final String KEY_RTSP_URL = "custom_rtsp_url";
    public static final String KEY_AUTO_SCAN = "auto_scan_alternatives";

    public static final String DEFAULT_URL = "rtsp://192.168.0.1:554/livestream/12";

    public static String getActiveRtspUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_RTSP_URL, DEFAULT_URL);
    }

    public static void setActiveRtspUrl(Context context, String url) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_RTSP_URL, url).apply();
    }

    /**
     * Ordered RTSP candidates for the currently connected 70mai model.
     * The saved (last known good / user-entered) URL comes first, with its host
     * rewritten to the freshly resolved camera IP; then the family-specific
     * paths extracted from the official app.
     */
    public static List<String> buildCandidates(Context context) {
        DashcamModel.ModelInfo model = DashcamModel.detect(context);
        String ip = DashcamModel.resolveCameraIp(context, model.family);

        List<String> candidates = new ArrayList<>();
        String saved = getActiveRtspUrl(context);
        if (saved != null && !saved.trim().isEmpty()) {
            candidates.add(rewriteHostToCameraIp(saved.trim(), ip));
        }
        candidates.addAll(DashcamModel.buildRtspCandidates(ip, model.family));

        return new ArrayList<>(new LinkedHashSet<>(candidates));
    }

    /**
     * If the saved URL points at a well-known 70mai default IP but the camera now
     * lives on a different subnet (e.g. A810 Lite), swap the host so the stale
     * saved value cannot break the stream. User-entered custom hosts are kept.
     */
    private static String rewriteHostToCameraIp(String url, String cameraIp) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null || host.equals(cameraIp)) {
                return url;
            }
            boolean isDefaultHisi = DashcamModel.DEFAULT_HISI_IP.equals(host);
            boolean isDefaultMstar = DashcamModel.DEFAULT_MSTAR_IP.equals(host);
            if (!isDefaultHisi && !isDefaultMstar) {
                return url;
            }
            int port = uri.getPort();
            String rest = uri.getRawPath();
            if (rest == null || rest.isEmpty()) {
                rest = "/";
            }
            if (uri.getRawQuery() != null) {
                rest = rest + "?" + uri.getRawQuery();
            }
            return "rtsp://" + cameraIp + (port != -1 ? ":" + port : "") + rest;
        } catch (Throwable t) {
            return url;
        }
    }
}
