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

    private static final int REQ_PERM = 100;

    private DiscoveryService discoveryService;
    private boolean          serviceBound = false;

    private PeerAdapter       peerAdapter;
    private TextView          tvMyName, tvWifiInfo;
    private View              tvNoPeers;
    private RecyclerView      recyclerView;
    private FloatingActionButton fabRefresh;
    private String            myDisplayName;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            myDisplayName = Build.MODEL;
            initViews();
            checkPermission();
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
        // Toujours (re)enregistrer MainActivity comme listener CallManager
        CallManager.get().setListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Ne pas désenregistrer ici pour continuer à recevoir les appels en arrière-plan
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

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private void initViews() {
        tvMyName     = findViewById(R.id.tv_my_name);
        tvWifiInfo   = findViewById(R.id.tv_wifi_info);
        tvNoPeers    = findViewById(R.id.tv_no_peers);
        recyclerView = findViewById(R.id.recycler_peers);
        fabRefresh   = findViewById(R.id.fab_refresh);

        if (tvMyName != null) tvMyName.setText("Vous : " + myDisplayName);

        peerAdapter = new PeerAdapter(this::onPeerClicked);
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(peerAdapter);
        }
        if (fabRefresh != null) {
            fabRefresh.setOnClickListener(v -> {
                if (serviceBound) onPeersChanged(discoveryService.getPeerList());
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

    // -------------------------------------------------------------------------
    // Actions utilisateur
    // -------------------------------------------------------------------------

    private void onPeerClicked(Peer peer) {
        if (!CallManager.get().isIdle()) {
            Toast.makeText(this, "Un appel est déjà en cours.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Appeler " + peer.getDisplayName())
                .setMessage("Démarrer un appel vocal avec " + peer.getDisplayName() + " ?")
                .setPositiveButton("Appeler", (d, w) -> initiateCall(peer))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void initiateCall(Peer peer) {
        if (!serviceBound) return;
        CallManager.get().startOutgoing(peer);
        discoveryService.sendCallRequest(peer.getId());
        openCallScreen(peer.getDisplayName(), peer.getAddress().getHostAddress(),
                peer.getId(), true);
    }

    // -------------------------------------------------------------------------
    // CallManager.CallEventListener
    // -------------------------------------------------------------------------

    @Override
    public void onIncomingCall(Peer from) {
        runOnUiThread(() -> {
            // Vérifier qu'on n'est pas déjà en appel
            if (!CallManager.get().isIdle()) {
                if (serviceBound) discoveryService.sendCallReject(from.getId());
                return;
            }
            CallManager.get().startIncoming(from);

            new AlertDialog.Builder(this)
                    .setTitle("📞 Appel entrant")
                    .setMessage(from.getDisplayName() + " vous appelle.")
                    .setPositiveButton("Répondre", (d, w) -> acceptCall(from))
                    .setNegativeButton("Refuser",  (d, w) -> {
                        CallManager.get().reset();
                        if (serviceBound) discoveryService.sendCallReject(from.getId());
                    })
                    .setCancelable(false)
                    .show();
        });
    }

    private void acceptCall(Peer from) {
        // Ouvrir CallActivity — c'est elle qui enverra CALL_ACCEPT
        openCallScreen(from.getDisplayName(), from.getAddress().getHostAddress(),
                from.getId(), false);
    }

    @Override
    public void onCallAccepted(Peer by) {
        // Reçu par MainActivity si CallActivity n'est pas encore enregistrée
        // → ne rien faire ici, CallActivity gère cela via CallManager
    }

    @Override
    public void onCallRejected(Peer by) {
        runOnUiThread(() -> {
            CallManager.get().reset();
            Toast.makeText(this, by.getDisplayName() + " a refusé l'appel.", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onCallEnded(Peer by) {
        runOnUiThread(() -> {
            CallManager.get().reset();
            Toast.makeText(this, "Appel terminé.", Toast.LENGTH_SHORT).show();
        });
    }

    // -------------------------------------------------------------------------
    // DiscoveryService.PeersListener
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    private void openCallScreen(String name, String ip, String id, boolean isCaller) {
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra(CallActivity.EXTRA_PEER_NAME, name);
        i.putExtra(CallActivity.EXTRA_PEER_IP,   ip);
        i.putExtra(CallActivity.EXTRA_PEER_ID,   id);
        i.putExtra(CallActivity.EXTRA_IS_CALLER, isCaller);
        startActivity(i);
    }

    // -------------------------------------------------------------------------
    // ServiceConnection
    // -------------------------------------------------------------------------

    private final ServiceConnection svcConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            discoveryService = ((DiscoveryService.LocalBinder) b).getService();
            discoveryService.setPeersListener(MainActivity.this);
            serviceBound = true;
            if (discoveryService.getSelfId() == null) {
                discoveryService.startDiscovery(myDisplayName, CallActivity.CALLEE_AUDIO_PORT);
            }
            onPeersChanged(discoveryService.getPeerList());
        }
        @Override
        public void onServiceDisconnected(ComponentName n) { serviceBound = false; }
    };

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private void checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERM && (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Permission microphone requise.", Toast.LENGTH_LONG).show();
        }
    }
}
