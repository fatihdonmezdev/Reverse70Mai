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

    private boolean currentIsRear = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatusText = findViewById(R.id.tvStatusText);
        tvWifiInfo = findViewById(R.id.tvWifiInfo);
        btnTestOverlay = findViewById(R.id.btnTestOverlay);
        btnSwitchFront = findViewById(R.id.btnSwitchFront);

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

        updateNetworkStatus();
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
