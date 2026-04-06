package com.voicechat;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;

public class MainActivity extends AppCompatActivity
        implements CallManager.CallEventListener,
                   DiscoveryService.PeersListener {

    private static final String TAG     = "MainActivity";
    private static final int    REQ_PERM = 100;

    private DiscoveryService     discoveryService;
    private boolean              serviceBound = false;
    private PeerAdapter          peerAdapter;
    private TextView             tvMyName, tvWifiInfo;
    private View                 tvNoPeers;
    private RecyclerView         recyclerView;
    private FloatingActionButton fabRefresh;
    private String               myDisplayName;
    private AlertDialog          incomingDialog = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            myDisplayName = Build.MODEL;
            initViews();
            checkPermissions();
        } catch (Exception e) {
            Toast.makeText(this, "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent i = new Intent(this, DiscoveryService.class);
        bindService(i, svcConn, Context.BIND_AUTO_CREATE);
        startService(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        CallManager.get().setListener(this);
        Log.d(TAG, "onResume → listener enregistré. État=" + CallManager.get().getState());
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            if (serviceBound) {
                discoveryService.setPeersListener(null);
                unbindService(svcConn);
                serviceBound = false;
            }
        } catch (Exception ignored) {}
    }

    private void initViews() {
        tvMyName     = findViewById(R.id.tv_my_name);
        tvWifiInfo   = findViewById(R.id.tv_wifi_info);
        tvNoPeers    = findViewById(R.id.tv_no_peers);
        recyclerView = findViewById(R.id.recycler_peers);
        fabRefresh   = findViewById(R.id.fab_refresh);
        if (tvMyName != null) tvMyName.setText("Vous : " + myDisplayName);

        peerAdapter = new PeerAdapter(
                this::onAudioCallClicked,
                this::onVideoCallClicked);

        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(peerAdapter);
        }
        if (fabRefresh != null) {
            fabRefresh.setOnClickListener(v -> {
                if (serviceBound) onPeersChanged(discoveryService.getPeerList());
                updateWifiInfo();
            });
        }
        updateWifiInfo();
    }

    private void updateWifiInfo() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (tvWifiInfo == null) return;
            if (wifi != null && wifi.isWifiEnabled()) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    tvWifiInfo.setText("Wi-Fi • " + String.format("%d.%d.%d.%d",
                            ip & 0xff, ip >> 8 & 0xff, ip >> 16 & 0xff, ip >> 24 & 0xff));
                    tvWifiInfo.setTextColor(ContextCompat.getColor(this, R.color.green));
                } else {
                    tvWifiInfo.setText("Wi-Fi connecté");
                    tvWifiInfo.setTextColor(ContextCompat.getColor(this, R.color.green));
                }
            } else {
                tvWifiInfo.setText("⚠ Wi-Fi non connecté");
                tvWifiInfo.setTextColor(ContextCompat.getColor(this, R.color.red));
            }
        } catch (Exception ignored) {}
    }

    private void onAudioCallClicked(Peer peer) { startCall(peer, false); }
    private void onVideoCallClicked(Peer peer) { startCall(peer, true);  }

    private void startCall(Peer peer, boolean withVideo) {
        if (!CallManager.get().isIdle()) {
            Toast.makeText(this, "Un appel est déjà en cours.", Toast.LENGTH_SHORT).show();
            return;
        }
        String type = withVideo ? "vidéo" : "vocal";
        new AlertDialog.Builder(this)
                .setTitle((withVideo ? "📹 " : "📞 ") + "Appeler " + peer.getDisplayName())
                .setMessage("Démarrer un appel " + type + " avec " + peer.getDisplayName() + " ?")
                .setPositiveButton("Appeler", (d, w) -> initiateCall(peer, withVideo))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void initiateCall(Peer peer, boolean withVideo) {
        if (!serviceBound) return;
        CallManager.get().startOutgoing(peer);
        discoveryService.sendCallRequest(peer.getId());
        openCallScreen(peer.getDisplayName(), peer.getAddress().getHostAddress(),
                peer.getId(), true, withVideo);
    }

    // -------------------------------------------------------------------------
    // CallManager.CallEventListener
    // -------------------------------------------------------------------------

    @Override
    public void onIncomingCall(Peer from) {
        runOnUiThread(() -> {
            if (!CallManager.get().isIdle()) {
                if (serviceBound) discoveryService.sendCallReject(from.getId());
                return;
            }
            CallManager.get().startIncoming(from);
            if (incomingDialog != null && incomingDialog.isShowing()) incomingDialog.dismiss();

            incomingDialog = new AlertDialog.Builder(this)
                    .setTitle("📞 Appel entrant")
                    .setMessage(from.getDisplayName() + " vous appelle.")
                    .setPositiveButton("🎙 Répondre (vocal)", (d, w) -> {
                        incomingDialog = null;
                        openCallScreen(from.getDisplayName(),
                                from.getAddress().getHostAddress(),
                                from.getId(), false, false);
                    })
                    .setNeutralButton("📹 Répondre (vidéo)", (d, w) -> {
                        incomingDialog = null;
                        openCallScreen(from.getDisplayName(),
                                from.getAddress().getHostAddress(),
                                from.getId(), false, true);
                    })
                    .setNegativeButton("Refuser", (d, w) -> {
                        incomingDialog = null;
                        CallManager.get().reset();
            if (serviceBound && discoveryService != null) discoveryService.restartDiscovery();
                        if (serviceBound) discoveryService.sendCallReject(from.getId());
                    })
                    .setCancelable(false)
                    .create();
            incomingDialog.show();
        });
    }

    @Override public void onCallAccepted(Peer by) {}

    @Override
    public void onCallRejected(Peer by) {
        runOnUiThread(() -> {
            CallManager.get().reset();
            if (serviceBound && discoveryService != null) discoveryService.restartDiscovery();
            Toast.makeText(this, by.getDisplayName() + " a refusé.", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onCallEnded(Peer by) {
        runOnUiThread(() -> {
            CallManager.get().reset();
            if (serviceBound && discoveryService != null) discoveryService.restartDiscovery();
            if (incomingDialog != null && incomingDialog.isShowing()) {
                incomingDialog.dismiss(); incomingDialog = null;
            }
        });
    }

    @Override
    public void onPeersChanged(List<Peer> peers) {
        runOnUiThread(() -> {
            try {
                peerAdapter.updatePeers(peers);
                if (tvNoPeers  != null) tvNoPeers.setVisibility(peers.isEmpty() ? View.VISIBLE : View.GONE);
                if (recyclerView != null) recyclerView.setVisibility(peers.isEmpty() ? View.GONE : View.VISIBLE);
            } catch (Exception ignored) {}
        });
    }

    private void openCallScreen(String name, String ip, String id,
                                boolean isCaller, boolean withVideo) {
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra(CallActivity.EXTRA_PEER_NAME,  name);
        i.putExtra(CallActivity.EXTRA_PEER_IP,    ip);
        i.putExtra(CallActivity.EXTRA_PEER_ID,    id);
        i.putExtra(CallActivity.EXTRA_IS_CALLER,  isCaller);
        i.putExtra(CallActivity.EXTRA_WITH_VIDEO, withVideo);
        startActivity(i);
    }

    private final ServiceConnection svcConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            discoveryService = ((DiscoveryService.LocalBinder) b).getService();
            discoveryService.setPeersListener(MainActivity.this);
            serviceBound = true;
            if (discoveryService.getSelfId() == null)
                discoveryService.startDiscovery(myDisplayName, CallActivity.CALLEE_AUDIO_PORT);
            onPeersChanged(discoveryService.getPeerList());
        }
        @Override public void onServiceDisconnected(ComponentName n) { serviceBound = false; }
    };

    private void checkPermissions() {
        String[] perms = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
        };
        boolean ok = true;
        for (String p : perms)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
            { ok = false; break; }
        if (!ok) ActivityCompat.requestPermissions(this, perms, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
    }
}
