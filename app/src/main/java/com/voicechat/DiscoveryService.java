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

/**
 * Service de découverte et signalisation.
 * Transmet les événements d'appel via CallManager (singleton).
 */
public class DiscoveryService extends Service {

    private static final String TAG = "DiscoveryService";
    public static final int DISCOVERY_PORT    = 45678;
    private static final int ANNOUNCE_INTERVAL = 2000;
    private static final int SCAN_INTERVAL     = 8000;

    public static final String MSG_ANNOUNCE     = "ANNOUNCE";
    public static final String MSG_CALL_REQUEST = "CALL_REQUEST";
    public static final String MSG_CALL_ACCEPT  = "CALL_ACCEPT";
    public static final String MSG_CALL_REJECT  = "CALL_REJECT";
    public static final String MSG_CALL_END     = "CALL_END";
    public static final String MSG_BYE          = "BYE";

    private String selfId, selfName;
    private int    selfAudioPort;
    private String myIp;

    private final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();

    private DatagramSocket sendSocket, recvSocket;
    private WifiManager.WifiLock wifiLock;
    private ExecutorService executor;
    private volatile boolean running = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final IBinder binder = new LocalBinder();

    // Listener simple pour la liste des pairs (UI)
    private PeersListener peersListener;
    public interface PeersListener {
        void onPeersChanged(List<Peer> peers);
    }

    public class LocalBinder extends Binder {
        public DiscoveryService getService() { return DiscoveryService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newFixedThreadPool(5);
        acquireWifiLock();
    }

    @Override public IBinder onBind(Intent i)  { return binder; }
    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }

    public void startDiscovery(String name, int audioPort) {
        if (running) return;
        selfId        = UUID.randomUUID().toString();
        selfName      = name;
        selfAudioPort = audioPort;
        running       = true;

        executor.submit(() -> {
            try {
                sendSocket = new DatagramSocket();
                sendSocket.setBroadcast(true);

                recvSocket = new DatagramSocket(null);
                recvSocket.setReuseAddress(true);
                recvSocket.setBroadcast(true);
                recvSocket.bind(new InetSocketAddress(DISCOVERY_PORT));
                recvSocket.setSoTimeout(2000);

                myIp = getMyIp();
                Log.d(TAG, "Discovery démarré id=" + selfId + " ip=" + myIp);

                executor.submit(this::receiveLoop);
                executor.submit(this::announceLoop);
                executor.submit(this::scanLoop);
                executor.submit(this::cleanupLoop);
            } catch (Exception e) {
                Log.e(TAG, "Erreur démarrage", e);
                running = false;
            }
        });
    }

    public void stopDiscovery() {
        running = false;
        sendDirect(MSG_BYE, null, null);
        closeSocket(sendSocket);
        closeSocket(recvSocket);
    }

    // -------------------------------------------------------------------------
    // Boucles
    // -------------------------------------------------------------------------

