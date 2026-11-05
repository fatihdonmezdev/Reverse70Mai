package com.fatih.reverse70mai;

import android.app.Activity;
import android.content.Intent;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY_PERM = 1001;

    private TextView tvStatusText;
    private TextView tvWifiInfo;
    private Button btnTestOverlay;
    private Button btnSwitchFront;
    private Button btnCheckUpdate;

    private boolean currentIsRear = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatusText = findViewById(R.id.tvStatusText);
        tvWifiInfo = findViewById(R.id.tvWifiInfo);
        btnTestOverlay = findViewById(R.id.btnTestOverlay);
        btnSwitchFront = findViewById(R.id.btnSwitchFront);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);

        checkOverlayPermission();

        // Start background service
        startServiceExplicitly();

        btnTestOverlay.setOnClickListener(v -> {
            Intent testIntent = new Intent(this, Reverse70MaiService.class);
            testIntent.setAction(Reverse70MaiService.ACTION_TEST_OVERLAY);
            startService(testIntent);
            Toast.makeText(this, "5 saniyelik test yayını başlatıldı", Toast.LENGTH_SHORT).show();
        });

        btnSwitchFront.setOnClickListener(v -> {
            currentIsRear = !currentIsRear;
            int targetSensor = currentIsRear ? 1 : 0;
            new Thread(() -> {
                boolean ok = DashcamNetworkRouter.switchSensorHardware(this, targetSensor);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Sensör: " + (currentIsRear ? "ARKA" : "ÖN") + (ok ? " (Başarılı)" : " (Hata)"), Toast.LENGTH_SHORT).show();
                });
            }).start();
        });

        btnCheckUpdate.setOnClickListener(v -> checkForUpdate());

        updateNetworkStatus();
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

    private void updateNetworkStatus() {
        Network wifiNet = DashcamNetworkRouter.getWifiNetwork(this);
        if (wifiNet != null) {
            tvWifiInfo.setText("Ağ Durumu: Wi-Fi arayüzü algılandı (" + wifiNet + "). 70mai (192.168.0.1) için doğrudan yönlendirilecek.");
        } else {
            tvWifiInfo.setText("Ağ Durumu: Wi-Fi bağlı değil veya algılanmadı. Lütfen multimedyayı 70mai Wi-Fi ağına bağlayın.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNetworkStatus();
    }
}
