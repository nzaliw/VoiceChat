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
import java.util.List;

public class CallActivity extends AppCompatActivity
        implements DiscoveryService.DiscoveryListener {

    private static final String TAG = "CallActivity";

    public static final String EXTRA_PEER_NAME = "peer_name";
    public static final String EXTRA_PEER_IP   = "peer_ip";
    public static final String EXTRA_PEER_ID   = "peer_id";
    public static final String EXTRA_IS_CALLER = "is_caller";

    public static final int CALLER_AUDIO_PORT = 49876;
    public static final int CALLEE_AUDIO_PORT = 49877;

    private String  peerName, peerIp, peerId;
    private boolean isCaller;
    private int     myAudioPort, peerAudioPort;
    private boolean muted = false;

    // Services
    private DiscoveryService   discoveryService;
    private AudioStreamService audioStreamService;
    private boolean discoveryBound = false;
    private boolean audioBound     = false;

    // État : on attend que les DEUX services soient liés ET que l'appel soit accepté
    private boolean audioStarted  = false;
    private boolean callReady     = false; // signal "go" reçu

    // UI
    private TextView       tvPeerName, tvCallStatus, tvTimer;
    private ImageButton    btnMute;
    private MaterialButton btnHangup;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int elapsedSeconds = 0;

    // Retransmission du CALL_ACCEPT côté appelant (au cas où le premier paquet est perdu)
    private static final int RETRY_INTERVAL_MS = 1500;
    private static final int MAX_RETRIES       = 6;
    private int retryCount = 0;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        peerName = getIntent().getStringExtra(EXTRA_PEER_NAME);
        peerIp   = getIntent().getStringExtra(EXTRA_PEER_IP);
        peerId   = getIntent().getStringExtra(EXTRA_PEER_ID);
        isCaller = getIntent().getBooleanExtra(EXTRA_IS_CALLER, true);

        myAudioPort   = isCaller ? CALLER_AUDIO_PORT : CALLEE_AUDIO_PORT;
        peerAudioPort = isCaller ? CALLEE_AUDIO_PORT : CALLER_AUDIO_PORT;

        Log.d(TAG, "onCreate isCaller=" + isCaller
                + " myPort=" + myAudioPort + " peerPort=" + peerAudioPort
                + " peerIp=" + peerIp);

        initViews();
        updateStatus(isCaller ? "Appel de " + peerName + "..." : "Connexion...");

        // Lier les deux services
        Intent di = new Intent(this, DiscoveryService.class);
        bindService(di, discoveryConn, Context.BIND_AUTO_CREATE);

        Intent ai = new Intent(this, AudioStreamService.class);
        startService(ai);
        bindService(ai, audioConn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        stopCall();
        if (discoveryBound) {
            try { discoveryService.setListener(null); unbindService(discoveryConn); }
            catch (Exception ignored) {}
        }
        if (audioBound) {
            try { unbindService(audioConn); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() { hangup(); }

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
        if (tvTimer    != null) tvTimer.setVisibility(View.INVISIBLE);
        if (btnMute    != null) btnMute.setOnClickListener(v -> toggleMute());
        if (btnHangup  != null) btnHangup.setOnClickListener(v -> hangup());
    }

    private void updateStatus(String s) {
        runOnUiThread(() -> { if (tvCallStatus != null) tvCallStatus.setText(s); });
    }

    // -------------------------------------------------------------------------
    // Logique centrale : démarrer l'audio quand TOUT est prêt
    // -------------------------------------------------------------------------

    /**
     * Appelé depuis n'importe quel thread quand :
     * - Pour le CALLEE : dès que les deux services sont liés
     * - Pour le CALLER : dès que CALL_ACCEPT est reçu ET les deux services sont liés
     */
    private void checkAndStartAudio() {
        mainHandler.post(() -> {
            if (audioStarted) return;
            if (!callReady)   return;
            if (!audioBound || audioStreamService == null) return;
            if (!discoveryBound || discoveryService == null) return;

            audioStarted = true;
            Log.d(TAG, "✅ Démarrage audio !");
            updateStatus("En communication");
            if (tvTimer != null) tvTimer.setVisibility(View.VISIBLE);
            mainHandler.postDelayed(timerRunnable, 1000);

            try {
                InetAddress addr = InetAddress.getByName(peerIp);
                audioStreamService.startStreaming(addr, peerAudioPort, myAudioPort);
            } catch (Exception e) {
                Log.e(TAG, "Erreur démarrage audio", e);
                updateStatus("Erreur réseau : " + e.getMessage());
            }
        });
    }

    /** Marque l'appel comme prêt et tente de démarrer */
    private void signalCallReady() {
        callReady = true;
        checkAndStartAudio();
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
    // Retransmission périodique du CALL_ACCEPT (côté CALLEE)
    // Envoie plusieurs fois pour compenser les pertes UDP
    // -------------------------------------------------------------------------

    private final Runnable retryAcceptRunnable = new Runnable() {
        @Override public void run() {
            if (audioStarted || retryCount >= MAX_RETRIES) return;
            retryCount++;
            Log.d(TAG, "Retransmission CALL_ACCEPT #" + retryCount);
            if (discoveryBound && discoveryService != null && peerId != null) {
                discoveryService.sendCallAccept(peerId);
            }
            mainHandler.postDelayed(this, RETRY_INTERVAL_MS);
        }
    };

    // -------------------------------------------------------------------------
    // DiscoveryService.DiscoveryListener
    // -------------------------------------------------------------------------

    @Override public void onPeersChanged(List<Peer> peers) {}
    @Override public void onCallRequest(Peer from) {}

    @Override
    public void onCallAccepted(Peer by) {
        // L'APPELANT reçoit ici la confirmation que l'autre a décroché
        if (isCaller && peerId != null && peerId.equals(by.getId())) {
            Log.d(TAG, "CALL_ACCEPT reçu de " + by.getDisplayName());
            signalCallReady();
        }
    }

    @Override
    public void onCallRejected(Peer by) {
        if (peerId != null && peerId.equals(by.getId())) {
            stopCall();
            updateStatus(peerName + " a refusé.");
            runOnUiThread(() -> {
                if (btnHangup != null) { btnHangup.setText("Fermer"); btnHangup.setOnClickListener(v -> finish()); }
            });
        }
    }

    @Override
    public void onCallEnded(Peer by) {
        if (peerId != null && peerId.equals(by.getId())) {
            stopCall();
            updateStatus("Appel terminé par " + peerName);
            runOnUiThread(() -> {
                if (btnHangup != null) { btnHangup.setText("Fermer"); btnHangup.setOnClickListener(v -> finish()); }
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

            if (!isCaller) {
                // CALLEE : envoyer CALL_ACCEPT maintenant que le service est prêt
                // + retransmissions périodiques
                discoveryService.sendCallAccept(peerId);
                mainHandler.postDelayed(retryAcceptRunnable, RETRY_INTERVAL_MS);
                // Marquer l'appel comme prêt du côté callee
                signalCallReady();
            }
            // Pour le caller : on attend onCallAccepted()
            checkAndStartAudio();
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
            checkAndStartAudio();
        }
        @Override
        public void onServiceDisconnected(ComponentName n) { audioBound = false; }
    };

    // -------------------------------------------------------------------------
    // Timer
    // -------------------------------------------------------------------------

    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            elapsedSeconds++;
            if (tvTimer != null)
                tvTimer.setText(String.format("%02d:%02d",
                        elapsedSeconds / 60, elapsedSeconds % 60));
            mainHandler.postDelayed(this, 1000);
        }
    };
}
