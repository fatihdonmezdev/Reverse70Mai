package com.fatih.reverse70mai;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Universal 70mai dashcam model detection and protocol resolution.
 *
 * Reverse engineered from the official 70mai app (com.banyac.midrive.app.intl 3.6.1):
 * - The camera family is detected from the Wi-Fi SSID prefix the dashcam broadcasts.
 * - The camera IP is resolved from the DHCP gateway (NetWorkUtils.f), falling back to
 *   the family-specific default (Hisi: 192.168.0.1, Mstar: 192.72.1.1).
 * - Hisi family: front/rear switching via /cgi-bin/setwifistream.cgi, plain or
 *   MD5-signed exactly like the official app (HisiCommand buildSignedRequestUrl).
 * - Mstar family: no CGI command exists; front and rear are separate RTSP paths
 *   (/liveRTSP/av4 front, /liveRTSP/av5 rear, MainLoadV2PresenterImpl).
 * - Legacy CardDV devices use the same /liveRTSP/* paths.
 */
public final class DashcamModel {

    public enum Family {
        HISI,
        MSTAR,
        LEGACY
    }

    public static final class ModelInfo {
        public final Family family;
        public final String ssid;
        public final String matchedPrefix;
        public final String displayName;

        ModelInfo(Family family, String ssid, String matchedPrefix, String displayName) {
            this.family = family;
            this.ssid = ssid;
            this.matchedPrefix = matchedPrefix;
            this.displayName = displayName;
        }

        public boolean isKnown() {
            return matchedPrefix != null;
        }
    }

    public static final String DEFAULT_HISI_IP = "192.168.0.1";
    public static final String DEFAULT_MSTAR_IP = "192.72.1.1";

    private static final String[][] SSID_TABLE = {
            // Hisi family (livestream/12 + setwifistream.cgi)
            {"70mai_A810 Lite 2_", "HISI"},
            {"70mai_A810 LiteSP_", "HISI"},
            {"70mai_A810 Lite_", "HISI"},
            {"70mai_A810 Plus_", "HISI"},
            {"70mai_A810 Ultra_", "HISI"},
            {"70mai_A810Pro_", "HISI"},
            {"70mai_A810SE_", "HISI"},
            {"70mai_A810S_", "HISI"},
            {"70mai_A810", "HISI"},
            {"70mai_A820_", "HISI"},
            {"70mai_A900 Ultra_", "HISI"},
            {"70mai_A900_", "HISI"},
            {"70mai_A410 Plus 4K_", "HISI"},
            {"70mai_A410NANO_", "HISI"},
            {"70mai_A410 Neo_", "HISI"},
            {"70mai_A410_", "HISI"},
            {"70mai_A400 Pro 4K_", "HISI"},
            {"70mai_A400PRO4K_", "HISI"},
            {"70mai_A400PRO3K_", "HISI"},
            {"70mai_A400X 4K_", "HISI"},
            {"70mai_A400X4K_", "HISI"},
            {"70mai_A400X3K_", "HISI"},
            {"70mai_A400_", "HISI"},
            {"70mai_A510Lite_", "HISI"},
            {"70mai_A510Neo_", "HISI"},
            {"70mai_A510_", "HISI"},
            {"70mai_A500S_", "HISI"},
            {"70mai_A800NANO_", "HISI"},
            {"70mai_A800SE2_", "HISI"},
            {"70mai_A800SE_", "HISI"},
            {"70mai_C460_", "HISI"},
            {"70mai_E800R_", "HISI"},
            {"70mai_E800_", "HISI"},
            {"70mai_F800_", "HISI"},
            {"70mai_M800Pro_", "HISI"},
            {"70mai_M800_", "HISI"},
            {"70mai_M500_", "HISI"},
            {"70mai_M210_", "HISI"},
            {"70mai_N500SE_", "HISI"},
            {"70mai_N500_", "HISI"},
            {"70mai_N400_", "HISI"},
            {"70mai_N800_", "HISI"},
            {"70mai_P800_", "HISI"},
            {"70mai_Pro Plus+_", "HISI"},
            {"70mai_R400_", "HISI"},
            {"70mai_T800E2_", "HISI"},
            {"70mai_T800E_", "HISI"},
            {"70mai_T800_", "HISI"},
            {"70mai_T400_", "HISI"},
            {"70mai_X800_", "HISI"},
            {"70mai_X200_", "HISI"},
            {"70mai_d02_", "HISI"},
            {"70mai_d03_", "HISI"},
            {"70mai_d05_", "HISI"},
            {"70mai_A200_", "HISI"},
            {"70mai_A210_", "HISI"},
            {"70mai_M310 Plus 3K_", "HISI"},
            // Mstar family (liveRTSP paths, no CGI sensor command)
            {"70mai_A500Lite_", "MSTAR"},
            {"70mai_Lite2_", "MSTAR"},
            {"70mai_A800S_", "MSTAR"},
            {"70mai_S500_", "MSTAR"},
            {"70mai_M200_", "MSTAR"},
            {"70mai_M310_", "MSTAR"},
            // Legacy CardDV devices (same liveRTSP paths)
            {"70mai_A800_4K_", "LEGACY"},
            {"70mai_M310 Plus 4K_", "LEGACY"},
            {"70mai_M310 Plus 2K_", "LEGACY"},
            {"70mai_M310 Pro 4K_", "LEGACY"},
            {"70mai_M310 Pro 3K_", "LEGACY"},
            {"70mai_M310 Pro 2K_", "LEGACY"},
            {"70mai_M310X 4K_", "LEGACY"},
            {"70mai_M310X 3K_", "LEGACY"},
            {"70mai_A500_", "LEGACY"},
            {"70mai_S410_", "LEGACY"},
            {"70mai_S400_", "LEGACY"},
            {"70mai_M300_", "LEGACY"},
            {"70mai_M300", "LEGACY"},
            {"70mai_d01_", "LEGACY"},
            {"70mai_d06_", "LEGACY"},
            {"70mai_d07_", "LEGACY"},
            {"70mai_d08_", "LEGACY"},
    };

