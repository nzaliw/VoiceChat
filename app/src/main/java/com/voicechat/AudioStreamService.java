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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioStreamService extends Service {

    private static final String TAG = "AudioStreamService";
    private static final String NOTIF_CHANNEL = "voicechat_call";
    private static final int    NOTIF_ID      = 1001;

    public static final int SAMPLE_RATE = 8000;
    public static final int FRAME_BYTES = 320; // 20ms @ 8kHz mono PCM16

    private final AtomicBoolean streaming = new AtomicBoolean(false);

    // Ces objets sont recréés à chaque appel
    private AudioRecord    audioRecord;
    private AudioTrack     audioTrack;
    private DatagramSocket sendSocket;
    private DatagramSocket recvSocket;

    private InetAddress remoteAddress;
    private int         remotePort;
    private int         localPort;
    private boolean     muted = false;

    private ExecutorService executor;
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public AudioStreamService getService() { return AudioStreamService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // Executor créé une seule fois, réutilisé entre appels
        executor = Executors.newFixedThreadPool(2);
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    // -------------------------------------------------------------------------
    // API publique
    // -------------------------------------------------------------------------

    public void startStreaming(InetAddress peerAddr, int peerPort, int myPort) {
        // Toujours stopper proprement avant de redémarrer
        if (streaming.get()) {
            Log.w(TAG, "startStreaming appelé alors qu'un streaming tourne — arrêt forcé");
            forceStop();
        }

        remoteAddress = peerAddr;
        remotePort    = peerPort;
        localPort     = myPort;
        muted         = false;

        Log.d(TAG, "startStreaming → " + peerAddr.getHostAddress()
                + ":" + peerPort + "  écoute:" + myPort);

        startForeground(NOTIF_ID, buildNotification());

        try {
            // Recréer l'executor si nécessaire (au cas où il aurait été shut down)
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newFixedThreadPool(2);
            }

            // Ouvrir les sockets (nouveaux à chaque appel)
            sendSocket = new DatagramSocket();
            sendSocket.setBroadcast(false);

            recvSocket = new DatagramSocket(localPort);
            recvSocket.setSoTimeout(200);

            // Configurer l'audio
            configureAudioManager(true);

            // Initialiser AudioRecord
            int minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minIn * 2, FRAME_BYTES * 4));

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new Exception("AudioRecord non initialisé");
            }

            // Initialiser AudioTrack
            int minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minOut * 4, FRAME_BYTES * 8))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new Exception("AudioTrack non initialisé");
            }

            streaming.set(true);
            executor.submit(this::captureLoop);
            executor.submit(this::playbackLoop);

            Log.d(TAG, "✅ Streaming démarré !");

        } catch (Exception e) {
            Log.e(TAG, "❌ Erreur démarrage streaming : " + e.getMessage(), e);
            forceStop();
        }
    }

    public void stopStreaming() {
        if (!streaming.get()) return;
        Log.d(TAG, "stopStreaming()");
        forceStop();
    }

    public void setMuted(boolean m) { this.muted = m; }
    public boolean isStreaming()    { return streaming.get(); }

    // -------------------------------------------------------------------------
    // Reset complet de tous les objets audio/réseau
    // -------------------------------------------------------------------------

    private void forceStop() {
        streaming.set(false);

        // Fermer les sockets en premier (interrompt les boucles bloquantes)
        closeSocket(sendSocket); sendSocket = null;
        closeSocket(recvSocket); recvSocket = null;

        // Libérer AudioRecord
        if (audioRecord != null) {
            try { audioRecord.stop();    } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }

        // Libérer AudioTrack
        if (audioTrack != null) {
            try { audioTrack.stop();    } catch (Exception ignored) {}
            try { audioTrack.release(); } catch (Exception ignored) {}
            audioTrack = null;
        }

        configureAudioManager(false);
        stopForeground(true);
        Log.d(TAG, "forceStop() terminé — service prêt pour un nouvel appel");
    }

    // -------------------------------------------------------------------------
    // Boucle capture → envoi UDP
    // -------------------------------------------------------------------------

    private void captureLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[FRAME_BYTES];
        Log.d(TAG, "captureLoop démarrée");
        try {
            audioRecord.startRecording();
            while (streaming.get()) {
                int read = audioRecord.read(buf, 0, buf.length);
                if (read <= 0 || muted) continue;
                if (sendSocket == null || sendSocket.isClosed()) break;
                try {
                    sendSocket.send(new DatagramPacket(buf, read, remoteAddress, remotePort));
                } catch (Exception e) {
                    if (streaming.get()) Log.w(TAG, "Envoi UDP : " + e.getMessage());
                }
            }
        } catch (Exception e) {
            if (streaming.get()) Log.e(TAG, "captureLoop erreur", e);
        }
        Log.d(TAG, "captureLoop terminée");
    }

    // -------------------------------------------------------------------------
    // Boucle réception UDP → lecture
    // -------------------------------------------------------------------------

    private void playbackLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[FRAME_BYTES * 4];
        Log.d(TAG, "playbackLoop démarrée");
        try {
            audioTrack.play();
            while (streaming.get()) {
                if (recvSocket == null || recvSocket.isClosed()) break;
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    recvSocket.receive(pkt);
                    if (pkt.getLength() > 0) {
                        audioTrack.write(pkt.getData(), pkt.getOffset(), pkt.getLength());
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (streaming.get()) Log.w(TAG, "Réception UDP : " + e.getMessage());
                }
            }
        } catch (Exception e) {
            if (streaming.get()) Log.e(TAG, "playbackLoop erreur", e);
        }
        Log.d(TAG, "playbackLoop terminée");
    }

    // -------------------------------------------------------------------------
    // Audio manager
    // -------------------------------------------------------------------------

    private void configureAudioManager(boolean forCall) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (forCall) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(true);
            } else {
                am.setMode(AudioManager.MODE_NORMAL);
                am.setSpeakerphoneOn(false);
            }
        } catch (Exception e) {
            Log.w(TAG, "AudioManager : " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    private void closeSocket(DatagramSocket s) {
        if (s != null && !s.isClosed()) try { s.close(); } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                NOTIF_CHANNEL, "Appel vocal", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("VoiceChat — Appel en cours")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onDestroy() {
        forceStop();
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(1, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
