package com.voicechat;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
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
        implements DiscoveryService.DiscoveryListener {

    private static final int REQUEST_PERMISSIONS = 100;

    private DiscoveryService discoveryService;
    private boolean serviceBound = false;

    private PeerAdapter peerAdapter;
    private TextView tvMyName;
    private TextView tvWifiInfo;
    private View tvNoPeers;
    private RecyclerView recyclerView;
    private FloatingActionButton fabRefresh;

    private String myDisplayName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            myDisplayName = Build.MODEL;
            initViews();
            checkPermissionsAndStart();
        } catch (Exception e) {
            Toast.makeText(this, "Erreur démarrage : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            Intent intent = new Intent(this, DiscoveryService.class);
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            startService(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Erreur service : " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            if (serviceBound) {
                discoveryService.setListener(null);
                unbindService(serviceConnection);
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

        peerAdapter = new PeerAdapter(this::onPeerSelected);
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(peerAdapter);
        }

        if (fabRefresh != null) {
            fabRefresh.setOnClickListener(v -> {
                if (serviceBound) {
                    peerAdapter.updatePeers(discoveryService.getPeerList());
                    Toast.makeText(this, "Liste actualisée", Toast.LENGTH_SHORT).show();
                }
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
                int ipInt = wifi.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    String ip = String.format("%d.%d.%d.%d",
                            (ipInt & 0xff), (ipInt >> 8 & 0xff),
                            (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
                    tvWifiInfo.setText("Wi-Fi connecté • " + ip);
                    tvWifiInfo.setTextColor(ContextCompat.getColor(this, R.color.green));
                } else {
                    tvWifiInfo.setText("Wi-Fi connecté");
                    tvWifiInfo.setTextColor(ContextCompat.getColor(this, R.color.green));
                }
            } else {
                tvWifiInfo.setText("⚠ Wi-Fi non connecté");
                tvWifiInfo.setTextColor(ContextCompat.getColor(this, R.color.red));
            }
        } catch (Exception e) {
            if (tvWifiInfo != null) tvWifiInfo.setText("Wi-Fi : état inconnu");
        }
    }

    private void onPeerSelected(Peer peer) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle("Appeler " + peer.getDisplayName())
                    .setMessage("Démarrer un appel vocal avec " + peer.getDisplayName() + " ?")
                    .setPositiveButton("Appeler", (d, w) -> initiateCall(peer))
                    .setNegativeButton("Annuler", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "Erreur : " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void initiateCall(Peer peer) {
        if (!serviceBound) return;
        try {
            discoveryService.sendCallRequest(peer.getId());
            Intent intent = new Intent(this, CallActivity.class);
            intent.putExtra(CallActivity.EXTRA_PEER_NAME, peer.getDisplayName());
            intent.putExtra(CallActivity.EXTRA_PEER_IP,   peer.getAddress().getHostAddress());
            intent.putExtra(CallActivity.EXTRA_PEER_ID,   peer.getId());
            intent.putExtra(CallActivity.EXTRA_IS_CALLER, true);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Impossible de lancer l'appel : " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // DiscoveryService.DiscoveryListener

    @Override
    public void onPeersChanged(List<Peer> peers) {
        try {
            peerAdapter.updatePeers(peers);
            if (tvNoPeers != null)
                tvNoPeers.setVisibility(peers.isEmpty() ? View.VISIBLE : View.GONE);
            if (recyclerView != null)
                recyclerView.setVisibility(peers.isEmpty() ? View.GONE : View.VISIBLE);
        } catch (Exception ignored) {}
    }

    @Override
    public void onCallRequest(Peer from) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle("📞 Appel entrant")
                    .setMessage(from.getDisplayName() + " vous appelle.")
                    .setPositiveButton("Répondre", (d, w) -> acceptCall(from))
                    .setNegativeButton("Refuser",  (d, w) -> {
                        if (serviceBound) discoveryService.sendCallReject(from.getId());
                    })
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "Appel de " + from.getDisplayName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void acceptCall(Peer from) {
        if (!serviceBound) return;
        try {
            // Envoyer CALL_ACCEPT AVANT d'ouvrir l'activité
            discoveryService.sendCallAccept(from.getId());

            Intent intent = new Intent(this, CallActivity.class);
            intent.putExtra(CallActivity.EXTRA_PEER_NAME, from.getDisplayName());
            intent.putExtra(CallActivity.EXTRA_PEER_IP,   from.getAddress().getHostAddress());
            intent.putExtra(CallActivity.EXTRA_PEER_ID,   from.getId());
            intent.putExtra(CallActivity.EXTRA_IS_CALLER, false);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Erreur acceptation : " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onCallAccepted(Peer by) {}
    @Override public void onCallRejected(Peer by) {
        Toast.makeText(this, by.getDisplayName() + " a refusé.", Toast.LENGTH_SHORT).show();
    }
    @Override public void onCallEnded(Peer by) {
        Toast.makeText(this, "Appel terminé.", Toast.LENGTH_SHORT).show();
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                discoveryService = ((DiscoveryService.LocalBinder) service).getService();
                discoveryService.setListener(MainActivity.this);
                serviceBound = true;
                if (discoveryService.getSelfId() == null) {
                    // Port d'annonce : on annonce le port CALLEE car c'est la valeur par défaut
                    // Le vrai port est assigné dans CallActivity selon le rôle
                    discoveryService.startDiscovery(myDisplayName, CallActivity.CALLEE_AUDIO_PORT);
                }
                onPeersChanged(discoveryService.getPeerList());
            } catch (Exception e) {
                Toast.makeText(MainActivity.this,
                        "Erreur connexion service : " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { serviceBound = false; }
    };

    private void checkPermissionsAndStart() {
        String[] permissions = { Manifest.permission.RECORD_AUDIO };
        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false; break;
            }
        }
        if (!allGranted) ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permission microphone requise.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
    }
}
