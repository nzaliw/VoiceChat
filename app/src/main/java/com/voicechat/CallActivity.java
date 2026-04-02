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
    public static final String EXTRA_PEER_AUDIO_PORT = "peer_audio_port";
    public static final String EXTRA_PEER_ID         = "peer_id";
    public static final String EXTRA_MY_AUDIO_PORT   = "my_audio_port";
    public static final String EXTRA_IS_CALLER       = "is_caller";

    // Ports audio :
    // L'appelant écoute sur 49876, envoie vers le port d'écoute du callee (49877)
    // Le callee écoute sur 49877, envoie vers le port d'écoute de l'appelant (49876)
    public static final int CALLER_AUDIO_PORT = 49876;
    public static final int CALLEE_AUDIO_PORT = 49877;

    private String peerName;
    private String peerIp;
    private int    peerAudioPort; // port sur lequel le PAIR écoute
    private String peerId;
    private int    myAudioPort;   // port sur lequel MOI j'écoute
    private boolean isCaller;

    private boolean inCall = false;
    private boolean muted  = false;

    private DiscoveryService  discoveryService;
    private AudioStreamService audioStreamService;
    private boolean discoveryBound = false;
    private boolean audioBound     = false;

    private TextView tvPeerName;
    private TextView tvCallStatus;
    private TextView tvTimer;
    private ImageButton btnMute;
    private MaterialButton btnHangup;

    private Handler timerHandler;
    private int elapsedSeconds = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        peerName      = getIntent().getStringExtra(EXTRA_PEER_NAME);
        peerIp        = getIntent().getStringExtra(EXTRA_PEER_IP);
        peerId        = getIntent().getStringExtra(EXTRA_PEER_ID);
        isCaller      = getIntent().getBooleanExtra(EXTRA_IS_CALLER, true);

        // Attribution des ports :
        // Appelant écoute sur CALLER_AUDIO_PORT, le pair (callee) écoute sur CALLEE_AUDIO_PORT
        // Callee écoute sur CALLEE_AUDIO_PORT, le pair (caller) écoute sur CALLER_AUDIO_PORT
        if (isCaller) {
            myAudioPort   = CALLER_AUDIO_PORT;
            peerAudioPort = CALLEE_AUDIO_PORT;
        } else {
            myAudioPort   = CALLEE_AUDIO_PORT;
            peerAudioPort = CALLER_AUDIO_PORT;
        }

        Log.d(TAG, "CallActivity : isCaller=" + isCaller
                + " myPort=" + myAudioPort + " peerPort=" + peerAudioPort
                + " peerIp=" + peerIp);

        initViews();
        timerHandler = new Handler(Looper.getMainLooper());

        Intent discIntent = new Intent(this, DiscoveryService.class);
        bindService(discIntent, discoveryConnection, Context.BIND_AUTO_CREATE);

        Intent audioIntent = new Intent(this, AudioStreamService.class);
        bindService(audioIntent, audioConnection, Context.BIND_AUTO_CREATE);
        startService(audioIntent);

        updateStatus(isCaller ? "Appel de " + peerName + "..." : "Connexion...");
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
    public void onBackPressed() { hangup(); }

    private void initViews() {
        tvPeerName   = findViewById(R.id.tv_call_peer_name);
        tvCallStatus = findViewById(R.id.tv_call_status);
        tvTimer      = findViewById(R.id.tv_call_timer);
        btnMute      = findViewById(R.id.btn_mute);
        btnHangup    = findViewById(R.id.btn_hangup);

        if (tvPeerName != null) tvPeerName.setText(peerName);
        if (tvTimer != null) tvTimer.setVisibility(View.INVISIBLE);

        if (btnMute != null) btnMute.setOnClickListener(v -> toggleMute());
        if (btnHangup != null) btnHangup.setOnClickListener(v -> hangup());
    }

    private void updateStatus(String status) {
        runOnUiThread(() -> { if (tvCallStatus != null) tvCallStatus.setText(status); });
    }

    private void startCall() {
        if (inCall) return;
        inCall = true;
        Log.d(TAG, "Démarrage de l'appel audio");
        updateStatus("En communication");
        runOnUiThread(() -> { if (tvTimer != null) tvTimer.setVisibility(View.VISIBLE); });
        startTimer();

        if (audioBound) {
            try {
                InetAddress addr = InetAddress.getByName(peerIp);
                audioStreamService.startStreaming(addr, peerAudioPort, myAudioPort);
            } catch (UnknownHostException e) {
                Log.e(TAG, "IP invalide : " + peerIp, e);
                updateStatus("Erreur réseau : IP invalide");
            }
        } else {
            Log.e(TAG, "AudioStreamService non lié !");
            updateStatus("Erreur : service audio non disponible");
        }
    }

    private void stopCall() {
        stopTimer();
        if (audioBound && audioStreamService != null && audioStreamService.isStreaming()) {
            audioStreamService.stopStreaming();
        }
    }

    private void hangup() {
        if (discoveryBound && discoveryService != null) {
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

    // Timer
    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            elapsedSeconds++;
            if (tvTimer != null)
                tvTimer.setText(String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60));
            timerHandler.postDelayed(this, 1000);
        }
    };
    private void startTimer() { timerHandler.postDelayed(timerRunnable, 1000); }
    private void stopTimer()  { timerHandler.removeCallbacks(timerRunnable); }

    // DiscoveryListener
    @Override public void onPeersChanged(List<Peer> peers) {}

    @Override public void onCallRequest(Peer from) {}

    @Override
    public void onCallAccepted(Peer by) {
        // L'appelant reçoit l'acceptation → démarrer l'audio
        if (isCaller && by.getId().equals(peerId)) {
            Log.d(TAG, "Appel accepté par " + by.getDisplayName());
            startCall();
        }
    }

    @Override
    public void onCallRejected(Peer by) {
        if (by.getId().equals(peerId)) {
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
        if (by.getId().equals(peerId)) {
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

    // ServiceConnections
    private final ServiceConnection discoveryConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            discoveryService = ((DiscoveryService.LocalBinder) service).getService();
            discoveryService.setListener(CallActivity.this);
            discoveryBound = true;
            Log.d(TAG, "DiscoveryService lié");
            // Si callee : démarrer l'audio dès que le service est prêt
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
            Log.d(TAG, "AudioStreamService lié");
            // Si callee et que l'appel est déjà marqué inCall, démarrer maintenant
            if (!isCaller && inCall && !audioStreamService.isStreaming()) {
                try {
                    InetAddress addr = InetAddress.getByName(peerIp);
                    audioStreamService.startStreaming(addr, peerAudioPort, myAudioPort);
                } catch (Exception e) { Log.e(TAG, "Erreur démarrage audio tardif", e); }
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { audioBound = false; }
    };
}
