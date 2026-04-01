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
import java.net.MulticastSocket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service de découverte des pairs sur le réseau local Wi-Fi.
 * Utilise le multicast UDP pour annoncer sa présence et découvrir les autres.
 *
 * Protocole :
 *  - Groupe multicast : 239.255.42.99
 *  - Port : 45678
 *  - Format des messages : JSON
 *    { "type": "ANNOUNCE|CALL_REQUEST|CALL_ACCEPT|CALL_REJECT|CALL_END|BYE",
 *      "id": "<uuid>", "name": "<displayName>",
 *      "audioPort": <port>, "targetId": "<uuid>" }
 */
public class DiscoveryService extends Service {

    private static final String TAG = "DiscoveryService";

    // Multicast
    public static final String MULTICAST_GROUP = "239.255.42.99";
    public static final int DISCOVERY_PORT = 45678;
    private static final int ANNOUNCE_INTERVAL_MS = 3000;

    // Types de messages
    public static final String MSG_ANNOUNCE       = "ANNOUNCE";
    public static final String MSG_CALL_REQUEST   = "CALL_REQUEST";
    public static final String MSG_CALL_ACCEPT    = "CALL_ACCEPT";
    public static final String MSG_CALL_REJECT    = "CALL_REJECT";
    public static final String MSG_CALL_END       = "CALL_END";
    public static final String MSG_BYE            = "BYE";

    // Notre identité
    private String selfId;
    private String selfName;
    private int selfAudioPort;

    // Liste des pairs découverts (thread-safe)
    private final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();

    // Multicast
    private MulticastSocket multicastSocket;
    private WifiManager.MulticastLock multicastLock;
    private InetAddress groupAddress;

    // Threads
    private ExecutorService executor;
    private volatile boolean running = false;
    private Handler mainHandler;

    // Callback vers l'activité principale
    private DiscoveryListener listener;

    // Binder
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public DiscoveryService getService() {
            return DiscoveryService.this;
        }
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

        // Acquérir le multicast lock
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("VoiceChatLock");
        multicastLock.setReferenceCounted(true);
        multicastLock.acquire();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /**
     * Initialise et démarre la découverte.
     */
    public void startDiscovery(String name, int audioPort) {
        if (running) return;

        selfId = UUID.randomUUID().toString();
        selfName = name;
        selfAudioPort = audioPort;
        running = true;

        try {
            groupAddress = InetAddress.getByName(MULTICAST_GROUP);
            multicastSocket = new MulticastSocket(DISCOVERY_PORT);
            multicastSocket.setReuseAddress(true);
            multicastSocket.joinGroup(groupAddress);
            multicastSocket.setTimeToLive(4);

            // Thread de réception
            executor.submit(this::receiveLoop);
            // Thread d'annonce périodique
            executor.submit(this::announceLoop);
            // Thread de nettoyage des peers inactifs
            executor.submit(this::cleanupLoop);

            Log.d(TAG, "Discovery démarré. ID=" + selfId + " name=" + selfName);
        } catch (Exception e) {
            Log.e(TAG, "Erreur démarrage discovery", e);
        }
    }

    public void stopDiscovery() {
        running = false;
        sendMessage(MSG_BYE, null);
        try {
            if (multicastSocket != null) {
                multicastSocket.leaveGroup(groupAddress);
                multicastSocket.close();
            }
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Boucles internes
    // -------------------------------------------------------------------------

    private void receiveLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                multicastSocket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                handleMessage(msg, packet.getAddress());
            } catch (Exception e) {
                if (running) Log.w(TAG, "Erreur réception : " + e.getMessage());
            }
        }
    }

    private void announceLoop() {
        while (running) {
            sendMessage(MSG_ANNOUNCE, null);
            try { Thread.sleep(ANNOUNCE_INTERVAL_MS); } catch (InterruptedException e) { break; }
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
                    Log.d(TAG, "Peer retiré (inactif) : " + entry.getValue().getDisplayName());
                }
            }
            if (changed) notifyPeersChanged();
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

            // Ignorer nos propres messages
            if (selfId.equals(senderId)) return;

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
                    if (peers.remove(senderId) != null) {
                        Log.d(TAG, "Peer parti : " + senderName);
                        notifyPeersChanged();
                    }
                    break;
                }
                case MSG_CALL_REQUEST: {
                    if (selfId.equals(targetId)) {
                        Peer caller = peers.get(senderId);
                        if (caller != null) {
                            caller.setCalling(true);
                            mainHandler.post(() -> { if (listener != null) listener.onCallRequest(caller); });
                        }
                    }
                    break;
                }
                case MSG_CALL_ACCEPT: {
                    if (selfId.equals(targetId)) {
                        Peer callee = peers.get(senderId);
                        if (callee != null) {
                            callee.setInCall(true);
                            mainHandler.post(() -> { if (listener != null) listener.onCallAccepted(callee); });
                        }
                    }
                    break;
                }
                case MSG_CALL_REJECT: {
                    if (selfId.equals(targetId)) {
                        Peer callee = peers.get(senderId);
                        if (callee != null) {
                            callee.setCalling(false);
                            mainHandler.post(() -> { if (listener != null) listener.onCallRejected(callee); });
                        }
                    }
                    break;
                }
                case MSG_CALL_END: {
                    if (selfId.equals(targetId)) {
                        Peer other = peers.get(senderId);
                        if (other != null) {
                            other.setInCall(false);
                            other.setCalling(false);
                            mainHandler.post(() -> { if (listener != null) listener.onCallEnded(other); });
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Message invalide : " + raw + " - " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Envoi de messages
    // -------------------------------------------------------------------------

    public void sendMessage(String type, String targetId) {
        executor.submit(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("type", type);
                json.put("id", selfId);
                json.put("name", selfName);
                json.put("audioPort", selfAudioPort);
                if (targetId != null) json.put("targetId", targetId);

                byte[] data = json.toString().getBytes("UTF-8");
                DatagramPacket packet = new DatagramPacket(data, data.length, groupAddress, DISCOVERY_PORT);
                if (multicastSocket != null && !multicastSocket.isClosed()) {
                    multicastSocket.send(packet);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur envoi message", e);
            }
        });
    }

    public void sendCallRequest(String targetId) { sendMessage(MSG_CALL_REQUEST, targetId); }
    public void sendCallAccept(String targetId)  { sendMessage(MSG_CALL_ACCEPT,  targetId); }
    public void sendCallReject(String targetId)  { sendMessage(MSG_CALL_REJECT,  targetId); }
    public void sendCallEnd(String targetId)     { sendMessage(MSG_CALL_END,     targetId); }

    // -------------------------------------------------------------------------
    // Accesseurs
    // -------------------------------------------------------------------------

    public List<Peer> getPeerList() { return new ArrayList<>(peers.values()); }
    public String getSelfId()       { return selfId; }
    public String getSelfName()     { return selfName; }
    public int getSelfAudioPort()   { return selfAudioPort; }

    public void setListener(DiscoveryListener listener) { this.listener = listener; }

    private void notifyPeersChanged() {
        mainHandler.post(() -> { if (listener != null) listener.onPeersChanged(getPeerList()); });
    }

    @Override
    public void onDestroy() {
        stopDiscovery();
        executor.shutdownNow();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        super.onDestroy();
    }
}
