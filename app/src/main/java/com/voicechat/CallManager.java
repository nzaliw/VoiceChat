package com.voicechat;

import android.util.Log;

/**
 * Singleton global de gestion d'état des appels.
 * Garantit le reset complet entre chaque appel.
 */
public class CallManager {

    private static final String TAG = "CallManager";
    private static final CallManager INSTANCE = new CallManager();

    public enum State { IDLE, CALLING, RINGING, IN_CALL }

    private State   state    = State.IDLE;
    private String  peerId   = null;
    private String  peerName = null;
    private String  peerIp   = null;
    private boolean isCaller = false;

    private CallEventListener listener;

    public interface CallEventListener {
        void onCallAccepted(Peer by);
        void onCallRejected(Peer by);
        void onCallEnded(Peer by);
        void onIncomingCall(Peer from);
    }

    public static CallManager get() { return INSTANCE; }
    private CallManager() {}

    // -------------------------------------------------------------------------
    // État
    // -------------------------------------------------------------------------

    public void startOutgoing(Peer p) {
        state    = State.CALLING;
        peerId   = p.getId();
        peerName = p.getDisplayName();
        peerIp   = p.getAddress().getHostAddress();
        isCaller = true;
        Log.d(TAG, "startOutgoing → " + peerName);
    }

    public void startIncoming(Peer p) {
        state    = State.RINGING;
        peerId   = p.getId();
        peerName = p.getDisplayName();
        peerIp   = p.getAddress().getHostAddress();
        isCaller = false;
        Log.d(TAG, "startIncoming ← " + peerName);
    }

    public void setInCall() {
        state = State.IN_CALL;
        Log.d(TAG, "IN_CALL");
    }

    /** Remet TOUT à zéro — doit être appelé après chaque fin d'appel */
    public void reset() {
        Log.d(TAG, "reset() → IDLE");
        state    = State.IDLE;
        peerId   = null;
        peerName = null;
        peerIp   = null;
        isCaller = false;
        // NE PAS effacer le listener ici !
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
    // Listener — seule l'activité active s'enregistre
    // -------------------------------------------------------------------------

    public void setListener(CallEventListener l) {
        Log.d(TAG, "setListener → " + (l != null ? l.getClass().getSimpleName() : "null"));
        this.listener = l;
    }

    public void notifyAccepted(Peer by) {
        Log.d(TAG, "notifyAccepted → " + by.getDisplayName()
                + " listener=" + (listener != null ? listener.getClass().getSimpleName() : "NULL"));
        if (listener != null) listener.onCallAccepted(by);
    }

    public void notifyRejected(Peer by) {
        Log.d(TAG, "notifyRejected → " + by.getDisplayName());
        if (listener != null) listener.onCallRejected(by);
    }

    public void notifyEnded(Peer by) {
        Log.d(TAG, "notifyEnded → " + by.getDisplayName()
                + " listener=" + (listener != null ? listener.getClass().getSimpleName() : "NULL"));
        if (listener != null) listener.onCallEnded(by);
    }

    public void notifyIncoming(Peer from) {
        Log.d(TAG, "notifyIncoming ← " + from.getDisplayName()
                + " listener=" + (listener != null ? listener.getClass().getSimpleName() : "NULL"));
        if (listener != null) listener.onIncomingCall(from);
    }
}
