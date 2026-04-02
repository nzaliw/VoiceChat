package com.voicechat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public class CallActivity extends AppCompatActivity
        implements DiscoveryService.DiscoveryListener {

    private static final String TAG = "CallActivity";

    public static final String EXTRA_PEER_NAME       = "peer_name";
    public static final String EXTRA_PEER_IP         = "peer_ip";
    public static final String EXTRA_PEER_ID         = "peer_id";
    public static final String EXTRA_IS_CALLER       = "is_caller";

    // Appelant écoute sur 49876, Appelé écoute sur 49877
    public static final int CALLER_AUDIO_PORT = 49876;
    public static final int CALLEE_AUDIO_PORT = 49877;

    private String peerName;
    private String peerIp;
    private String peerId;
    private boolean isCaller;

    private int myAudioPort;
    private int peerAudioPort;

    // État de la liaison des services
    private DiscoveryService  discoveryService;
    private AudioStreamService audioStreamService;
    private boolean discoveryBound = false;
    private boolean audioBound     = false;

    // État de l'appel
    private boolean callAccepted  = false; // true quand les deux sont prêts
    private boolean shouldStart   = false; // true quand on doit démarrer dès que le service est prêt
    private boolean muted         = false;

    // UI
    private TextView tvPeerName, tvCallStatus, tvTimer;
    private ImageButton btnMute;
    private MaterialButton btnHangup;

    // Timer
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int elapsedSeconds = 0;
    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            elapsedSeconds++;
            if (tvTimer != null)
                tvTimer.setText(String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60));
            mainHandler.postDelayed(this, 1000);
        }
    };

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        peerName  = getIntent().getStringExtra(EXTRA_PEER_NAME);
        peerIp    = getIntent().getStringExtra(EXTRA_PEER_IP);
        peerId    = getIntent().getStringExtra(EXTRA_PEER_ID);
        isCaller  = getIntent().getBooleanExtra(EXTRA_IS_CALLER, true);

        // Ports : chacun écoute sur son port, envoie sur le port de l'autre
        myAudioPort   = isCaller ? CALLER_AUDIO_PORT : CALLEE_AUDIO_PORT;
        peerAudioPort = isCaller ? CALLEE_AUDIO_PORT : CALLER_AUDIO_PORT;

        Log.d(TAG, "onCreate isCaller=" + isCaller
                + " myPort=" + myAudioPort + " peerPort=" + peerAudioPort
                + " peerIp=" + peerIp);

        initViews();

        // Lier DiscoveryService
        Intent di = new Intent(this, DiscoveryService.class);
        bindService(di, discoveryConn, Context.BIND_AUTO_CREATE);

        // Lier et démarrer AudioStreamService
        Intent ai = new Intent(this, AudioStreamService.class);
        startService(ai);
        bindService(ai, audioConn, Context.BIND_AUTO_CREATE);

        updateStatus(isCaller ? "Appel de " + peerName + "..." : "Connexion en cours...");
    }

    @Override
    protected void onDestroy() {
        stopCall();
        mainHandler.removeCallbacks(timerRunnable);
        if (discoveryBound) {
            try { discoveryService.setListener(null); unbindService(discoveryConn); }
            catch (Exception ignored) {}
            discoveryBound = false;
        }
        if (audioBound) {
            try { unbindService(audioConn); } catch (Exception ignored) {}
            audioBound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { hangup(); }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private void initViews() {
        tvPeerName   = findViewById(R.id.tv_call_peer_name);
        tvCallStatus = findViewById(R.id.tv_call_status);
        tvTimer      = findViewById(R.id.tv_call_timer);
        btnMute      = findViewById(R.id.btn_mute);
        btnHangup    = findViewById(R.id.btn_hangup);

        if (tvPeerName != null) tvPeerName.setText(peerName != null ? peerName : "");
        if (tvTimer != null) tvTimer.setVisibility(View.INVISIBLE);
        if (btnMute != null) btnMute.setOnClickListener(v -> toggleMute());
        if (btnHangup != null) btnHangup.setOnClickListener(v -> hangup());
    }

    private void updateStatus(String s) {
        runOnUiThread(() -> { if (tvCallStatus != null) tvCallStatus.setText(s); });
    }

    // -------------------------------------------------------------------------
    // Gestion de l'appel
    // -------------------------------------------------------------------------

    /**
     * Appelé quand les deux services sont prêts ET que l'appel est accepté.
     * Démarre le streaming audio bidirectionnel.
     */
    private void tryStartAudio() {
        if (callAccepted && audioBound && audioStreamService != null
                && !audioStreamService.isStreaming()) {
            Log.d(TAG, "tryStartAudio → démarrage !");
            updateStatus("En communication");
            runOnUiThread(() -> { if (tvTimer != null) tvTimer.setVisibility(View.VISIBLE); });
            mainHandler.postDelayed(timerRunnable, 1000);

            try {
                InetAddress addr = InetAddress.getByName(peerIp);
                audioStreamService.startStreaming(addr, peerAudioPort, myAudioPort);
            } catch (UnknownHostException e) {
                Log.e(TAG, "IP invalide", e);
                updateStatus("Erreur réseau");
            }
        } else {
            Log.d(TAG, "tryStartAudio → pas encore prêt"
                    + " callAccepted=" + callAccepted
                    + " audioBound=" + audioBound
                    + " audioService=" + (audioStreamService != null));
        }
    }

    /**
     * Marque l'appel comme accepté et tente de démarrer l'audio.
     * Peut être appelé depuis n'importe quel thread.
     */
    private void acceptAndStart() {
        Log.d(TAG, "acceptAndStart");
        callAccepted = true;
        shouldStart  = true;
        runOnUiThread(this::tryStartAudio);
    }

    private void stopCall() {
        mainHandler.removeCallbacks(timerRunnable);
        if (audioBound && audioStreamService != null && audioStreamService.isStreaming()) {
            audioStreamService.stopStreaming();
        }
    }

    private void hangup() {
        if (discoveryBound && discoveryService != null && peerId != null) {
            discoveryService.sendCallEnd(peerId);
        }
        stopCall();
        finish();
    }

    private void toggleMute() {
        muted = !muted;
        if (audioBound && audioStreamService != null) audioStreamService.setMuted(muted);
        if (btnMute != null) btnMute.setImageResource(muted
                ? android.R.drawable.ic_lock_silent_mode
                : android.R.drawable.ic_lock_silent_mode_off);
    }

    // -------------------------------------------------------------------------
    // DiscoveryService.DiscoveryListener
    // -------------------------------------------------------------------------

    @Override public void onPeersChanged(List<Peer> peers) {}
    @Override public void onCallRequest(Peer from) {}

    @Override
    public void onCallAccepted(Peer by) {
        // L'appelant reçoit l'acceptation du callee
        if (isCaller && peerId != null && peerId.equals(by.getId())) {
            Log.d(TAG, "onCallAccepted par " + by.getDisplayName());
            acceptAndStart();
        }
    }

    @Override
    public void onCallRejected(Peer by) {
        if (peerId != null && peerId.equals(by.getId())) {
            stopCall();
            updateStatus(peerName + " a refusé l'appel.");
            runOnUiThread(() -> {
                if (btnHangup != null) {
                    btnHangup.setText("Fermer");
                    btnHangup.setOnClickListener(v -> finish());
                }
            });
        }
    }

    @Override
    public void onCallEnded(Peer by) {
        if (peerId != null && peerId.equals(by.getId())) {
            stopCall();
            updateStatus("Appel terminé par " + peerName);
            runOnUiThread(() -> {
                if (btnHangup != null) {
                    btnHangup.setText("Fermer");
                    btnHangup.setOnClickListener(v -> finish());
                }
            });
        }
    }

    // -------------------------------------------------------------------------
    // ServiceConnections
    // -------------------------------------------------------------------------

    private final ServiceConnection discoveryConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            discoveryService = ((DiscoveryService.LocalBinder) b).getService();
            discoveryService.setListener(CallActivity.this);
            discoveryBound = true;
            Log.d(TAG, "DiscoveryService lié");

            // Le CALLEE : dès qu'on a le service de signalisation, on accepte
            // (il a déjà envoyé CALL_ACCEPT depuis MainActivity, on démarre l'audio)
            if (!isCaller) {
                acceptAndStart();
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName n) { discoveryBound = false; }
    };

    private final ServiceConnection audioConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            audioStreamService = ((AudioStreamService.LocalBinder) b).getService();
            audioBound = true;
            Log.d(TAG, "AudioStreamService lié");

            // Si l'appel a déjà été accepté pendant qu'on attendait le service
            if (shouldStart) {
                runOnUiThread(() -> tryStartAudio());
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName n) { audioBound = false; }
    };
}
