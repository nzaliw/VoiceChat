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

public class DiscoveryService extends Service {

    private static final String TAG = "DiscoveryService";

    public static final String MULTICAST_GROUP = "239.255.42.99";
    public static final int DISCOVERY_PORT = 45678;
    private static final int ANNOUNCE_INTERVAL_MS = 3000;

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

    private MulticastSocket multicastSocket;
    private WifiManager.MulticastLock multicastLock;
    private InetAddress groupAddress;

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

        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("VoiceChatLock");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur multicast lock", e);
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
                groupAddress = InetAddress.getByName(MULTICAST_GROUP);
                multicastSocket = new MulticastSocket(DISCOVERY_PORT);
                multicastSocket.setReuseAddress(true);
                multicastSocket.joinGroup(groupAddress);
                multicastSocket.setTimeToLive(4);
                multicastSocket.setSoTimeout(5000);

                Log.d(TAG, "Discovery démarré. ID=" + selfId);

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
        try { sendMessage(MSG_BYE, null); } catch (Exception ignored) {}
        try {
            if (multicastSocket != null) {
                try { multicastSocket.leaveGroup(groupAddress); } catch (Exception ignored) {}
                multicastSocket.close();
            }
        } catch (Exception ignored) {}
    }

    private void receiveLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                if (multicastSocket == null || multicastSocket.isClosed()) break;
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                multicastSocket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                handleMessage(msg, packet.getAddress());
            } catch (java.net.SocketTimeoutException e) {
                // normal, continuer
            } catch (Exception e) {
                if (running) Log.w(TAG, "Erreur réception : " + e.getMessage());
            }
        }
    }

    private void announceLoop() {
        while (running) {
            try {
                sendMessage(MSG_ANNOUNCE, null);
                Thread.sleep(ANNOUNCE_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.w(TAG, "Erreur annonce : " + e.getMessage());
            }
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
                }
            }
            if (changed) notifyPeersChanged();
        }
    }

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
                    if (isNew) notifyPeersChanged();
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

    public void sendMessage(String type, String targetId) {
        if (!running && !MSG_BYE.equals(type)) return;
        executor.submit(() -> {
            try {
                if (multicastSocket == null || multicastSocket.isClosed()) return;
                JSONObject json = new JSONObject();
                json.put("type", type);
                json.put("id", selfId != null ? selfId : "unknown");
                json.put("name", selfName != null ? selfName : "Unknown");
                json.put("audioPort", selfAudioPort);
                if (targetId != null) json.put("targetId", targetId);

                byte[] data = json.toString().getBytes("UTF-8");
                DatagramPacket packet = new DatagramPacket(
                        data, data.length, groupAddress, DISCOVERY_PORT);
                multicastSocket.send(packet);
            } catch (Exception e) {
                Log.w(TAG, "Erreur envoi : " + e.getMessage());
            }
        });
    }

    public void sendCallRequest(String targetId) { sendMessage(MSG_CALL_REQUEST, targetId); }
    public void sendCallAccept(String targetId)  { sendMessage(MSG_CALL_ACCEPT,  targetId); }
    public void sendCallReject(String targetId)  { sendMessage(MSG_CALL_REJECT,  targetId); }
    public void sendCallEnd(String targetId)     { sendMessage(MSG_CALL_END,     targetId); }

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
        if (multicastLock != null && multicastLock.isHeld()) {
            try { multicastLock.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
