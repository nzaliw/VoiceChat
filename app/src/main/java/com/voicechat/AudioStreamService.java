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

/**
 * Service de streaming audio UDP en temps réel.
 *
 * Architecture :
 *  - Capture : AudioRecord → paquets UDP → pair distant
 *  - Lecture  : Socket UDP local → AudioTrack (haut-parleur)
 *
 * Paramètres audio :
 *  - Fréquence : 16 000 Hz (bonne qualité vocale, faible bande passante)
 *  - Canaux    : Mono
 *  - Format    : PCM 16 bits
 *  - Taille paquet : ~160 échantillons = 10 ms de latence par paquet
 */
public class AudioStreamService extends Service {

    private static final String TAG = "AudioStreamService";
    private static final String NOTIF_CHANNEL = "voicechat_call";
    private static final int NOTIF_ID = 1001;

    // Paramètres audio
    public static final int SAMPLE_RATE    = 16000;
    public static final int CHANNEL_CONFIG_IN  = AudioFormat.CHANNEL_IN_MONO;
    public static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    public static final int AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT;
    public static final int FRAME_SIZE     = 320; // 10ms @ 16kHz × 2 bytes × 1 channel

    // État
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private AudioTrack  audioTrack;
    private DatagramSocket sendSocket;
    private DatagramSocket recvSocket;
    private InetAddress remoteAddress;
    private int remotePort;
    private int localPort;

    private ExecutorService executor;

    // Binder
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
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    // -------------------------------------------------------------------------
    // API publique
    // -------------------------------------------------------------------------

    /**
     * Démarre le streaming bidirectionnel avec le pair distant.
     * @param peerAddress Adresse IP du pair
     * @param peerAudioPort Port UDP du pair (réception de notre audio)
     * @param ourPort Notre port d'écoute (réception de son audio)
     */
    public void startStreaming(InetAddress peerAddress, int peerAudioPort, int ourPort) {
        if (streaming.getAndSet(true)) return;

        remoteAddress = peerAddress;
        remotePort    = peerAudioPort;
        localPort     = ourPort;

        startForeground(NOTIF_ID, buildNotification("Appel en cours..."));

        try {
            sendSocket = new DatagramSocket();
            recvSocket = new DatagramSocket(localPort);
            recvSocket.setSoTimeout(5000);

            initAudioRecord();
            initAudioTrack();

            executor.submit(this::captureAndSendLoop);
            executor.submit(this::receiveAndPlayLoop);

            Log.d(TAG, "Streaming démarré → " + peerAddress.getHostAddress() + ":" + peerAudioPort);
        } catch (Exception e) {
            Log.e(TAG, "Erreur démarrage streaming", e);
            stopStreaming();
        }
    }

    public void stopStreaming() {
        if (!streaming.getAndSet(false)) return;
        Log.d(TAG, "Arrêt du streaming");

        // Fermer les sockets (interrompt les boucles bloquantes)
        closeQuietly(sendSocket);
        closeQuietly(recvSocket);

        // Libérer AudioRecord
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }

        // Libérer AudioTrack
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (Exception ignored) {}
            audioTrack.release();
            audioTrack = null;
        }

        stopForeground(true);
    }

    public boolean isStreaming() { return streaming.get(); }

    // -------------------------------------------------------------------------
    // Boucles de streaming
    // -------------------------------------------------------------------------

    private void captureAndSendLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buffer = new byte[FRAME_SIZE];

        try {
            audioRecord.startRecording();
            while (streaming.get()) {
                int read = audioRecord.read(buffer, 0, FRAME_SIZE);
                if (read <= 0) continue;

                // Appliquer un gain léger (amplifier de 1.5×)
                applyGain(buffer, read, 1.5f);

                DatagramPacket packet = new DatagramPacket(buffer, read, remoteAddress, remotePort);
                sendSocket.send(packet);
            }
        } catch (Exception e) {
            if (streaming.get()) Log.w(TAG, "Erreur envoi audio : " + e.getMessage());
        }
    }

    private void receiveAndPlayLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buffer = new byte[FRAME_SIZE * 4]; // Buffer de réception plus grand

        try {
            audioTrack.play();
            while (streaming.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    recvSocket.receive(packet);
                    audioTrack.write(packet.getData(), 0, packet.getLength());
                } catch (java.net.SocketTimeoutException e) {
                    // Timeout normal, continuer
                }
            }
        } catch (Exception e) {
            if (streaming.get()) Log.w(TAG, "Erreur réception audio : " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Initialisation audio
    // -------------------------------------------------------------------------

    private void initAudioRecord() {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufferSize, FRAME_SIZE * 4);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSize);
    }

    private void initAudioTrack() {
        int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufferSize, FRAME_SIZE * 8);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AUDIO_FORMAT)
                .setChannelMask(CHANNEL_CONFIG_OUT)
                .build();

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    /**
     * Applique un gain linéaire aux échantillons PCM 16 bits.
     */
    private void applyGain(byte[] buffer, int length, float gain) {
        for (int i = 0; i < length - 1; i += 2) {
            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
            float amplified = sample * gain;
            // Clamp pour éviter la saturation
            amplified = Math.max(-32768, Math.min(32767, amplified));
            short out = (short) amplified;
            buffer[i]     = (byte) (out & 0xFF);
            buffer[i + 1] = (byte) ((out >> 8) & 0xFF);
        }
    }

    private void closeQuietly(DatagramSocket socket) {
        if (socket != null && !socket.isClosed()) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Notification foreground
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIF_CHANNEL, "Appel vocal", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Notification d'appel vocal en cours");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);

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
        executor.shutdownNow();
        super.onDestroy();
    }
}
