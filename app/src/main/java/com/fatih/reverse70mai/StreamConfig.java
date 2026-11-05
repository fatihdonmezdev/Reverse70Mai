package com.fatih.reverse70mai;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class StreamConfig {
    public static final String PREF_NAME = "reverse70mai_config";
    public static final String KEY_RTSP_URL = "custom_rtsp_url";
    public static final String KEY_AUTO_SCAN = "auto_scan_alternatives";

    public static final String DEFAULT_URL = "rtsp://192.168.0.1:554/livestream/12";

    public static final List<String> CANDIDATE_URLS = Arrays.asList(
            "rtsp://192.168.0.1:554/livestream/12",
            "rtsp://192.168.0.1:554/livestream/13",
            "rtsp://192.168.0.1:554/livestream/11",
            "rtsp://192.168.0.1:554/",
            "rtsp://192.168.0.1/liveRTSP/av4",
            "rtsp://192.168.0.1:554/liveRTSP/v1",
            "rtsp://192.168.0.1:8554/live",
            "rtsp://192.72.1.1/liveRTSP/av4"
    );

    public static String getActiveRtspUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_RTSP_URL, DEFAULT_URL);
    }

    public static void setActiveRtspUrl(Context context, String url) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_RTSP_URL, url).apply();
    }
}