    static {
        // Longest prefix must win: "70mai_A810 Lite_" before "70mai_A810",
        // "70mai_A400 Pro 4K_" before "70mai_A400_", etc.
        Arrays.sort(SSID_TABLE, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return Integer.compare(b[0].length(), a[0].length());
            }
        });
    }

    private DashcamModel() {}

    /**
     * Detects the connected 70mai model from the current Wi-Fi SSID.
     * Unknown SSIDs fall back to Hisi behaviour (most common family);
     * the DHCP gateway plus RTSP candidate cycling still recovers those.
     */
    public static ModelInfo detect(Context context) {
        String ssid = readCurrentSsid(context);
        for (String[] entry : SSID_TABLE) {
            if (ssid.startsWith(entry[0])) {
                return new ModelInfo(Family.valueOf(entry[1]), ssid, entry[0], prettyName(entry[0]));
            }
        }
        return new ModelInfo(Family.HISI, ssid, null, ssid.isEmpty() ? "Bilinmeyen (SSID okunamadi)" : ssid);
    }

    /**
     * Reads the SSID of the currently connected Wi-Fi (best effort).
     * If WifiInfo returns <unknown ssid> due to Android location permission,
     * inspects getConfiguredNetworks() to find the active or 70mai network name.
     */
    public static String readCurrentSsid(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    String ssid = info.getSSID();
                    if (ssid != null) {
                        ssid = ssid.replace("\"", "").trim();
                        if (!ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid) && !"0x".equalsIgnoreCase(ssid)) {
                            return ssid;
                        }
                    }
                }

                // Fallback: Check configured networks for 70mai SSID
                try {
                    List<android.net.wifi.WifiConfiguration> configs = wm.getConfiguredNetworks();
                    if (configs != null) {
                        for (android.net.wifi.WifiConfiguration config : configs) {
                            if (config != null && config.SSID != null) {
                                String clean = config.SSID.replace("\"", "").trim();
                                if (clean.startsWith("70mai_")) {
                                    return clean;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * Resolves the dashcam IP exactly like the official app (NetWorkUtils.f):
     * the DHCP gateway of the camera's Wi-Fi hotspot, little-endian decoded.
     */
    public static String resolveCameraIp(Context context, Family family) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    int gw = dhcp.gateway;
                    String ip = String.format(Locale.US, "%d.%d.%d.%d",
                            gw & 0xff, (gw >> 8) & 0xff, (gw >> 16) & 0xff, (gw >> 24) & 0xff);
                    if (!"0.0.0.0".equals(ip)) {
                        return ip;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return family == Family.MSTAR ? DEFAULT_MSTAR_IP : DEFAULT_HISI_IP;
    }

    /**
     * Ordered RTSP candidates for the family. The overlay only streams while in
     * reverse gear, so for Mstar/Legacy the rear channel (av5) is tried first.
     */
    public static List<String> buildRtspCandidates(String ip, Family family) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (family == Family.HISI) {
            urls.add("rtsp://" + ip + ":554/livestream/12");
            urls.add("rtsp://" + ip + ":554/livestream/13");
            urls.add("rtsp://" + ip + ":554/livestream/11");
            addLiveRtspPaths(urls, ip);
        } else {
            addLiveRtspPaths(urls, ip);
            urls.add("rtsp://" + ip + ":554/livestream/12");
            urls.add("rtsp://" + ip + ":554/livestream/13");
        }
        return new ArrayList<>(urls);
    }

    private static void addLiveRtspPaths(LinkedHashSet<String> urls, String ip) {
        urls.add("rtsp://" + ip + "/liveRTSP/av5");
        urls.add("rtsp://" + ip + "/liveRTSP/av4");
        urls.add("rtsp://" + ip + "/liveRTSP/av1");
        urls.add("rtsp://" + ip + "/liveRTSP/av2");
        urls.add("rtsp://" + ip + "/liveRTSP/v1");
    }

    /**
     * Signed setwifistream.cgi path, replicating the official HisiCommand
     * buildSignedRequestUrl with an empty connectKey (unbound camera):
     *
     *   /cgi-bin/setwifistream.cgi?&-sensor=1&-timestamp=<epoch>&-signkey=<md5>
     *   md5 input: "setwifistream.cgi?&-sensor=1&-timestamp=<epoch>" + connectKey
     */
    public static String buildSignedSensorPath(int sensor, String connectKey) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String md5Input = "setwifistream.cgi?&-sensor=" + sensor
                + "&-timestamp=" + timestamp
                + (connectKey == null ? "" : connectKey);
        return "/cgi-bin/setwifistream.cgi?&-sensor=" + sensor
                + "&-timestamp=" + timestamp
                + "&-signkey=" + md5Hex(md5Input);
    }

    /**
     * Lowercase hex MD5, byte-for-byte identical to the official app's j.f().
     */
    public static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int value = b & 0xff;
                if (value < 16) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(value));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String prettyName(String prefix) {
        String name = prefix;
        if (name.startsWith("70mai_")) {
            name = name.substring(6);
        } else if (name.startsWith("70mai")) {
            name = name.substring(5);
        }
        if (name.endsWith("_")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }
}
