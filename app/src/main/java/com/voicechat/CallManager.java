package com.voicechat;

import android.util.Log;

/**
 * Singleton qui maintient l'état global de l'appel en cours.
 * Évite les problèmes de listeners perdus entre les activités.
 */
public class CallManager {

    private static final String TAG = "CallManager";
    private static CallManager instance;

    public enum State { IDLE, CALLING, RINGING, IN_CALL }

    private State  state    = State.IDLE;
    private String peerId   = null;
    private String peerName = null;
    private String peerIp   = null;
    private boolean isCaller = false;

    // Callback vers l'activité active
    private CallEventListener listener;

    public interface CallEventListener {
        void onCallAccepted(Peer by);
        void onCallRejected(Peer by);
        void onCallEnded(Peer by);
        void onIncomingCall(Peer from);
    }

    public static CallManager get() {
        if (instance == null) instance = new CallManager();
        return instance;
    }

    // -------------------------------------------------------------------------
    // Gestion d'état
    // -------------------------------------------------------------------------

    public void startOutgoing(Peer peer) {
        state    = State.CALLING;
        peerId   = peer.getId();
        peerName = peer.getDisplayName();
        peerIp   = peer.getAddress().getHostAddress();
        isCaller = true;
        Log.d(TAG, "startOutgoing → " + peerName);
    }

    public void startIncoming(Peer peer) {
        state    = State.RINGING;
        peerId   = peer.getId();
        peerName = peer.getDisplayName();
        peerIp   = peer.getAddress().getHostAddress();
        isCaller = false;
        Log.d(TAG, "startIncoming ← " + peerName);
    }

    public void setInCall() {
        state = State.IN_CALL;
        Log.d(TAG, "setInCall");
    }

    /** Remet tout à zéro — appelé après chaque fin d'appel */
    public void reset() {
        Log.d(TAG, "reset() — état IDLE");
        state    = State.IDLE;
        peerId   = null;
        peerName = null;
        peerIp   = null;
        isCaller = false;
    }

    // -------------------------------------------------------------------------
    // Accesseurs
    // -------------------------------------------------------------------------

    public State   getState()    { return state; }
    public String  getPeerId()   { return peerId; }
    public String  getPeerName() { return peerName; }
    public String  getPeerIp()   { return peerIp; }
    public boolean isCaller()    { return isCaller; }
    public boolean isIdle()      { return state == State.IDLE; }

    // -------------------------------------------------------------------------
    // Listener (l'activité courante s'enregistre ici)
    // -------------------------------------------------------------------------

    public void setListener(CallEventListener l) { this.listener = l; }

    public void notifyAccepted(Peer by) {
        Log.d(TAG, "notifyAccepted by=" + by.getDisplayName());
        if (listener != null) listener.onCallAccepted(by);
    }
    public void notifyRejected(Peer by) {
        Log.d(TAG, "notifyRejected by=" + by.getDisplayName());
        if (listener != null) listener.onCallRejected(by);
    }
    public void notifyEnded(Peer by) {
        Log.d(TAG, "notifyEnded by=" + by.getDisplayName());
        if (listener != null) listener.onCallEnded(by);
    }
    public void notifyIncoming(Peer from) {
        Log.d(TAG, "notifyIncoming from=" + from.getDisplayName());
        if (listener != null) listener.onIncomingCall(from);
    }
}

