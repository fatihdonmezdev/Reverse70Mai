package com.fatih.reverse70mai;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY_PERM = 1001;
    private static final int REQ_LOCATION_PERM = 1002;

    private TextView tvStatusTitle;
    private TextView tvWifiInfo;
    private EditText etRtspUrl;
    private Button btnSaveUrl;
    private Button btnScanAlternatives;
    private Button btnRunDiag;
    private Button btnTestOverlay;
    private Button btnSwitchFront;
    private Button btnCheckUpdate;
    private TextView tvLogs;
    private Button btnSendLogsNow;

    private boolean currentIsRear = true;

    private final AppLogger.LogListener logListener = line -> runOnUiThread(() -> {
        if (tvLogs != null) {
            tvLogs.append(line + "\n");
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatusTitle = findViewById(R.id.tvStatusTitle);
        tvWifiInfo = findViewById(R.id.tvWifiInfo);
        etRtspUrl = findViewById(R.id.etRtspUrl);
        btnSaveUrl = findViewById(R.id.btnSaveUrl);
        btnScanAlternatives = findViewById(R.id.btnScanAlternatives);
        btnRunDiag = findViewById(R.id.btnRunDiag);
        btnTestOverlay = findViewById(R.id.btnTestOverlay);
        btnSwitchFront = findViewById(R.id.btnSwitchFront);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        tvLogs = findViewById(R.id.tvLogs);
        btnSendLogsNow = findViewById(R.id.btnSendLogsNow);

        if (tvLogs != null) {
            tvLogs.setMovementMethod(new ScrollingMovementMethod());
            List<String> rec = AppLogger.getRecentLogs();
            for (String r : rec) {
                tvLogs.append(r + "\n");
            }
        }
        AppLogger.addListener(logListener);

        // Load active RTSP URL
        String activeUrl = StreamConfig.getActiveRtspUrl(this);
        etRtspUrl.setText(activeUrl);

        btnSaveUrl.setOnClickListener(v -> {
            String newUrl = etRtspUrl.getText().toString().trim();
            if (!newUrl.isEmpty()) {
                StreamConfig.setActiveRtspUrl(this, newUrl);
                AppLogger.i(this, "Aktif RTSP URL güncellendi: " + newUrl);
                Toast.makeText(this, "RTSP URL kaydedildi!", Toast.LENGTH_SHORT).show();
            }
        });

        checkOverlayPermission();
        ensureLocationPermissionForSsid();
        startServiceExplicitly();

        btnTestOverlay.setOnClickListener(v -> {
            AppLogger.i(this, "Kullanıcı 5 sn test butonuna bastı");
            Intent testIntent = new Intent(this, Reverse70MaiService.class);
            testIntent.setAction(Reverse70MaiService.ACTION_TEST_OVERLAY);
            startService(testIntent);
            Toast.makeText(this, "5 saniyelik test yayını başlatıldı", Toast.LENGTH_SHORT).show();
        });

        btnSwitchFront.setOnClickListener(v -> {
            currentIsRear = !currentIsRear;
            int targetSensor = currentIsRear ? 1 : 0;
            AppLogger.i(this, "Sensör değiştirme tetiklendi: " + (currentIsRear ? "ARKA (1)" : "ÖN (0)"));
            new Thread(() -> {
                boolean ok = DashcamNetworkRouter.switchSensorHardware(this, targetSensor);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Sensör: " + (currentIsRear ? "ARKA" : "ÖN") + (ok ? " (Başarılı)" : " (Hata)"), Toast.LENGTH_SHORT).show();
                });
            }).start();
        });

        btnRunDiag.setOnClickListener(v -> runFullDiagnostics());

        btnScanAlternatives.setOnClickListener(v -> runStreamUrlScanner());

        btnCheckUpdate.setOnClickListener(v -> checkForUpdate());

        btnSendLogsNow.setOnClickListener(v -> {
            Toast.makeText(this, "Loglar merkeze gönderiliyor...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                AppLogger.flushCache(this);
                runOnUiThread(() -> Toast.makeText(this, "Log gönderimi tamamlandı!", Toast.LENGTH_SHORT).show());
            }).start();
        });

        updateNetworkStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLogger.removeListener(logListener);
    }

    private void runFullDiagnostics() {
        btnRunDiag.setEnabled(false);
        AppLogger.i(this, "=== TEŞHİS BAŞLATILDI ===");

        new Thread(() -> {
            try {
                // 0. Model & IP detection (SSID -> device family -> DHCP gateway)
                DashcamModel.ModelInfo model = DashcamModel.detect(this);
                String camIp = DashcamModel.resolveCameraIp(this, model.family);
                AppLogger.i(this, "0. Kamera Modeli: " + model.displayName
                        + " | Aile: " + model.family
                        + " | SSID: \"" + model.ssid + "\""
                        + (model.isKnown() ? "" : " (SSID tablosunda yok, Hisi varsayıldı)"));
                AppLogger.i(this, "0. Kamera IP (DHCP gateway / aile varsayılanı): " + camIp);

                // 1. Wi-Fi interface & IP
                Network wifiNet = DashcamNetworkRouter.getWifiNetwork(this);
                AppLogger.i(this, "1. Wi-Fi Ağ Nesnesi: " + (wifiNet != null ? wifiNet.toString() : "YOK/BAĞLI DEĞİL"));

                // 2. Camera IP Reachability (Port 80)
                boolean p80 = false;
                try (Socket s = (wifiNet != null ? wifiNet.getSocketFactory().createSocket() : new Socket())) {
                    s.connect(new InetSocketAddress(camIp, 80), 2000);
                    p80 = true;
                } catch (Throwable t) {
                    p80 = false;
                }
                AppLogger.i(this, "2. " + camIp + " Port 80 (HTTP/CGI): " + (p80 ? "AÇIK (BAŞARILI)" : "KAPALI / ERİŞİLEMEZ"));

                // 3. RTSP Standard Port 554 Reachability
                boolean p554 = false;
                try (Socket s = (wifiNet != null ? wifiNet.getSocketFactory().createSocket() : new Socket())) {
                    s.connect(new InetSocketAddress(camIp, 554), 2000);
                    p554 = true;
                } catch (Throwable t) {
                    p554 = false;
                }
                AppLogger.i(this, "3. " + camIp + " Port 554 (RTSP Standart): " + (p554 ? "AÇIK (BAŞARILI)" : "KAPALI"));

                // 4. CGI Sensor Test (plain)
                if (p80) {
                    try (Socket s = (wifiNet != null ? wifiNet.getSocketFactory().createSocket() : new Socket())) {
                        s.connect(new InetSocketAddress(camIp, 80), 2000);
                        s.setSoTimeout(2000);
                        OutputStream os = s.getOutputStream();
                        os.write(("GET /cgi-bin/setwifistream.cgi?sensor=1 HTTP/1.1\r\nHost: " + camIp + "\r\nConnection: close\r\n\r\n").getBytes());
                        os.flush();
                        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
                        String resp = br.readLine();
                        AppLogger.i(this, "4. CGI /setwifistream (plain) Yanıtı: " + (resp != null ? resp : "BOŞ DÖNDÜ"));
                    } catch (Throwable t) {
                        AppLogger.w(this, "4. CGI /setwifistream (plain) Hatası: " + t.getMessage());
                    }

                    // 5. CGI Sensor Test (official app's MD5-signed variant)
                    try (Socket s = (wifiNet != null ? wifiNet.getSocketFactory().createSocket() : new Socket())) {
                        s.connect(new InetSocketAddress(camIp, 80), 2000);
                        s.setSoTimeout(2000);
                        OutputStream os = s.getOutputStream();
                        String signedPath = DashcamModel.buildSignedSensorPath(1, "");
                        os.write(("GET " + signedPath + " HTTP/1.1\r\nHost: " + camIp + "\r\nConnection: close\r\n\r\n").getBytes());
                        os.flush();
                        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
                        String resp = br.readLine();
                        AppLogger.i(this, "5. CGI /setwifistream (imzalı) Yanıtı: " + (resp != null ? resp : "BOŞ DÖNDÜ"));
                    } catch (Throwable t) {
                        AppLogger.w(this, "5. CGI /setwifistream (imzalı) Hatası: " + t.getMessage());
                    }
                }

                // 6. Common Port Scan
                int[] ports = new int[]{554, 8554, 80, 8080, 7070, 1935, 5544};
                StringBuilder openPorts = new StringBuilder();
                for (int p : ports) {
                    try (Socket s = (wifiNet != null ? wifiNet.getSocketFactory().createSocket() : new Socket())) {
                        s.connect(new InetSocketAddress(camIp, p), 600);
                        openPorts.append(p).append(" ");
                    } catch (Throwable ignored) {}
                }
                AppLogger.i(this, "6. " + camIp + " Açık Portlar: " + (openPorts.length() > 0 ? openPorts.toString() : "HİÇBİRİ BULUNAMADI"));

                // Flush logs to server
                AppLogger.flushCache(this);
                AppLogger.i(this, "=== TEŞHİS BİTTİ (Loglar Merkeze İletildi) ===");
            } catch (Throwable t) {
                AppLogger.e(this, "Teşhis sırasında genel hata", t);
            } finally {
                runOnUiThread(() -> btnRunDiag.setEnabled(true));
            }
        }).start();
    }

    private void runStreamUrlScanner() {
        btnScanAlternatives.setEnabled(false);
        AppLogger.i(this, "=== ALTERNATİF RTSP TARAMASI BAŞLATILDI ===");

        new Thread(() -> {
            Network wifiNet = DashcamNetworkRouter.getWifiNetwork(this);
            String foundWorking = null;

            for (String candidate : StreamConfig.buildCandidates(this)) {
                AppLogger.i(this, "Deneniyor: " + candidate);
                try {
                    java.net.URI uri = new java.net.URI(candidate);
                    String host = uri.getHost();
                    int port = uri.getPort() != -1 ? uri.getPort() : 554;

                    try (Socket s = (wifiNet != null ? wifiNet.getSocketFactory().createSocket() : new Socket())) {
                        s.connect(new InetSocketAddress(host, port), 1500);
                        // Port reached! Send RTSP OPTIONS
                        s.setSoTimeout(1500);
                        OutputStream os = s.getOutputStream();
                        String opt = "OPTIONS " + candidate + " RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: LibVLC/3.6.0\r\n\r\n";
                        os.write(opt.getBytes());
                        os.flush();

                        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
                        String resp = br.readLine();
                        AppLogger.i(this, "-> Yanıt: " + (resp != null ? resp : "Bağlandı ama yanıt yok"));
                        if (resp != null && (resp.contains("RTSP/1.0 200") || resp.contains("RTSP/1.0 401") || resp.contains("RTSP/1.0 404"))) {
                            foundWorking = candidate;
                            AppLogger.i(this, "-> BAŞARILI ADRES: " + candidate);
                            break;
                        }
                    }
                } catch (Throwable t) {
                    AppLogger.w(this, "-> Başarısız: " + t.getMessage());
                }
            }

            final String workingUrl = foundWorking;
            runOnUiThread(() -> {
                btnScanAlternatives.setEnabled(true);
                if (workingUrl != null) {
                    etRtspUrl.setText(workingUrl);
                    StreamConfig.setActiveRtspUrl(this, workingUrl);
                    Toast.makeText(this, "Çalışan URL bulundu ve kaydedildi: " + workingUrl, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Otomatik alternatif bulunamadı. Lütfen teşhis loglarını inceleyin.", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_OVERLAY_PERM);
            }
        }
    }

    private void startServiceExplicitly() {
        Intent serviceIntent = new Intent(this, Reverse70MaiService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    /**
     * SSID okunabilirliği Android 9+ üzerinde konum iznine baglidir; model tespiti
     * icin en iyi caba ile istenir (head unit tum izinleri otomatik verir).
     */
    private void ensureLocationPermissionForSsid() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && DashcamModel.readCurrentSsid(this).isEmpty()
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION_PERM);
        }
    }

    private void updateNetworkStatus() {
        Network wifiNet = DashcamNetworkRouter.getWifiNetwork(this);
        DashcamModel.ModelInfo model = DashcamModel.detect(this);
        String camIp = DashcamModel.resolveCameraIp(this, model.family);
        String status = "Kamera: " + model.displayName + " | " + model.family + " | IP: " + camIp;
        if (wifiNet != null) {
            tvWifiInfo.setText(status + " | Wi-Fi aktif");
        } else {
            tvWifiInfo.setText(status + " | Wi-Fi bağlı değil");
        }
    }

    private void checkForUpdate() {
        btnCheckUpdate.setEnabled(false);
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, padding);
        TextView status = new TextView(this);
        status.setText("Güncellemeler denetleniyor...");
        status.setTextColor(0xFFFFFFFF);
        android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        android.widget.LinearLayout.LayoutParams progressParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = padding;
        panel.addView(status);
        panel.addView(progress, progressParams);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("OTA Güncelleme")
                .setView(panel)
                .setCancelable(false)
                .create();
        dialog.show();

        new Thread(() -> {
            String message;
            boolean openDownloadsFolder = false;
            try {
                String currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                OtaUpdater.Update update = OtaUpdater.check(currentVersion);
                if (update == null) {
                    message = "Uygulamanız güncel (v" + currentVersion + ")";
                } else {
                    runOnUiThread(() -> status.setText("İndiriliyor: v" + update.versionName));
                    java.io.File apk = OtaUpdater.download(this, update, percent -> {
                        runOnUiThread(() -> {
                            if (percent < 0) {
                                progress.setIndeterminate(true);
                            } else {
                                progress.setIndeterminate(false);
                                progress.setMax(100);
                                progress.setProgress(percent);
                                status.setText("İndiriliyor: %" + percent);
                            }
                        });
                    });

                    if (apk != null && apk.exists()) {
                        message = "Reverse70Mai v" + update.versionName + " indirildi. İndirilenler klasörü açılıyor, listeden dokunarak güncelleyebilirsiniz.";
                        openDownloadsFolder = true;
                    } else {
                        message = "İndirme başarısız oldu. Lütfen internet bağlantınızı kontrol edin.";
                    }
                }
            } catch (Exception e) {
                message = "Güncelleme denetlenemedi: " + e.getMessage();
            }

            final String finalMsg = message;
            final boolean shouldOpenDownloads = openDownloadsFolder;
            runOnUiThread(() -> {
                btnCheckUpdate.setEnabled(true);
                dialog.dismiss();
                new android.app.AlertDialog.Builder(this)
                        .setTitle("OTA Güncelleme")
                        .setMessage(finalMsg)
                        .setPositiveButton("Tamam", (d, w) -> {
                            if (shouldOpenDownloads) {
                                OtaUpdater.openDownloads(this);
                            }
                        })
                        .show();
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNetworkStatus();
    }
}
