package com.voicechat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioStreamService extends Service {

    private static final String TAG = "AudioStreamService";
    private static final String NOTIF_CHANNEL = "voicechat_call";
    private static final int NOTIF_ID = 1001;

    // Paramètres audio optimisés pour la voix
    public static final int SAMPLE_RATE = 8000;   // 8kHz = qualité téléphone, faible bande passante
    public static final int FRAME_SIZE  = 160;    // 20ms @ 8kHz = bon compromis latence/qualité

    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private AudioTrack  audioTrack;
    private DatagramSocket sendSocket;
    private DatagramSocket recvSocket;
    private InetAddress remoteAddress;
    private int remotePort;
    private int localPort;
    private boolean muted = false;

    private ExecutorService executor;
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public AudioStreamService getService() { return AudioStreamService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newFixedThreadPool(2);
        createNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    public void startStreaming(InetAddress peerAddress, int peerAudioPort, int ourPort) {
        if (streaming.getAndSet(true)) return;

        remoteAddress = peerAddress;
        remotePort    = peerAudioPort;
        localPort     = ourPort;

        Log.d(TAG, "Démarrage streaming → " + peerAddress.getHostAddress() + ":" + peerAudioPort
                + " écoute sur port " + ourPort);

        startForeground(NOTIF_ID, buildNotification("Appel en cours..."));

        // Forcer le haut-parleur
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(true);
        } catch (Exception e) {
            Log.w(TAG, "Impossible de configurer l'audio : " + e.getMessage());
        }

        try {
            // Ouvrir les sockets
            sendSocket = new DatagramSocket();
            sendSocket.setSoTimeout(0); // pas de timeout en envoi

            recvSocket = new DatagramSocket(localPort);
            recvSocket.setSoTimeout(100); // timeout court pour ne pas bloquer

            // Initialiser AudioRecord
            int minBufIn = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufIn = Math.max(minBufIn * 2, FRAME_SIZE * 4);
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufIn);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord non initialisé !");
                stopStreaming();
                return;
            }

            // Initialiser AudioTrack
            int minBufOut = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufOut = Math.max(minBufOut * 4, FRAME_SIZE * 8);

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufOut)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack non initialisé !");
                stopStreaming();
                return;
            }

            executor.submit(this::captureAndSendLoop);
            executor.submit(this::receiveAndPlayLoop);

            Log.d(TAG, "Streaming démarré avec succès !");

        } catch (Exception e) {
            Log.e(TAG, "Erreur démarrage streaming", e);
            stopStreaming();
        }
    }

    public void stopStreaming() {
        if (!streaming.getAndSet(false)) return;
        Log.d(TAG, "Arrêt du streaming");

        closeSocket(sendSocket);
        closeSocket(recvSocket);

        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (Exception ignored) {}
            try { audioTrack.release(); } catch (Exception ignored) {}
            audioTrack = null;
        }

        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.setMode(AudioManager.MODE_NORMAL);
            am.setSpeakerphoneOn(false);
        } catch (Exception ignored) {}

        stopForeground(true);
    }

    public void setMuted(boolean muted) { this.muted = muted; }
    public boolean isStreaming() { return streaming.get(); }

    // -------------------------------------------------------------------------
    // Boucle capture + envoi
    // -------------------------------------------------------------------------

    private void captureAndSendLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buffer = new byte[FRAME_SIZE * 2]; // *2 car PCM 16-bit = 2 bytes/sample

        try {
            audioRecord.startRecording();
            Log.d(TAG, "Capture audio démarrée");

            while (streaming.get()) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                if (muted) continue;

                try {
                    DatagramPacket pkt = new DatagramPacket(
                            buffer, read, remoteAddress, remotePort);
                    sendSocket.send(pkt);
                } catch (Exception e) {
                    if (streaming.get()) Log.w(TAG, "Erreur envoi UDP : " + e.getMessage());
                }
            }
        } catch (Exception e) {
            if (streaming.get()) Log.e(TAG, "Erreur capture audio", e);
        }
        Log.d(TAG, "Capture audio arrêtée");
    }

    // -------------------------------------------------------------------------
    // Boucle réception + lecture
    // -------------------------------------------------------------------------

    private void receiveAndPlayLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buffer = new byte[FRAME_SIZE * 4];

        try {
            audioTrack.play();
            Log.d(TAG, "Lecture audio démarrée");

            while (streaming.get()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
                    recvSocket.receive(pkt);
                    if (pkt.getLength() > 0) {
                        audioTrack.write(pkt.getData(), pkt.getOffset(), pkt.getLength());
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // normal, continuer
                } catch (Exception e) {
                    if (streaming.get()) Log.w(TAG, "Erreur réception UDP : " + e.getMessage());
                }
            }
        } catch (Exception e) {
            if (streaming.get()) Log.e(TAG, "Erreur lecture audio", e);
        }
        Log.d(TAG, "Lecture audio arrêtée");
    }

    private void closeSocket(DatagramSocket s) {
        if (s != null && !s.isClosed()) try { s.close(); } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIF_CHANNEL, "Appel vocal", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("VoiceChat")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
