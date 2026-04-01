package com.voicechat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Écran d'appel vocal (pendant la communication).
 *
 * États possibles :
 *  - CALLER / en attente : L'appelant attend l'acceptation
 *  - IN_CALL : Communication active
 */
public class CallActivity extends AppCompatActivity
        implements DiscoveryService.DiscoveryListener {

    public static final String EXTRA_PEER_NAME       = "peer_name";
    public static final String EXTRA_PEER_IP         = "peer_ip";
    public static final String EXTRA_PEER_AUDIO_PORT = "peer_audio_port";
    public static final String EXTRA_PEER_ID         = "peer_id";
    public static final String EXTRA_MY_AUDIO_PORT   = "my_audio_port";
    public static final String EXTRA_IS_CALLER       = "is_caller";

    private String peerName;
    private String peerIp;
    private int peerAudioPort;
    private String peerId;
    private int myAudioPort;
    private boolean isCaller;

    private boolean inCall = false;
    private boolean muted  = false;

    // Services
    private DiscoveryService discoveryService;
    private AudioStreamService audioStreamService;
    private boolean discoveryBound = false;
    private boolean audioBound = false;

    // UI
    private TextView tvPeerName;
    private TextView tvCallStatus;
    private TextView tvTimer;
    private ImageButton btnMute;
    private MaterialButton btnHangup;

    // Timer
    private Handler timerHandler;
    private int elapsedSeconds = 0;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        // Récupérer les extras
        peerName      = getIntent().getStringExtra(EXTRA_PEER_NAME);
        peerIp        = getIntent().getStringExtra(EXTRA_PEER_IP);
        peerAudioPort = getIntent().getIntExtra(EXTRA_PEER_AUDIO_PORT, 49876);
        peerId        = getIntent().getStringExtra(EXTRA_PEER_ID);
        myAudioPort   = getIntent().getIntExtra(EXTRA_MY_AUDIO_PORT, 49877);
        isCaller      = getIntent().getBooleanExtra(EXTRA_IS_CALLER, true);

        initViews();
        timerHandler = new Handler(Looper.getMainLooper());

        // Lier les services
        Intent discIntent = new Intent(this, DiscoveryService.class);
        bindService(discIntent, discoveryConnection, Context.BIND_AUTO_CREATE);

        Intent audioIntent = new Intent(this, AudioStreamService.class);
        bindService(audioIntent, audioConnection, Context.BIND_AUTO_CREATE);
        startService(audioIntent);

        if (!isCaller) {
            // Le callee démarre directement le streaming (l'appelant l'a déjà fait)
            updateStatus("Connexion en cours...");
        } else {
            updateStatus("Appel de " + peerName + "...");
        }
    }

    @Override
    protected void onDestroy() {
        stopCall();
        if (discoveryBound) {
            discoveryService.setListener(null);
            unbindService(discoveryConnection);
        }
        if (audioBound) unbindService(audioConnection);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Empêcher de quitter sans raccrocher
        hangup();
    }

    // -------------------------------------------------------------------------
    // Init UI
    // -------------------------------------------------------------------------

    private void initViews() {
        tvPeerName   = findViewById(R.id.tv_call_peer_name);
        tvCallStatus = findViewById(R.id.tv_call_status);
        tvTimer      = findViewById(R.id.tv_call_timer);
        btnMute      = findViewById(R.id.btn_mute);
        btnHangup    = findViewById(R.id.btn_hangup);

        tvPeerName.setText(peerName);
        tvTimer.setVisibility(View.INVISIBLE);

        btnMute.setOnClickListener(v -> toggleMute());
        btnHangup.setOnClickListener(v -> hangup());
    }

    private void updateStatus(String status) {
        runOnUiThread(() -> tvCallStatus.setText(status));
    }

    // -------------------------------------------------------------------------
    // Gestion de l'appel
    // -------------------------------------------------------------------------

    private void startCall() {
        if (inCall) return;
        inCall = true;

        updateStatus("En communication");
        runOnUiThread(() -> tvTimer.setVisibility(View.VISIBLE));
        startTimer();

        if (audioBound) {
            try {
                InetAddress addr = InetAddress.getByName(peerIp);
                audioStreamService.startStreaming(addr, peerAudioPort, myAudioPort);
            } catch (UnknownHostException e) {
                updateStatus("Erreur réseau");
            }
        }
    }

    private void stopCall() {
        if (audioBound && audioStreamService.isStreaming()) {
            audioStreamService.stopStreaming();
        }
        stopTimer();
    }

    private void hangup() {
        if (discoveryBound) {
            discoveryService.sendCallEnd(peerId);
        }
        stopCall();
        finish();
    }

    private void toggleMute() {
        muted = !muted;
        btnMute.setImageResource(muted
                ? android.R.drawable.ic_lock_silent_mode
                : android.R.drawable.ic_lock_silent_mode_off);
        // En production : suspendre l'envoi audio sans stopper le service
    }

    // -------------------------------------------------------------------------
    // Timer
    // -------------------------------------------------------------------------

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            elapsedSeconds++;
            int minutes = elapsedSeconds / 60;
            int seconds = elapsedSeconds % 60;
            tvTimer.setText(String.format("%02d:%02d", minutes, seconds));
            timerHandler.postDelayed(this, 1000);
        }
    };

    private void startTimer() { timerHandler.postDelayed(timerRunnable, 1000); }
    private void stopTimer()  { timerHandler.removeCallbacks(timerRunnable); }

    // -------------------------------------------------------------------------
    // DiscoveryService.DiscoveryListener
    // -------------------------------------------------------------------------

    @Override
    public void onPeersChanged(java.util.List<Peer> peers) {}

    @Override
    public void onCallRequest(Peer from) {}

    @Override
    public void onCallAccepted(Peer by) {
        // L'appelant reçoit l'acceptation → démarrer le streaming
        if (isCaller && by.getId().equals(peerId)) {
            startCall();
        }
    }

    @Override
    public void onCallRejected(Peer by) {
        if (by.getId().equals(peerId)) {
            updateStatus(peerName + " a refusé l'appel.");
            runOnUiThread(() -> {
                btnHangup.setText("Fermer");
                btnHangup.setOnClickListener(v -> finish());
            });
        }
    }

    @Override
    public void onCallEnded(Peer by) {
        if (by.getId().equals(peerId)) {
            stopCall();
            updateStatus("Appel terminé par " + peerName);
            runOnUiThread(() -> {
                btnHangup.setText("Fermer");
                btnHangup.setOnClickListener(v -> finish());
            });
        }
    }

    // -------------------------------------------------------------------------
    // ServiceConnections
    // -------------------------------------------------------------------------

    private final ServiceConnection discoveryConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            discoveryService = ((DiscoveryService.LocalBinder) service).getService();
            discoveryService.setListener(CallActivity.this);
            discoveryBound = true;

            // Si le callee, démarrer l'appel directement
            if (!isCaller) startCall();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) { discoveryBound = false; }
    };

    private final ServiceConnection audioConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            audioStreamService = ((AudioStreamService.LocalBinder) service).getService();
            audioBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) { audioBound = false; }
    };
}
