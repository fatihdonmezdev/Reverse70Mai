package com.fatih.reverse70mai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;

/**
 * Reverse gear detector and 70mai camera stream overlay service.
 * 
 * Listens for MG4 reverse gear broadcasts:
 * - "com.saicmotor.360camera.show"  -> Switches 70mai to Rear Sensor (1), binds network to Wi-Fi, starts VLC stream on right 65% of screen.
 * - "com.saicmotor.360camera.close" -> Switches 70mai back to Front Sensor (0), hides overlay, stops VLC.
 */
public class Reverse70MaiService extends Service {
    private static final String TAG = "Reverse70MaiService";
    private static final String CHANNEL_ID = "reverse_70mai_channel";
    private static final int NOTIF_ID = 701;

    public static final String ACTION_TEST_OVERLAY = "com.fatih.reverse70mai.ACTION_TEST_OVERLAY";

    private WindowManager windowManager;
    private View overlayView;
    private SurfaceView surfaceOverlay;
    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private boolean isOverlayShowing = false;

    private BroadcastReceiver reverseReceiver;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        // Keep Wi-Fi connection alive actively (bypasses captive portal without root/adb)
        DashcamNetworkRouter.startWifiKeepAlive(this);

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Reverse70Mai")
                .setContentText("MG4 Geri Vites Kamerası Dinleniyor")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, notification);

        registerReverseReceiver();
        Log.i(TAG, "Reverse70MaiService created and listening for reverse camera broadcasts.");
    }

    private void registerReverseReceiver() {
        reverseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String action = intent.getAction();
                Log.d(TAG, "Received broadcast intent: " + action);

                if ("com.saicmotor.360camera.show".equals(action)) {
                    Log.i(TAG, "MG4 Reverse Gear Activated -> Showing 70mai Rear Camera");
                    showReverseOverlay();
                } else if ("com.saicmotor.360camera.close".equals(action)) {
                    Log.i(TAG, "MG4 Reverse Gear Deactivated -> Hiding Overlay");
                    hideReverseOverlay();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.saicmotor.360camera.show");
        filter.addAction("com.saicmotor.360camera.close");
        registerReceiver(reverseReceiver, filter);
    }

    public synchronized void showReverseOverlay() {
        if (isOverlayShowing) {
            Log.d(TAG, "Overlay is already showing");
            return;
        }

        // 1. Switch 70mai hardware multiplexer to REAR sensor (sensor 1) in background
        new Thread(() -> {
            DashcamNetworkRouter.switchSensorHardware(this, 1);
        }).start();

        mainHandler.post(() -> {
            try {
                // Layout params: cover right 65% of screen (1250px on standard 1920x720 panel)
                // Leaving the left 35% (670px) fully visible for OEM SAIC park radar & distance sensors
                int overlayWidth = 1250;
                int overlayHeight = 720;

                int windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    windowType = WindowManager.LayoutParams.TYPE_PHONE;
                }

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        overlayWidth,
                        overlayHeight,
                        windowType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT
                );

                params.gravity = Gravity.TOP | Gravity.END;
                params.x = 0;
                params.y = 0;

                LayoutInflater inflater = LayoutInflater.from(this);
                overlayView = inflater.inflate(R.layout.overlay_camera, null);
                surfaceOverlay = overlayView.findViewById(R.id.surfaceOverlay);

                try {
                    windowManager.addView(overlayView, params);
                } catch (Throwable wt) {
                    // Fallback to TYPE_SYSTEM_ALERT or TYPE_PHONE if overlay failed
                    AppLogger.w(this, "Standard overlay add failed (" + wt.getMessage() + "), retrying with fallback type");
                    params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
                    windowManager.addView(overlayView, params);
                }
                isOverlayShowing = true;

                // Start VLC streaming
                startVlcStream();
                AppLogger.i(this, "Overlay attached to WindowManager (" + overlayWidth + "x" + overlayHeight + ", type=" + params.type + ")");
            } catch (Throwable t) {
                AppLogger.e(this, "Failed to display reverse camera overlay: " + t.getMessage(), t);
            }
        });
    }

    private int currentCandidateIndex = 0;
    private long lastStreamAttemptTime = 0;

    private void startVlcStream() {
        stopVlcStream();

        new Thread(() -> {
            // CRITICAL: Bind process to Wi-Fi network so RTSP traffic doesn't get routed to T-Box
            DashcamNetworkRouter.bindProcessToWifi(this);

            mainHandler.post(() -> {
                try {
                    ArrayList<String> options = new ArrayList<>();
                    options.add("--rtsp-tcp");
                    options.add("--network-caching=150");
                    options.add("--no-audio");
                    options.add("--clock-jitter=0");
                    options.add("--drop-late-frames");
                    options.add("--skip-frames");
                    options.add("--avcodec-hurry-up");
                    options.add("--avcodec-hw=none");

                    libVLC = new LibVLC(this, options);
                    mediaPlayer = new MediaPlayer(libVLC);

                    if (surfaceOverlay != null) {
                        IVLCVout vout = mediaPlayer.getVLCVout();
                        vout.setVideoView(surfaceOverlay);
                        vout.setWindowSize(1250, 720);
                        vout.attachViews();
                    }

                    mediaPlayer.setAspectRatio("125:72");
                    mediaPlayer.setScale(0f);

                    mediaPlayer.setEventListener(event -> {
                        if (event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.EndReached) {
                            AppLogger.w(this, "LibVLC stream interrupted (event=" + event.type + ")");
                            if (isOverlayShowing) {
                                // If stream failed quickly, cycle to next candidate URL automatically (livestream/12 -> livestream/13 etc.)
                                long duration = System.currentTimeMillis() - lastStreamAttemptTime;
                                if (duration < 3000) {
                                    currentCandidateIndex = (currentCandidateIndex + 1) % StreamConfig.CANDIDATE_URLS.size();
                                    String nextCandidate = StreamConfig.CANDIDATE_URLS.get(currentCandidateIndex);
                                    AppLogger.i(this, "Otomatik alternatif URL deneniyor: " + nextCandidate);
                                    StreamConfig.setActiveRtspUrl(this, nextCandidate);
                                }
                                mainHandler.postDelayed(() -> {
                                    if (isOverlayShowing) startVlcStream();
                                }, 800);
                            }
                        } else if (event.type == MediaPlayer.Event.Playing) {
                            AppLogger.i(this, "70mai RTSP Rear Stream playing successfully!");
                        }
                    });

                    String targetUrl = StreamConfig.getActiveRtspUrl(this);
                    lastStreamAttemptTime = System.currentTimeMillis();
                    AppLogger.i(this, "Connecting to RTSP Stream: " + targetUrl);
                    Media media = new Media(libVLC, Uri.parse(targetUrl));
                    media.setHWDecoderEnabled(false, false);
                    media.addOption(":rtsp-tcp");
                    media.addOption(":network-caching=150");
                    media.addOption(":clock-jitter=0");
                    media.addOption(":drop-late-frames");
                    media.addOption(":skip-frames");
                    media.addOption(":avcodec-hurry-up");
                    media.addOption(":avcodec-hw=none");

                    mediaPlayer.setMedia(media);
                    media.release();
                    mediaPlayer.play();
                } catch (Throwable t) {
                    Log.e(TAG, "Error starting VLC player: " + t.getMessage(), t);
                }
            });
        }).start();
    }

    public synchronized void hideReverseOverlay() {
        if (!isOverlayShowing || overlayView == null) return;

        mainHandler.post(() -> {
            try {
                stopVlcStream();
                if (overlayView != null) {
                    windowManager.removeView(overlayView);
                    overlayView = null;
                    surfaceOverlay = null;
                }
                isOverlayShowing = false;
                Log.i(TAG, "Reverse overlay dismissed successfully.");
            } catch (Throwable t) {
                Log.e(TAG, "Error removing overlay view: " + t.getMessage(), t);
            }
        });

        // Switch sensor back to Front (sensor 0) & restore system network routing
        new Thread(() -> {
            DashcamNetworkRouter.switchSensorHardware(this, 0);
            DashcamNetworkRouter.unbindProcess(this);
        }).start();
    }

    private void stopVlcStream() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                if (mediaPlayer.getVLCVout() != null) {
                    mediaPlayer.getVLCVout().detachViews();
                }
                mediaPlayer.release();
            } catch (Throwable ignored) {}
            mediaPlayer = null;
        }
        if (libVLC != null) {
            try {
                libVLC.release();
            } catch (Throwable ignored) {}
            libVLC = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "onStartCommand action: " + action);
            if (ACTION_TEST_OVERLAY.equals(action)) {
                Log.i(TAG, "Triggered 5-second test overlay...");
                showReverseOverlay();
                mainHandler.postDelayed(this::hideReverseOverlay, 5000);
            } else if ("com.saicmotor.360camera.show".equals(action)) {
                showReverseOverlay();
            } else if ("com.saicmotor.360camera.close".equals(action)) {
                hideReverseOverlay();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (reverseReceiver != null) {
            try { unregisterReceiver(reverseReceiver); } catch (Throwable ignored) {}
            reverseReceiver = null;
        }
        hideReverseOverlay();
        DashcamNetworkRouter.stopWifiKeepAlive(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Reverse70Mai Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
