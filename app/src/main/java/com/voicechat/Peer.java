package com.voicechat;

import java.net.InetAddress;

/**
 * Représente un utilisateur découvert sur le réseau local.
 */
public class Peer {
    private String id;         // UUID unique de l'appareil
    private String displayName; // Nom affiché (nom d'appareil)
    private InetAddress address; // Adresse IP sur le réseau local
    private int audioPort;     // Port UDP pour la réception audio
    private long lastSeen;     // Timestamp de la dernière annonce reçue
    private boolean calling;   // En train d'appeler ce peer
    private boolean inCall;    // En communication avec ce peer

    public Peer(String id, String displayName, InetAddress address, int audioPort) {
        this.id = id;
        this.displayName = displayName;
        this.address = address;
        this.audioPort = audioPort;
        this.lastSeen = System.currentTimeMillis();
        this.calling = false;
        this.inCall = false;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public InetAddress getAddress() { return address; }
    public int getAudioPort() { return audioPort; }
    public long getLastSeen() { return lastSeen; }
    public boolean isCalling() { return calling; }
    public boolean isInCall() { return inCall; }

    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    public void setAddress(InetAddress address) { this.address = address; }
    public void setAudioPort(int audioPort) { this.audioPort = audioPort; }
    public void setCalling(boolean calling) { this.calling = calling; }
    public void setInCall(boolean inCall) { this.inCall = inCall; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    /**
     * Un peer est considéré actif s'il a envoyé une annonce dans les 10 dernières secondes.
     */
    public boolean isActive() {
        return (System.currentTimeMillis() - lastSeen) < 10_000;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Peer)) return false;
        Peer peer = (Peer) o;
        return id.equals(peer.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Peer{id='" + id + "', name='" + displayName + "', ip=" + address.getHostAddress() + "}";
    }
}
