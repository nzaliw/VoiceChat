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
    public static final int DISCOVERY_PORT = 45678;
    private static final int ANNOUNCE_INTERVAL_MS = 2000;
    private static final int SCAN_INTERVAL_MS = 8000;

    public static final String MSG_ANNOUNCE     = "ANNOUNCE";
    public static final String MSG_CALL_REQUEST = "CALL_REQUEST";
    public static final String MSG_CALL_ACCEPT  = "CALL_ACCEPT";
    public static final String MSG_CALL_REJECT  = "CALL_REJECT";
    public static final String MSG_CALL_END     = "CALL_END";
    public static final String MSG_BYE          = "BYE";

    private String selfId;
    private String selfName;
    private int selfAudioPort;
    private String myIpAddress;

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
        executor = Executors.newFixedThreadPool(5);
        acquireWifiLock();
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
                sendSocket = new DatagramSocket();
                sendSocket.setBroadcast(true);
                sendSocket.setSoTimeout(500);

                recvSocket = new DatagramSocket(null);
                recvSocket.setReuseAddress(true);
                recvSocket.setBroadcast(true);
                recvSocket.bind(new InetSocketAddress(DISCOVERY_PORT));
                recvSocket.setSoTimeout(2000);

                myIpAddress = getMyIpAddress();
                Log.d(TAG, "Discovery démarré. IP=" + myIpAddress + " name=" + selfName);

                executor.submit(this::receiveLoop);
                executor.submit(this::announceLoop);
                executor.submit(this::scanLoop);
                executor.submit(this::cleanupLoop);

            } catch (Exception e) {
                Log.e(TAG, "Erreur démarrage discovery", e);
                running = false;
            }
        });
    }

    public void stopDiscovery() {
        running = false;
        sendToAll(MSG_BYE, null, null);
        closeSocket(sendSocket);
        closeSocket(recvSocket);
    }

    private void receiveLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                if (recvSocket == null || recvSocket.isClosed()) break;
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                recvSocket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                handleMessage(msg, packet.getAddress());
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (running) Log.w(TAG, "Erreur réception : " + e.getMessage());
            }
        }
    }

    private void announceLoop() {
        while (running) {
            sendToAll(MSG_ANNOUNCE, null, null);
            try { Thread.sleep(ANNOUNCE_INTERVAL_MS); }
            catch (InterruptedException e) { break; }
        }
    }

    private void scanLoop() {
        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        while (running) {
            scanSubnet();
            try { Thread.sleep(SCAN_INTERVAL_MS); }
            catch (InterruptedException e) { break; }
        }
    }

    private void scanSubnet() {
        if (myIpAddress == null) myIpAddress = getMyIpAddress();
        if (myIpAddress == null) return;
        String prefix = getSubnetPrefix(myIpAddress);
        if (prefix == null) return;
        try {
            JSONObject json = buildMessage(MSG_ANNOUNCE, null);
            byte[] data = json.toString().getBytes("UTF-8");
            for (int i = 1; i <= 254 && running; i++) {
                String targetIp = prefix + i;
                if (targetIp.equals(myIpAddress)) continue;
                try {
                    InetAddress addr = InetAddress.getByName(targetIp);
                    DatagramPacket pkt = new DatagramPacket(data, data.length, addr, DISCOVERY_PORT);
                    if (sendSocket != null && !sendSocket.isClosed()) sendSocket.send(pkt);
                } catch (Exception ignored) {}
                if (i % 50 == 0) try { Thread.sleep(20); } catch (InterruptedException e) { break; }
            }
        } catch (Exception e) {
            Log.w(TAG, "Erreur scan : " + e.getMessage());
        }
    }

    private void cleanupLoop() {
        while (running) {
            try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
            boolean changed = false;
            Iterator<Map.Entry<String, Peer>> it = peers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Peer> entry = it.next();
                if (!entry.getValue().isActive()) { it.remove(); changed = true; }
            }
            if (changed) notifyPeersChanged();
        }
    }

    // -------------------------------------------------------------------------
    // Envoi — DIRECT vers le pair connu, broadcast en fallback
    // -------------------------------------------------------------------------

    /**
     * Envoie un message. Si peerIp est fourni, envoie DIRECTEMENT à cette IP.
     * Sinon envoie en broadcast sur tout le réseau.
     */
    private void sendToAll(String type, String targetId, String peerIp) {
        executor.submit(() -> {
            try {
                if (sendSocket == null || sendSocket.isClosed()) return;
                JSONObject json = buildMessage(type, targetId);
                byte[] data = json.toString().getBytes("UTF-8");

                if (peerIp != null) {
                    // Envoi direct : ultra-rapide, pas de délai broadcast
                    sendBytesTo(data, peerIp);
                    // Aussi en broadcast pour fiabilité
                    sendBytesTo(data, "255.255.255.255");
                    String subnetBcast = getSubnetBroadcast();
                    if (subnetBcast != null) sendBytesTo(data, subnetBcast);
                } else {
                    sendBytesTo(data, "255.255.255.255");
                    String subnetBcast = getSubnetBroadcast();
                    if (subnetBcast != null) sendBytesTo(data, subnetBcast);
                }
            } catch (Exception e) {
                Log.w(TAG, "Erreur sendToAll : " + e.getMessage());
            }
        });
    }

    private void sendBytesTo(byte[] data, String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            DatagramPacket pkt = new DatagramPacket(data, data.length, addr, DISCOVERY_PORT);
            sendSocket.send(pkt);
        } catch (Exception e) {
            Log.v(TAG, "Envoi échoué vers " + ip);
        }
    }

    private JSONObject buildMessage(String type, String targetId) throws Exception {
        JSONObject json = new JSONObject();
        json.put("type", type);
        json.put("id", selfId != null ? selfId : "unknown");
        json.put("name", selfName != null ? selfName : "Unknown");
        json.put("audioPort", selfAudioPort);
        if (targetId != null) json.put("targetId", targetId);
        return json;
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
                    if (isNew) {
                        Log.d(TAG, "Nouveau peer : " + senderName + " @ " + senderAddress.getHostAddress());
                        notifyPeersChanged();
                    }
                    break;
                }
                case MSG_BYE:
                    if (peers.remove(senderId) != null) notifyPeersChanged();
                    break;
                case MSG_CALL_REQUEST:
                    if (selfId != null && selfId.equals(targetId)) {
                        Peer caller = peers.get(senderId);
                        if (caller != null) {
                            caller.setCalling(true);
                            mainHandler.post(() -> { if (listener != null) listener.onCallRequest(caller); });
                        }
                    }
                    break;
                case MSG_CALL_ACCEPT:
                    if (selfId != null && selfId.equals(targetId)) {
                        Peer callee = peers.get(senderId);
                        if (callee != null) {
                            callee.setInCall(true);
                            mainHandler.post(() -> { if (listener != null) listener.onCallAccepted(callee); });
                        }
                    }
                    break;
                case MSG_CALL_REJECT:
                    if (selfId != null && selfId.equals(targetId)) {
                        Peer callee = peers.get(senderId);
                        if (callee != null) {
                            callee.setCalling(false);
                            mainHandler.post(() -> { if (listener != null) listener.onCallRejected(callee); });
                        }
                    }
                    break;
                case MSG_CALL_END:
                    if (selfId != null && selfId.equals(targetId)) {
                        Peer other = peers.get(senderId);
                        if (other != null) {
                            other.setInCall(false); other.setCalling(false);
                            mainHandler.post(() -> { if (listener != null) listener.onCallEnded(other); });
                        }
                    }
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Message invalide : " + e.getMessage());
        }
    }

    // API publique — envoi direct vers le pair connu
    public void sendCallRequest(String targetId) {
        Peer p = peers.get(targetId);
        sendToAll(MSG_CALL_REQUEST, targetId, p != null ? p.getAddress().getHostAddress() : null);
    }
    public void sendCallAccept(String targetId) {
        Peer p = peers.get(targetId);
        sendToAll(MSG_CALL_ACCEPT, targetId, p != null ? p.getAddress().getHostAddress() : null);
    }
    public void sendCallReject(String targetId) {
        Peer p = peers.get(targetId);
        sendToAll(MSG_CALL_REJECT, targetId, p != null ? p.getAddress().getHostAddress() : null);
    }
    public void sendCallEnd(String targetId) {
        Peer p = peers.get(targetId);
        sendToAll(MSG_CALL_END, targetId, p != null ? p.getAddress().getHostAddress() : null);
    }
    public void sendMessage(String type, String targetId) { sendToAll(type, targetId, null); }

    public List<Peer> getPeerList()  { return new ArrayList<>(peers.values()); }
    public String getSelfId()        { return selfId; }
    public String getSelfName()      { return selfName; }
    public int getSelfAudioPort()    { return selfAudioPort; }
    public void setListener(DiscoveryListener l) { this.listener = l; }

    private void notifyPeersChanged() {
        mainHandler.post(() -> { if (listener != null) listener.onPeersChanged(getPeerList()); });
    }

    private String getMyIpAddress() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi == null) return null;
            int ipInt = wifi.getConnectionInfo().getIpAddress();
            if (ipInt == 0) return null;
            return String.format("%d.%d.%d.%d",
                    (ipInt & 0xff), (ipInt >> 8 & 0xff), (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
        } catch (Exception e) { return null; }
    }

    private String getSubnetPrefix(String ip) {
        try {
            int lastDot = ip.lastIndexOf('.');
            return lastDot < 0 ? null : ip.substring(0, lastDot + 1);
        } catch (Exception e) { return null; }
    }

    private String getSubnetBroadcast() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi == null) return null;
            int ipInt   = wifi.getConnectionInfo().getIpAddress();
            int maskInt = wifi.getDhcpInfo().netmask;
            if (maskInt == 0) maskInt = 0x00FFFFFF;
            int broadcast = (ipInt & maskInt) | ~maskInt;
            return String.format("%d.%d.%d.%d",
                    (broadcast & 0xff), (broadcast >> 8 & 0xff),
                    (broadcast >> 16 & 0xff), (broadcast >> 24 & 0xff));
        } catch (Exception e) { return null; }
    }

    private void acquireWifiLock() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VoiceChatWifi");
                wifiLock.acquire();
            }
        } catch (Exception e) { Log.w(TAG, "WifiLock non disponible"); }
    }

    private void closeSocket(DatagramSocket s) {
        if (s != null && !s.isClosed()) try { s.close(); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopDiscovery();
        if (executor != null) executor.shutdownNow();
        if (wifiLock != null && wifiLock.isHeld()) try { wifiLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
