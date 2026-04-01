package com.voicechat;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
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
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

/**
 * Activité principale : liste des pairs disponibles sur le Wi-Fi.
 */
public class MainActivity extends AppCompatActivity
        implements DiscoveryService.DiscoveryListener {

    private static final int REQUEST_PERMISSIONS = 100;
    // Port d'écoute audio local (fixe dans cet exemple, à dynamiser en production)
    private static final int MY_AUDIO_PORT = 49876;

    private DiscoveryService discoveryService;
    private boolean serviceBound = false;

    private PeerAdapter peerAdapter;
    private TextView tvMyName;
    private TextView tvWifiInfo;
    private TextView tvNoPeers;
    private RecyclerView recyclerView;
    private FloatingActionButton fabRefresh;

    private String myDisplayName;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        myDisplayName = getDeviceName();
        tvMyName.setText("Vous : " + myDisplayName);

        checkPermissionsAndStart();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, DiscoveryService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        startService(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (serviceBound) {
            discoveryService.setListener(null);
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    // -------------------------------------------------------------------------
    // Init UI
    // -------------------------------------------------------------------------

    private void initViews() {
        tvMyName   = findViewById(R.id.tv_my_name);
        tvWifiInfo = findViewById(R.id.tv_wifi_info);
        tvNoPeers  = findViewById(R.id.tv_no_peers);
        recyclerView = findViewById(R.id.recycler_peers);
        fabRefresh = findViewById(R.id.fab_refresh);

        peerAdapter = new PeerAdapter(peer -> onPeerSelected(peer));
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(peerAdapter);

        fabRefresh.setOnClickListener(v -> {
            if (serviceBound) {
                peerAdapter.updatePeers(discoveryService.getPeerList());
                Snackbar.make(v, "Liste actualisée", Snackbar.LENGTH_SHORT).show();
            }
        });

        // Afficher l'info Wi-Fi
        updateWifiInfo();
    }

    private void updateWifiInfo() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null && wifi.isWifiEnabled()) {
            int ipInt = wifi.getConnectionInfo().getIpAddress();
            String ip = String.format("%d.%d.%d.%d",
                    (ipInt & 0xff), (ipInt >> 8 & 0xff),
                    (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
            tvWifiInfo.setText("Wi-Fi connecté • " + ip);
            tvWifiInfo.setTextColor(ContextCompat.getColor(this, R.color.green));
        } else {
            tvWifiInfo.setText("⚠ Wi-Fi non connecté");
            tvWifiInfo.setTextColor(ContextCompat.getColor(this, R.color.red));
        }
    }

    // -------------------------------------------------------------------------
    // Sélection d'un pair → initier un appel
    // -------------------------------------------------------------------------

    private void onPeerSelected(Peer peer) {
        new AlertDialog.Builder(this)
                .setTitle("Appeler " + peer.getDisplayName())
                .setMessage("Voulez-vous démarrer un appel vocal avec " + peer.getDisplayName() + " ?")
                .setPositiveButton("Appeler", (d, w) -> initiateCall(peer))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void initiateCall(Peer peer) {
        if (!serviceBound) return;
        discoveryService.sendCallRequest(peer.getId());

        // Passer à l'écran d'appel en attente
        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_PEER_NAME, peer.getDisplayName());
        intent.putExtra(CallActivity.EXTRA_PEER_IP, peer.getAddress().getHostAddress());
        intent.putExtra(CallActivity.EXTRA_PEER_AUDIO_PORT, peer.getAudioPort());
        intent.putExtra(CallActivity.EXTRA_PEER_ID, peer.getId());
        intent.putExtra(CallActivity.EXTRA_MY_AUDIO_PORT, MY_AUDIO_PORT);
        intent.putExtra(CallActivity.EXTRA_IS_CALLER, true);
        startActivity(intent);
    }

    // -------------------------------------------------------------------------
    // DiscoveryService.DiscoveryListener
    // -------------------------------------------------------------------------

    @Override
    public void onPeersChanged(List<Peer> peers) {
        peerAdapter.updatePeers(peers);
        tvNoPeers.setVisibility(peers.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(peers.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onCallRequest(Peer from) {
        // Afficher une boîte de dialogue pour accepter/rejeter l'appel entrant
        new AlertDialog.Builder(this)
                .setTitle("📞 Appel entrant")
                .setMessage(from.getDisplayName() + " vous appelle.")
                .setPositiveButton("Répondre", (d, w) -> acceptCall(from))
                .setNegativeButton("Refuser", (d, w) -> {
                    if (serviceBound) discoveryService.sendCallReject(from.getId());
                })
                .setCancelable(false)
                .show();
    }

    private void acceptCall(Peer from) {
        if (!serviceBound) return;
        discoveryService.sendCallAccept(from.getId());

        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_PEER_NAME, from.getDisplayName());
        intent.putExtra(CallActivity.EXTRA_PEER_IP, from.getAddress().getHostAddress());
        intent.putExtra(CallActivity.EXTRA_PEER_AUDIO_PORT, from.getAudioPort());
        intent.putExtra(CallActivity.EXTRA_PEER_ID, from.getId());
        intent.putExtra(CallActivity.EXTRA_MY_AUDIO_PORT, MY_AUDIO_PORT);
        intent.putExtra(CallActivity.EXTRA_IS_CALLER, false);
        startActivity(intent);
    }

    @Override
    public void onCallAccepted(Peer by) {
        // Géré dans CallActivity
    }

    @Override
    public void onCallRejected(Peer by) {
        Toast.makeText(this, by.getDisplayName() + " a refusé l'appel.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onCallEnded(Peer by) {
        Toast.makeText(this, "L'appel avec " + by.getDisplayName() + " a pris fin.", Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // ServiceConnection
    // -------------------------------------------------------------------------

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            DiscoveryService.LocalBinder binder = (DiscoveryService.LocalBinder) service;
            discoveryService = binder.getService();
            discoveryService.setListener(MainActivity.this);
            serviceBound = true;

            if (discoveryService.getSelfId() == null) {
                discoveryService.startDiscovery(myDisplayName, MY_AUDIO_PORT);
            }

            onPeersChanged(discoveryService.getPeerList());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private void checkPermissionsAndStart() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        };

        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this,
                            "Permissions requises pour fonctionner.", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    private String getDeviceName() {
        String name = Settings.Global.getString(getContentResolver(), "device_name");
        if (name == null || name.isEmpty()) {
            name = android.os.Build.MODEL;
        }
        return name;
    }
}
