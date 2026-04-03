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
        implements CallManager.CallEventListener {

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
    private boolean muted        = false;
    private boolean audioStarted = false;
    private boolean callReady    = false;
    private boolean finishing    = false; // évite les doubles appels à finish()

    private DiscoveryService   discoveryService;
    private AudioStreamService audioStreamService;
    private boolean discoveryBound = false;
    private boolean audioBound     = false;

    private TextView       tvPeerName, tvCallStatus, tvTimer;
    private ImageButton    btnMute;
    private MaterialButton btnHangup;

    private final Handler mainHandler    = new Handler(Looper.getMainLooper());
    private int           elapsedSeconds = 0;

    private static final int RETRY_MS  = 1500;
    private static final int MAX_RETRY = 8;
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
                + " myPort=" + myAudioPort + " peerPort=" + peerAudioPort + " ip=" + peerIp);

        initViews();
        updateStatus(isCaller ? "Appel de " + peerName + "..." : "Connexion...");

        // S'enregistrer comme listener actif dans CallManager
        CallManager.get().setListener(this);

        // Lier les services
        bindService(new Intent(this, DiscoveryService.class), discoveryConn, Context.BIND_AUTO_CREATE);
        Intent ai = new Intent(this, AudioStreamService.class);
        startService(ai);
        bindService(ai, audioConn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        mainHandler.removeCallbacksAndMessages(null);

        // Stopper l'audio
        if (audioBound && audioStreamService != null && audioStreamService.isStreaming()) {
            audioStreamService.stopStreaming();
        }

        // Rendre le listener à MainActivity
        CallManager.get().setListener(null);

        // Délier les services
        if (discoveryBound) {
            try { unbindService(discoveryConn); } catch (Exception ignored) {}
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
        if (tvTimer    != null) tvTimer.setVisibility(View.INVISIBLE);
        if (btnMute    != null) btnMute.setOnClickListener(v -> toggleMute());
        if (btnHangup  != null) btnHangup.setOnClickListener(v -> hangup());
    }

    private void updateStatus(String s) {
        runOnUiThread(() -> { if (tvCallStatus != null) tvCallStatus.setText(s); });
    }

    // -------------------------------------------------------------------------
    // Logique d'appel
    // -------------------------------------------------------------------------

    private void checkAndStart() {
        mainHandler.post(() -> {
            if (audioStarted || !callReady || !audioBound || audioStreamService == null) return;
            audioStarted = true;
            CallManager.get().setInCall();
            Log.d(TAG, "✅ Démarrage audio");
            updateStatus("En communication");
            if (tvTimer != null) tvTimer.setVisibility(View.VISIBLE);
            mainHandler.postDelayed(timerRunnable, 1000);
            try {
                audioStreamService.startStreaming(
                        InetAddress.getByName(peerIp), peerAudioPort, myAudioPort);
            } catch (Exception e) {
                Log.e(TAG, "Erreur audio", e);
                updateStatus("Erreur réseau");
            }
        });
    }

    private void markReady() {
        callReady = true;
        checkAndStart();
    }

    /** Raccrocher = action volontaire de l'utilisateur */
    private void hangup() {
        if (finishing) return;
        finishing = true;
        Log.d(TAG, "hangup()");
        mainHandler.removeCallbacks(timerRunnable);
        mainHandler.removeCallbacks(retryRunnable);

        if (audioBound && audioStreamService != null) audioStreamService.stopStreaming();
        if (discoveryBound && discoveryService != null && peerId != null) {
            discoveryService.sendCallEnd(peerId);
        }
        CallManager.get().reset();
        finish();
    }

    /**
     * L'autre a raccroché — fermer proprement ET immédiatement.
     * Appelé depuis onCallEnded().
     */
    private void closeByRemote(String reason) {
        if (finishing) return;
        finishing = true;
        Log.d(TAG, "closeByRemote : " + reason);
        mainHandler.removeCallbacks(timerRunnable);
        mainHandler.removeCallbacks(retryRunnable);

        if (audioBound && audioStreamService != null) audioStreamService.stopStreaming();
        CallManager.get().reset();

        // Fermer l'activité immédiatement sans attendre l'utilisateur
        runOnUiThread(this::finish);
    }

    private void toggleMute() {
        muted = !muted;
        if (audioBound && audioStreamService != null) audioStreamService.setMuted(muted);
        if (btnMute != null) btnMute.setImageResource(muted
                ? android.R.drawable.ic_lock_silent_mode
                : android.R.drawable.ic_lock_silent_mode_off);
    }

    // -------------------------------------------------------------------------
    // Retransmission CALL_ACCEPT (côté CALLEE)
    // -------------------------------------------------------------------------

    private final Runnable retryRunnable = new Runnable() {
        @Override public void run() {
            if (audioStarted || retryCount >= MAX_RETRY) return;
            retryCount++;
            Log.d(TAG, "Retry CALL_ACCEPT #" + retryCount);
            if (discoveryBound && discoveryService != null && peerId != null) {
                discoveryService.sendCallAccept(peerId);
            }
            mainHandler.postDelayed(this, RETRY_MS);
        }
    };

    // -------------------------------------------------------------------------
    // CallManager.CallEventListener
    // -------------------------------------------------------------------------

    @Override
    public void onCallAccepted(Peer by) {
        if (isCaller && peerId != null && peerId.equals(by.getId())) {
            Log.d(TAG, "✅ CALL_ACCEPT reçu");
            markReady();
        }
    }

    @Override
    public void onCallRejected(Peer by) {
        if (peerId != null && peerId.equals(by.getId())) {
            closeByRemote(by.getDisplayName() + " a refusé.");
        }
    }

    @Override
    public void onCallEnded(Peer by) {
        if (peerId != null && peerId.equals(by.getId())) {
            Log.d(TAG, "CALL_END reçu de " + by.getDisplayName());
            closeByRemote("Appel terminé par " + by.getDisplayName());
        }
    }

    @Override public void onIncomingCall(Peer from) { /* impossible ici */ }

    // -------------------------------------------------------------------------
    // ServiceConnections
    // -------------------------------------------------------------------------

    private final ServiceConnection discoveryConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            discoveryService = ((DiscoveryService.LocalBinder) b).getService();
            discoveryBound   = true;
            Log.d(TAG, "DiscoveryService lié");

            if (!isCaller) {
                // CALLEE : envoyer CALL_ACCEPT + retransmissions
                discoveryService.sendCallAccept(peerId);
                mainHandler.postDelayed(retryRunnable, RETRY_MS);
                markReady();
            }
            checkAndStart();
        }
        @Override public void onServiceDisconnected(ComponentName n) { discoveryBound = false; }
    };

    private final ServiceConnection audioConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            audioStreamService = ((AudioStreamService.LocalBinder) b).getService();
            audioBound         = true;
            Log.d(TAG, "AudioStreamService lié");
            checkAndStart();
        }
        @Override public void onServiceDisconnected(ComponentName n) { audioBound = false; }
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
