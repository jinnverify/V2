package com.voxlink.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import androidx.core.app.NotificationCompat;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.voxlink.ui.RoomActivity;

public class VoiceService extends Service {

    private static final String TAG              = "VoxLink.Service";
    public  static final String ACTION_START     = "com.voxlink.START";
    public  static final String ACTION_STOP      = "com.voxlink.STOP";
    public  static final String ACTION_MUTE      = "com.voxlink.MUTE";
    public  static final String CHANNEL_ID       = "voxlink_voice";
    public  static final int    NOTIFICATION_ID  = 101;

    public static final String EXTRA_ROOM_ID     = "room_id";
    public static final String EXTRA_USER_NAME   = "user_name";
    public static final String EXTRA_SERVER_HOST = "server_host";
    public static final String EXTRA_SERVER_PORT = "server_port";

    private AudioEngine         audioEngine;
    private PowerManager.WakeLock wakeLock;
    private boolean             isMuted     = false;
    private String              currentRoom = "";

    private final IBinder binder = new VoiceBinder();

    public class VoiceBinder extends Binder {
        public VoiceService getService() { return VoiceService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (action == null) action = ACTION_START;

        switch (action) {
            case ACTION_START:
                startVoice(
                    intent.getStringExtra(EXTRA_ROOM_ID),
                    intent.getStringExtra(EXTRA_USER_NAME),
                    intent.getStringExtra(EXTRA_SERVER_HOST),
                    intent.getIntExtra(EXTRA_SERVER_PORT, 45000)
                );
                break;
            case ACTION_STOP:
                stopVoice();
                break;
            case ACTION_MUTE:
                toggleMute();
                break;
        }
        return START_STICKY;
    }

    private void startVoice(String roomId, String userName, String serverHost, int serverPort) {
        if (roomId == null || serverHost == null) { stopSelf(); return; }
        currentRoom = roomId;

        // FIX B3: startForeground first, THEN acquire wakeLock
        // so if startForeground fails we don't leak the lock
        startForeground(NOTIFICATION_ID, buildNotification(roomId, false));

        // Wake lock — keep CPU alive while user games
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoxLink::Voice");
        wakeLock.acquire(4 * 60 * 60 * 1000L);

        String userId = userName + "_" + (System.currentTimeMillis() % 9999);
        audioEngine   = new AudioEngine(serverHost, serverPort, userId, roomId);

        if (!audioEngine.init()) {
            Log.e(TAG, "Audio init failed");
            stopForeground(true);
            stopSelf();
            return;
        }
        audioEngine.start();
        Log.d(TAG, "Voice started for room " + roomId);
    }

    private void stopVoice() {
        if (audioEngine != null) { audioEngine.stop(); audioEngine = null; }
        if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); wakeLock = null; }
        stopForeground(true);
        stopSelf();
    }

    private void toggleMute() {
        isMuted = !isMuted;
        if (audioEngine != null) audioEngine.setMuted(isMuted);
        // Refresh notification
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(currentRoom, isMuted));
    }

    public void setMuted(boolean muted) {
        isMuted = muted;
        if (audioEngine != null) audioEngine.setMuted(muted);
    }

    public boolean isMuted() { return isMuted; }

    private Notification buildNotification(String roomId, boolean muted) {
        // Tap → open RoomActivity
        Intent ri = new Intent(this, RoomActivity.class);
        ri.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, ri, piFlags);

        // Mute action
        Intent muteI = new Intent(this, VoiceService.class).setAction(ACTION_MUTE);
        PendingIntent mutePi = PendingIntent.getService(this, 1, muteI, piFlags);

        // Leave action
        Intent stopI = new Intent(this, VoiceService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stopI, piFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle("VoxLink · " + roomId)
            .setContentText(muted ? "Muted" : "Voice active")
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(new NotificationCompat.Action.Builder(
                android.R.drawable.presence_audio_busy,
                muted ? "Unmute" : "Mute",
                mutePi).build())
            .addAction(new NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Leave",
                stopPi).build());

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Voice Call", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Active voice room");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (audioEngine != null) audioEngine.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
