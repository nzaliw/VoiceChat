package com.voicechat;

import android.app.Service;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Découverte des pairs via UDP Broadcast (255.255.255.255 + broadcast du sous-réseau).
 * Plus compatible que le multicast sur les routeurs domestiques.
 */
public class DiscoveryService extends Service {

    private static final String TAG = "DiscoveryService";
    public static final int DISCOVERY_PORT = 45678;
    private static final int ANNOUNCE_INTERVAL_MS = 2000;

    public static final String MSG_ANNOUNCE     = "ANNOUNCE";
    public static final String MSG_CALL_REQUEST = "CALL_REQUEST";
    public static final String MSG_CALL_ACCEPT  = "CALL_ACCEPT";
    public static final String MSG_CALL_REJECT  = "CALL_REJECT";
    public static final String MSG_CALL_END     = "CALL_END";
    public static final String MSG_BYE          = "BYE";

    private String selfId;
    private String selfName;
    private int selfAudioPort;

    private final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();

    private DatagramSocket sendSocket;
    private DatagramSocket recvSocket;
    private WifiManager.WifiLock wifiLock;

    private ExecutorService executor;
    private volatile boolean running = false;
    private Handler mainHandler;
    private DiscoveryListener listener;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public DiscoveryService getService() { return DiscoveryService.this; }
    }

    public interface DiscoveryListener {
        void onPeersChanged(List<Peer> peers);
        void onCallRequest(Peer from);
        void onCallAccepted(Peer by);
        void onCallRejected(Peer by);
        void onCallEnded(Peer by);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newFixedThreadPool(3);

        // WifiLock pour maintenir le Wi-Fi actif
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VoiceChatWifi");
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "WifiLock non disponible : " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public void startDiscovery(String name, int audioPort) {
        if (running) return;
        selfId = UUID.randomUUID().toString();
        selfName = name;
        selfAudioPort = audioPort;
        running = true;

        executor.submit(() -> {
            try {
                // Socket d'envoi (broadcast)
                sendSocket = new DatagramSocket();
                sendSocket.setBroadcast(true);
                sendSocket.setSoTimeout(1000);

                // Socket de réception
                recvSocket = new DatagramSocket(null);
                recvSocket.setReuseAddress(true);
                recvSocket.setBroadcast(true);
                recvSocket.bind(new InetSocketAddress(DISCOVERY_PORT));
                recvSocket.setSoTimeout(2000);

                Log.d(TAG, "Discovery broadcast démarré. ID=" + selfId + " name=" + selfName);

                executor.submit(this::receiveLoop);
                executor.submit(this::announceLoop);
                executor.submit(this::cleanupLoop);

            } catch (Exception e) {
                Log.e(TAG, "Erreur démarrage discovery", e);
                running = false;
            }
        });
    }

    public void stopDiscovery() {
        running = false;
        sendBroadcastMessage(MSG_BYE, null);
        try { if (sendSocket != null) sendSocket.close(); } catch (Exception ignored) {}
        try { if (recvSocket != null) recvSocket.close(); } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Boucles
    // -------------------------------------------------------------------------

    private void receiveLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                if (recvSocket == null || recvSocket.isClosed()) break;
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                recvSocket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                handleMessage(msg, packet.getAddress());
            } catch (java.net.SocketTimeoutException e) {
                // normal
            } catch (Exception e) {
                if (running) Log.w(TAG, "Erreur réception : " + e.getMessage());
            }
        }
    }

    private void announceLoop() {
        while (running) {
            sendBroadcastMessage(MSG_ANNOUNCE, null);
            try { Thread.sleep(ANNOUNCE_INTERVAL_MS); }
            catch (InterruptedException e) { break; }
        }
    }

    private void cleanupLoop() {
        while (running) {
            try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
            boolean changed = false;
            Iterator<Map.Entry<String, Peer>> it = peers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Peer> entry = it.next();
                if (!entry.getValue().isActive()) {
                    it.remove();
                    changed = true;
                    Log.d(TAG, "Peer retiré : " + entry.getValue().getDisplayName());
                }
            }
            if (changed) notifyPeersChanged();
        }
    }

    // -------------------------------------------------------------------------
    // Envoi broadcast
    // -------------------------------------------------------------------------

    private void sendBroadcastMessage(String type, String targetId) {
        executor.submit(() -> {
            try {
                if (sendSocket == null || sendSocket.isClosed()) return;

                JSONObject json = new JSONObject();
                json.put("type", type);
                json.put("id", selfId != null ? selfId : "unknown");
                json.put("name", selfName != null ? selfName : "Unknown");
                json.put("audioPort", selfAudioPort);
                if (targetId != null) json.put("targetId", targetId);

                byte[] data = json.toString().getBytes("UTF-8");

                // Envoyer en broadcast global
                sendTo(data, "255.255.255.255");

                // Envoyer aussi sur le broadcast du sous-réseau Wi-Fi
                String subnetBroadcast = getWifiBroadcastAddress();
                if (subnetBroadcast != null && !subnetBroadcast.equals("255.255.255.255")) {
                    sendTo(data, subnetBroadcast);
                }

            } catch (Exception e) {
                Log.w(TAG, "Erreur envoi broadcast : " + e.getMessage());
            }
        });
    }

    private void sendTo(byte[] data, String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            DatagramPacket packet = new DatagramPacket(data, data.length, addr, DISCOVERY_PORT);
            sendSocket.send(packet);
            Log.d(TAG, "Annonce envoyée → " + ip);
        } catch (Exception e) {
            Log.w(TAG, "Envoi échoué vers " + ip + " : " + e.getMessage());
        }
    }

    /**
     * Calcule l'adresse de broadcast du réseau Wi-Fi actuel.
     * Ex : 192.168.8.100 avec masque 255.255.255.0 → 192.168.8.255
     */
    private String getWifiBroadcastAddress() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wifi == null) return null;

            int ipInt   = wifi.getConnectionInfo().getIpAddress();
            int maskInt = wifi.getDhcpInfo().netmask;
            int broadcast = (ipInt & maskInt) | ~maskInt;

            return String.format("%d.%d.%d.%d",
                    (broadcast & 0xff),
                    (broadcast >> 8 & 0xff),
                    (broadcast >> 16 & 0xff),
                    (broadcast >> 24 & 0xff));
        } catch (Exception e) {
            Log.w(TAG, "Impossible de calculer le broadcast : " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Traitement des messages reçus
    // -------------------------------------------------------------------------

    private void handleMessage(String raw, InetAddress senderAddress) {
        try {
            JSONObject json = new JSONObject(raw);
            String type     = json.getString("type");
            String senderId = json.getString("id");

            if (selfId != null && selfId.equals(senderId)) return;

            String senderName = json.optString("name", "Inconnu");
            int    audioPort  = json.optInt("audioPort", 0);
            String targetId   = json.optString("targetId", "");

            switch (type) {
                case MSG_ANNOUNCE: {
                    boolean isNew = !peers.containsKey(senderId);
                    Peer peer = peers.computeIfAbsent(senderId,
                            k -> new Peer(senderId, senderName, senderAddress, audioPort));
                    peer.setLastSeen(System.currentTimeMillis());
                    peer.setAddress(senderAddress);
                    peer.setAudioPort(audioPort);
                    peer.setDisplayName(senderName);
                    if (isNew) {
                        Log.d(TAG, "Nouveau peer : " + senderName + " @ " + senderAddress.getHostAddress());
                        notifyPeersChanged();
                    }
                    break;
                }
                case MSG_BYE: {
                    if (peers.remove(senderId) != null) notifyPeersChanged();
                    break;
                }
                case MSG_CALL_REQUEST: {
                    if (selfId != null && selfId.equals(targetId)) {
                        Peer caller = peers.get(senderId);
                        if (caller != null) {
                            caller.setCalling(true);
                            mainHandler.post(() -> {
                                if (listener != null) listener.onCallRequest(caller);
                            });
                        }
                    }
                    break;
                }
                case MSG_CALL_ACCEPT: {
                    if (selfId != null && selfId.equals(targetId)) {
                        Peer callee = peers.get(senderId);
                        if (callee != null) {
                            callee.setInCall(true);
                            mainHandler.post(() -> {
                                if (listener != null) listener.onCallAccepted(callee);
                            });
                        }
                    }
                    break;
                }
                case MSG_CALL_REJECT: {
                    if (selfId != null && selfId.equals(targetId)) {
                        Peer callee = peers.get(senderId);
                        if (callee != null) {
                            callee.setCalling(false);
                            mainHandler.post(() -> {
                                if (listener != null) listener.onCallRejected(callee);
                            });
                        }
                    }
                    break;
                }
                case MSG_CALL_END: {
                    if (selfId != null && selfId.equals(targetId)) {
                        Peer other = peers.get(senderId);
                        if (other != null) {
                            other.setInCall(false);
                            other.setCalling(false);
                            mainHandler.post(() -> {
                                if (listener != null) listener.onCallEnded(other);
                            });
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Message invalide : " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // API publique
    // -------------------------------------------------------------------------

    public void sendMessage(String type, String targetId) { sendBroadcastMessage(type, targetId); }
    public void sendCallRequest(String t) { sendBroadcastMessage(MSG_CALL_REQUEST, t); }
    public void sendCallAccept(String t)  { sendBroadcastMessage(MSG_CALL_ACCEPT,  t); }
    public void sendCallReject(String t)  { sendBroadcastMessage(MSG_CALL_REJECT,  t); }
    public void sendCallEnd(String t)     { sendBroadcastMessage(MSG_CALL_END,     t); }

    public List<Peer> getPeerList() { return new ArrayList<>(peers.values()); }
    public String getSelfId()       { return selfId; }
    public String getSelfName()     { return selfName; }
    public int getSelfAudioPort()   { return selfAudioPort; }
    public void setListener(DiscoveryListener l) { this.listener = l; }

    private void notifyPeersChanged() {
        mainHandler.post(() -> {
            if (listener != null) listener.onPeersChanged(getPeerList());
        });
    }

    @Override
    public void onDestroy() {
        stopDiscovery();
        if (executor != null) executor.shutdownNow();
        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
