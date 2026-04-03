package com.voicechat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.Camera;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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

    public static final String EXTRA_PEER_NAME  = "peer_name";
    public static final String EXTRA_PEER_IP    = "peer_ip";
    public static final String EXTRA_PEER_ID    = "peer_id";
    public static final String EXTRA_IS_CALLER  = "is_caller";
    public static final String EXTRA_WITH_VIDEO = "with_video";

    public static final int CALLER_AUDIO_PORT = 49876;
    public static final int CALLEE_AUDIO_PORT = 49877;
    public static final int CALLER_VIDEO_PORT = 49878;
    public static final int CALLEE_VIDEO_PORT = 49879;

    private String  peerName, peerIp, peerId;
    private boolean isCaller;
    private boolean withVideo;
    private int     myAudioPort, peerAudioPort;
    private int     myVideoPort, peerVideoPort;

    private boolean muted        = false;
    private boolean videoOff     = false;
    private boolean audioStarted = false;
    private boolean finishing    = false;

    // Les deux services DOIVENT être liés avant de démarrer
    private DiscoveryService   discoveryService;
    private AudioStreamService audioStreamService;
    private VideoStreamService videoStreamService;
    private boolean discoveryBound = false;
    private boolean audioBound     = false;
    private boolean videoBound     = false;

    // callReady = vrai quand le signal GO est reçu (CALL_ACCEPT côté caller, immédiat côté callee)
    private boolean callReady = false;

    // UI
    private TextView       tvPeerName, tvCallStatus, tvTimer;
    private ImageButton    btnMute, btnVideo, btnSpeaker;
    private MaterialButton btnHangup;
    private SurfaceView    svLocal, svRemote;
    private View           layoutVideo;

    private final Handler mainHandler    = new Handler(Looper.getMainLooper());
    private int           elapsedSeconds = 0;

    private static final int RETRY_MS  = 1500;
    private static final int MAX_RETRY = 10;
    private int retryCount = 0;

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
        withVideo = getIntent().getBooleanExtra(EXTRA_WITH_VIDEO, false);

        myAudioPort   = isCaller ? CALLER_AUDIO_PORT : CALLEE_AUDIO_PORT;
        peerAudioPort = isCaller ? CALLEE_AUDIO_PORT : CALLER_AUDIO_PORT;
        myVideoPort   = isCaller ? CALLER_VIDEO_PORT : CALLEE_VIDEO_PORT;
        peerVideoPort = isCaller ? CALLEE_VIDEO_PORT : CALLER_VIDEO_PORT;

        Log.d(TAG, "isCaller=" + isCaller + " myAudio=" + myAudioPort
                + " peerAudio=" + peerAudioPort + " ip=" + peerIp + " video=" + withVideo);

        initViews();
        updateStatus(isCaller ? "Appel de " + peerName + "..." : "Connexion...");
        CallManager.get().setListener(this);

        // Lier les services
        bindService(new Intent(this, DiscoveryService.class), discoveryConn, Context.BIND_AUTO_CREATE);

        Intent ai = new Intent(this, AudioStreamService.class);
        startService(ai);
        bindService(ai, audioConn, Context.BIND_AUTO_CREATE);

        if (withVideo) {
            Intent vi = new Intent(this, VideoStreamService.class);
            startService(vi);
            bindService(vi, videoConn, Context.BIND_AUTO_CREATE);
        } else {
            videoBound = true; // pas besoin de vidéo, considéré prêt
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        mainHandler.removeCallbacksAndMessages(null);
        if (audioBound && audioStreamService != null) audioStreamService.stopStreaming();
        if (withVideo && videoBound && videoStreamService != null) videoStreamService.stopStreaming();
        CallManager.get().setListener(null);
        if (discoveryBound) try { unbindService(discoveryConn); } catch (Exception ignored) {}
        if (audioBound)     try { unbindService(audioConn);     } catch (Exception ignored) {}
        if (withVideo && videoBound) try { unbindService(videoConn); } catch (Exception ignored) {}
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
        btnVideo     = findViewById(R.id.btn_video);
        btnSpeaker   = findViewById(R.id.btn_speaker);
        svLocal      = findViewById(R.id.sv_local);
        svRemote     = findViewById(R.id.sv_remote);
        layoutVideo  = findViewById(R.id.layout_video);

        if (tvPeerName != null) tvPeerName.setText(peerName != null ? peerName : "");
        if (tvTimer    != null) tvTimer.setVisibility(View.INVISIBLE);
        if (btnMute    != null) btnMute.setOnClickListener(v -> toggleMute());
        if (btnHangup  != null) btnHangup.setOnClickListener(v -> hangup());
        if (btnSpeaker != null) btnSpeaker.setOnClickListener(v -> toggleSpeaker());

        if (layoutVideo != null)
            layoutVideo.setVisibility(withVideo ? View.VISIBLE : View.GONE);

        if (btnVideo != null) {
            if (!withVideo) {
                btnVideo.setVisibility(View.GONE);
            } else {
                btnVideo.setOnClickListener(v -> toggleVideo());
            }
        }
    }

    private void updateStatus(String s) {
        runOnUiThread(() -> { if (tvCallStatus != null) tvCallStatus.setText(s); });
    }

    // -------------------------------------------------------------------------
    // Logique centrale : démarrer quand TOUT est prêt
    // -------------------------------------------------------------------------

    /**
     * Vérifie que les 3 conditions sont remplies :
     * 1. callReady   = signal GO reçu
     * 2. audioBound  = AudioStreamService lié
     * 3. videoBound  = VideoStreamService lié (ou pas de vidéo)
     * Si tout est OK → démarre l'audio (et la vidéo)
     */
    private void checkAndStart() {
        mainHandler.post(() -> {
            Log.d(TAG, "checkAndStart callReady=" + callReady
                    + " audioBound=" + audioBound + " videoBound=" + videoBound
                    + " audioStarted=" + audioStarted);

            if (audioStarted)  return;
            if (!callReady)    return;
            if (!audioBound || audioStreamService == null) return;
            if (!videoBound)   return;

            audioStarted = true;
            CallManager.get().setInCall();
            Log.d(TAG, "✅ Démarrage audio" + (withVideo ? " + vidéo" : ""));

            updateStatus("En communication");
            if (tvTimer != null) tvTimer.setVisibility(View.VISIBLE);
            mainHandler.postDelayed(timerRunnable, 1000);

            try {
                InetAddress addr = InetAddress.getByName(peerIp);
                audioStreamService.startStreaming(addr, peerAudioPort, myAudioPort);
                if (withVideo && videoStreamService != null) {
                    videoStreamService.startStreaming(addr, peerVideoPort, myVideoPort,
                            svLocal, svRemote);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur démarrage", e);
                updateStatus("Erreur réseau");
            }
        });
    }

    private void markReady() {
        Log.d(TAG, "markReady() audioBound=" + audioBound + " videoBound=" + videoBound);
        callReady = true;
        checkAndStart();
    }

    private void hangup() {
        if (finishing) return;
        finishing = true;
        mainHandler.removeCallbacks(timerRunnable);
        mainHandler.removeCallbacks(retryRunnable);
        if (audioBound && audioStreamService != null) audioStreamService.stopStreaming();
        if (withVideo && videoBound && videoStreamService != null) videoStreamService.stopStreaming();
        if (discoveryBound && discoveryService != null && peerId != null)
            discoveryService.sendCallEnd(peerId);
        CallManager.get().reset();
        finish();
    }

    private void closeByRemote() {
        if (finishing) return;
        finishing = true;
        mainHandler.removeCallbacks(timerRunnable);
        mainHandler.removeCallbacks(retryRunnable);
        if (audioBound && audioStreamService != null) audioStreamService.stopStreaming();
        if (withVideo && videoBound && videoStreamService != null) videoStreamService.stopStreaming();
        CallManager.get().reset();
        runOnUiThread(this::finish);
    }

    private void toggleMute() {
        muted = !muted;
        if (audioBound && audioStreamService != null) audioStreamService.setMuted(muted);
        if (btnMute != null) btnMute.setImageResource(muted
                ? android.R.drawable.ic_lock_silent_mode
                : android.R.drawable.ic_lock_silent_mode_off);
    }

    private void toggleSpeaker() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            boolean on = !am.isSpeakerphoneOn();
            am.setSpeakerphoneOn(on);
            if (btnSpeaker != null)
                btnSpeaker.setImageResource(on
                        ? android.R.drawable.ic_lock_silent_mode_off
                        : android.R.drawable.ic_lock_silent_mode);
        } catch (Exception ignored) {}
    }

    private void toggleVideo() {
        videoOff = !videoOff;
        if (withVideo && videoBound && videoStreamService != null)
            videoStreamService.setVideoMuted(videoOff);
        if (btnVideo != null)
            btnVideo.setImageResource(videoOff
                    ? android.R.drawable.ic_menu_close_clear_cancel
                    : android.R.drawable.ic_menu_camera);
    }

    // -------------------------------------------------------------------------
    // Retransmission CALL_ACCEPT (CALLEE)
    // -------------------------------------------------------------------------

    private final Runnable retryRunnable = new Runnable() {
        @Override public void run() {
            if (audioStarted || retryCount >= MAX_RETRY) return;
            retryCount++;
            Log.d(TAG, "Retry CALL_ACCEPT #" + retryCount);
            if (discoveryBound && discoveryService != null && peerId != null)
                discoveryService.sendCallAccept(peerId);
            mainHandler.postDelayed(this, RETRY_MS);
        }
    };

    // -------------------------------------------------------------------------
    // CallManager.CallEventListener
    // -------------------------------------------------------------------------

    @Override
    public void onCallAccepted(Peer by) {
        if (isCaller && peerId != null && peerId.equals(by.getId())) {
            Log.d(TAG, "✅ CALL_ACCEPT reçu de " + by.getDisplayName());
            markReady();
        }
    }

    @Override
    public void onCallRejected(Peer by) {
        if (peerId != null && peerId.equals(by.getId())) closeByRemote();
    }

    @Override
    public void onCallEnded(Peer by) {
        if (peerId != null && peerId.equals(by.getId())) {
            Log.d(TAG, "CALL_END reçu");
            closeByRemote();
        }
    }

    @Override public void onIncomingCall(Peer from) {}

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
                // CALLEE : envoyer CALL_ACCEPT et retransmettre
                discoveryService.sendCallAccept(peerId);
                mainHandler.postDelayed(retryRunnable, RETRY_MS);
                // Marquer prêt — si audioBound est déjà true, l'audio démarre tout de suite
                markReady();
            }
            // Pour le CALLER : on attend onCallAccepted()
            // Mais on tente quand même au cas où les deux services arrivent dans l'ordre inverse
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
            // Tenter de démarrer — si callReady est déjà true, ça partira maintenant
            checkAndStart();
        }
        @Override public void onServiceDisconnected(ComponentName n) { audioBound = false; }
    };

    private final ServiceConnection videoConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            videoStreamService = ((VideoStreamService.LocalBinder) b).getService();
            videoBound         = true;
            Log.d(TAG, "VideoStreamService lié");
            checkAndStart();
        }
        @Override public void onServiceDisconnected(ComponentName n) { videoBound = false; }
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
