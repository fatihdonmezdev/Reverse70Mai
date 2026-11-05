package com.fatih.reverse70mai;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class OtaUpdater {

    private static final String TAG = "OtaUpdater";
    private static final String RELEASES_API =
            "https://api.github.com/repos/fatihdonmezdev/Reverse70Mai/releases";

    private static final List<String> ALLOWED_HOSTS = Arrays.asList(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com");

    private static final int TIMEOUT_MS = 10_000;

    private OtaUpdater() { }

    public static final class Update {
        public final String versionName;
        public final String apkUrl;
        public final String apkName;

        public Update(String versionName, String apkUrl, String apkName) {
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.apkName = apkName;
        }
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public static boolean isAllowedUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return false;
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) return false;
        String host = uri.getHost();
        return host != null && ALLOWED_HOSTS.contains(host.toLowerCase(Locale.US));
    }

    private static int[] segments(String version) {
        String core = version.startsWith("v") || version.startsWith("V")
                ? version.substring(1) : version;
        int cut = core.indexOf('+');
        if (cut >= 0) core = core.substring(0, cut);
        cut = core.indexOf('-');
        if (cut >= 0) core = core.substring(0, cut);

        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int digits = 0;
            while (digits < parts[i].length() && Character.isDigit(parts[i].charAt(digits))) digits++;
            try {
                out[i] = digits == 0 ? 0 : Integer.parseInt(parts[i].substring(0, digits));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    public static boolean isNewer(String remote, String current) {
        int[] r = segments(remote);
        int[] c = segments(current);
        for (int i = 0; i < Math.max(r.length, c.length); i++) {
            int rv = i < r.length ? r[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (rv > cv) return true;
            if (rv < cv) return false;
        }
        return false;
    }

    public static Update check(String currentVersion) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "Reverse70Mai-Android");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Release API returned " + status);
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) body.append(line);
            }

            return selectUpdate(new JSONArray(body.toString()), currentVersion);
        } catch (JSONException e) {
            throw new IOException("Invalid release response", e);
        } finally {
            conn.disconnect();
        }
    }

    public static Update selectUpdate(JSONArray releases, String currentVersion) throws JSONException {
        Update best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft", false)) continue;
            String tagName = release.optString("tag_name", "");
            String releaseVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            for (int a = 0; a < assets.length(); a++) {
                JSONObject asset = assets.getJSONObject(a);
                String name = asset.optString("name", "");
                if (!name.endsWith(".apk")) continue;

                String candidateVer = releaseVersion.isEmpty() ? "1.0.0" : releaseVersion;
                if (!isNewer(candidateVer, currentVersion)) continue;
                if (best != null && !isNewer(candidateVer, best.versionName)) continue;

                String url = asset.optString("browser_download_url", "");
                if (isAllowedUrl(url)) {
                    best = new Update(candidateVer, url, name);
                }
            }
        }
        return best;
    }

    public static File download(Context context, Update update, ProgressListener listener) {
        if (!isAllowedUrl(update.apkUrl)) {
            Log.w(TAG, "Refusing to download from " + update.apkUrl);
            return null;
        }
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return null;
        String publicName = "Reverse70Mai-" + update.versionName + ".apk";
        File publicFile = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), publicName);
        if (publicFile.exists() && !publicFile.delete()) return null;

        long downloadId = -1L;
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.apkUrl))
                    .setTitle("Reverse70Mai " + update.versionName)
                    .setDescription("Güncelleme indiriliyor...")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, publicName)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true);
            downloadId = manager.enqueue(request);

            while (true) {
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId))) {
                    if (cursor == null || !cursor.moveToFirst()) return null;
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long received = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if (listener != null) listener.onProgress(total > 0
                            ? (int) Math.min(100, received * 100 / total) : -1);
                    if (status == DownloadManager.STATUS_SUCCESSFUL) break;
                    if (status == DownloadManager.STATUS_FAILED) return null;
                }
                Thread.sleep(250L);
            }
            return publicFile;
        } catch (Exception e) {
            Log.w(TAG, "Update download failed", e);
        }
        return null;
    }

    /** Opens Android's Downloads UI directly so driver can tap the APK to install. */
    public static boolean openDownloads(Context context) {
        try {
            Intent downloads = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(downloads);
            return true;
        } catch (Throwable t) {
            try {
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (Build.VERSION.SDK_INT >= 26) {
                    picker.putExtra("android.provider.extra.INITIAL_URI", Uri.parse(
                            "content://com.android.externalstorage.documents/document/primary%3ADownload"));
                }
                context.startActivity(picker);
                return true;
            } catch (Throwable ignored) {
                Log.w(TAG, "No Downloads UI available", t);
                return false;
            }
        }
    }
}