    private void receiveLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                recvSocket.receive(pkt);
                handleMessage(new String(pkt.getData(), 0, pkt.getLength(), "UTF-8"),
                        pkt.getAddress());
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Exception e) { if (running) Log.w(TAG, "recv: " + e.getMessage()); }
        }
    }

    private void announceLoop() {
        while (running) {
            broadcast(MSG_ANNOUNCE, null);
            try { Thread.sleep(ANNOUNCE_INTERVAL); } catch (InterruptedException e) { break; }
        }
    }

    private void scanLoop() {
        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        while (running) {
            scanSubnet();
            try { Thread.sleep(SCAN_INTERVAL); } catch (InterruptedException e) { break; }
        }
    }

    private void scanSubnet() {
        if (myIp == null) myIp = getMyIp();
        if (myIp == null) return;
        String prefix = myIp.substring(0, myIp.lastIndexOf('.') + 1);
        try {
            byte[] data = buildJson(MSG_ANNOUNCE, null).getBytes("UTF-8");
            for (int i = 1; i <= 254 && running; i++) {
                String ip = prefix + i;
                if (ip.equals(myIp)) continue;
                try {
                    sendSocket.send(new DatagramPacket(data, data.length,
                            InetAddress.getByName(ip), DISCOVERY_PORT));
                } catch (Exception ignored) {}
                if (i % 50 == 0) try { Thread.sleep(20); } catch (InterruptedException e) { break; }
            }
        } catch (Exception e) { Log.w(TAG, "scan: " + e.getMessage()); }
    }

    private void cleanupLoop() {
        while (running) {
            try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
            boolean changed = false;
            Iterator<Map.Entry<String, Peer>> it = peers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Peer> e = it.next();
                if (!e.getValue().isActive()) { it.remove(); changed = true; }
            }
            if (changed) notifyPeers();
        }
    }

    // -------------------------------------------------------------------------
    // Traitement des messages reçus
    // -------------------------------------------------------------------------

    private void handleMessage(String raw, InetAddress from) {
        try {
            JSONObject j      = new JSONObject(raw);
            String type       = j.getString("type");
            String senderId   = j.getString("id");
            if (selfId != null && selfId.equals(senderId)) return;

            String senderName = j.optString("name", "Inconnu");
            int    audioPort  = j.optInt("audioPort", 0);
            String targetId   = j.optString("targetId", "");

            switch (type) {
                case MSG_ANNOUNCE: {
                    boolean isNew = !peers.containsKey(senderId);
                    Peer p = peers.computeIfAbsent(senderId,
                            k -> new Peer(senderId, senderName, from, audioPort));
                    p.setLastSeen(System.currentTimeMillis());
                    p.setAddress(from);
                    p.setAudioPort(audioPort);
                    p.setDisplayName(senderName);
                    if (isNew) { Log.d(TAG, "Nouveau: " + senderName); notifyPeers(); }
                    break;
                }
                case MSG_BYE:
                    if (peers.remove(senderId) != null) notifyPeers();
                    break;

                case MSG_CALL_REQUEST:
                    if (selfId.equals(targetId)) {
                        Peer caller = peers.get(senderId);
                        if (caller != null) {
                            caller.setCalling(true);
                            mainHandler.post(() -> CallManager.get().notifyIncoming(caller));
                        }
                    }
                    break;

                case MSG_CALL_ACCEPT:
                    if (selfId.equals(targetId)) {
                        Peer callee = peers.get(senderId);
                        if (callee == null) {
                            // Créer le peer si inconnu (peut arriver)
                            callee = new Peer(senderId, senderName, from, audioPort);
                            peers.put(senderId, callee);
                        }
                        callee.setInCall(true);
                        final Peer finalCallee = callee;
                        mainHandler.post(() -> CallManager.get().notifyAccepted(finalCallee));
                    }
                    break;

                case MSG_CALL_REJECT:
                    if (selfId.equals(targetId)) {
                        Peer p = peers.get(senderId);
                        if (p != null) {
                            p.setCalling(false);
                            mainHandler.post(() -> CallManager.get().notifyRejected(p));
                        }
                    }
                    break;

                case MSG_CALL_END:
                    if (selfId.equals(targetId)) {
                        Peer p = peers.get(senderId);
                        if (p != null) {
                            p.setInCall(false); p.setCalling(false);
                            mainHandler.post(() -> CallManager.get().notifyEnded(p));
                        }
                    }
                    break;
            }
        } catch (Exception e) { Log.w(TAG, "msg: " + e.getMessage()); }
    }

    // -------------------------------------------------------------------------
    // Envoi
    // -------------------------------------------------------------------------

    public void sendCallRequest(String targetId) {
        Peer p = peers.get(targetId);
        sendDirect(MSG_CALL_REQUEST, targetId, p != null ? p.getAddress().getHostAddress() : null);
    }
    public void sendCallAccept(String targetId) {
        Peer p = peers.get(targetId);
        sendDirect(MSG_CALL_ACCEPT, targetId, p != null ? p.getAddress().getHostAddress() : null);
    }
    public void sendCallReject(String targetId) {
        Peer p = peers.get(targetId);
        sendDirect(MSG_CALL_REJECT, targetId, p != null ? p.getAddress().getHostAddress() : null);
    }
    public void sendCallEnd(String targetId) {
        Peer p = peers.get(targetId);
        sendDirect(MSG_CALL_END, targetId, p != null ? p.getAddress().getHostAddress() : null);
    }

    private void sendDirect(String type, String targetId, String directIp) {
        executor.submit(() -> {
            try {
                if (sendSocket == null || sendSocket.isClosed()) return;
                byte[] data = buildJson(type, targetId).getBytes("UTF-8");
                // Envoi direct si IP connue
                if (directIp != null) {
                    sendSocket.send(new DatagramPacket(data, data.length,
                            InetAddress.getByName(directIp), DISCOVERY_PORT));
                }
                // + broadcast pour fiabilité
                broadcast(type, targetId);
            } catch (Exception e) { Log.w(TAG, "sendDirect: " + e.getMessage()); }
        });
    }

    private void broadcast(String type, String targetId) {
        try {
            if (sendSocket == null || sendSocket.isClosed()) return;
            byte[] data = buildJson(type, targetId).getBytes("UTF-8");
            sendSocket.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
            String subnet = getSubnetBroadcast();
            if (subnet != null && !subnet.equals("255.255.255.255")) {
                sendSocket.send(new DatagramPacket(data, data.length,
                        InetAddress.getByName(subnet), DISCOVERY_PORT));
            }
        } catch (Exception e) { Log.v(TAG, "broadcast: " + e.getMessage()); }
    }

    private String buildJson(String type, String targetId) throws Exception {
        JSONObject j = new JSONObject();
        j.put("type", type);
        j.put("id",   selfId   != null ? selfId   : "x");
        j.put("name", selfName != null ? selfName : "?");
        j.put("audioPort", selfAudioPort);
        if (targetId != null) j.put("targetId", targetId);
        return j.toString();
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    private String getMyIp() {
        try {
            WifiManager w = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = w.getConnectionInfo().getIpAddress();
            if (ip == 0) return null;
            return String.format("%d.%d.%d.%d",
                    ip & 0xff, ip >> 8 & 0xff, ip >> 16 & 0xff, ip >> 24 & 0xff);
        } catch (Exception e) { return null; }
    }

    private String getSubnetBroadcast() {
        try {
            WifiManager w = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = w.getConnectionInfo().getIpAddress();
            int mask = w.getDhcpInfo().netmask;
            if (mask == 0) mask = 0x00FFFFFF;
            int bc = (ip & mask) | ~mask;
            return String.format("%d.%d.%d.%d",
                    bc & 0xff, bc >> 8 & 0xff, bc >> 16 & 0xff, bc >> 24 & 0xff);
        } catch (Exception e) { return null; }
    }

    private void acquireWifiLock() {
        try {
            WifiManager w = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = w.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VoiceChat");
            wifiLock.acquire();
        } catch (Exception ignored) {}
    }

    private void closeSocket(DatagramSocket s) {
        if (s != null && !s.isClosed()) try { s.close(); } catch (Exception ignored) {} }

    public void setPeersListener(PeersListener l) { this.peersListener = l; }
    private void notifyPeers() {
        mainHandler.post(() -> { if (peersListener != null) peersListener.onPeersChanged(getPeerList()); });
    }

    public List<Peer> getPeerList()  { return new ArrayList<>(peers.values()); }
    public String getSelfId()        { return selfId; }
    public String getSelfName()      { return selfName; }
    public int getSelfAudioPort()    { return selfAudioPort; }

    @Override
    public void onDestroy() {
        stopDiscovery();
        if (executor != null) executor.shutdownNow();
        if (wifiLock != null && wifiLock.isHeld()) try { wifiLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
