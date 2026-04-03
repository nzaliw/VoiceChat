package com.voicechat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.PeerViewHolder> {

    public interface OnPeerClickListener { void onPeerClick(Peer peer); }
    public interface OnPeerVideoClickListener { void onPeerVideoClick(Peer peer); }

    private List<Peer> peers = new ArrayList<>();
    private final OnPeerClickListener      audioListener;
    private final OnPeerVideoClickListener videoListener;

    public PeerAdapter(OnPeerClickListener audio) {
        this.audioListener = audio;
        this.videoListener = null;
    }

    public PeerAdapter(OnPeerClickListener audio, OnPeerVideoClickListener video) {
        this.audioListener = audio;
        this.videoListener = video;
    }

    public void updatePeers(List<Peer> newPeers) {
        this.peers = new ArrayList<>(newPeers);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public PeerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_peer, parent, false);
        return new PeerViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull PeerViewHolder h, int position) {
        h.bind(peers.get(position), audioListener, videoListener);
    }

    @Override public int getItemCount() { return peers.size(); }

    static class PeerViewHolder extends RecyclerView.ViewHolder {
        private final TextView  tvName, tvIp, tvStatus;
        private final View      statusDot;
        private final ImageView btnAudio, btnVideo;

        PeerViewHolder(@NonNull View v) {
            super(v);
            tvName    = v.findViewById(R.id.tv_peer_name);
            tvIp      = v.findViewById(R.id.tv_peer_ip);
            tvStatus  = v.findViewById(R.id.tv_peer_status);
            statusDot = v.findViewById(R.id.view_status_dot);
            btnAudio  = v.findViewById(R.id.btn_call_audio);
            btnVideo  = v.findViewById(R.id.btn_call_video);
        }

        void bind(Peer p, OnPeerClickListener audio, OnPeerVideoClickListener video) {
            tvName.setText(p.getDisplayName());
            tvIp.setText(p.getAddress().getHostAddress());

            if (p.isInCall()) {
                tvStatus.setText("En communication");
                statusDot.setBackgroundResource(R.drawable.dot_busy);
                if (btnAudio != null) { btnAudio.setEnabled(false); btnAudio.setAlpha(0.3f); }
                if (btnVideo != null) { btnVideo.setEnabled(false); btnVideo.setAlpha(0.3f); }
            } else if (p.isCalling()) {
                tvStatus.setText("Appel entrant...");
                statusDot.setBackgroundResource(R.drawable.dot_calling);
                if (btnAudio != null) { btnAudio.setEnabled(false); btnAudio.setAlpha(0.5f); }
                if (btnVideo != null) { btnVideo.setEnabled(false); btnVideo.setAlpha(0.5f); }
            } else {
                tvStatus.setText("Disponible");
                statusDot.setBackgroundResource(R.drawable.dot_available);
                if (btnAudio != null) {
                    btnAudio.setEnabled(true); btnAudio.setAlpha(1f);
                    btnAudio.setOnClickListener(v -> { if (audio != null) audio.onPeerClick(p); });
                }
                if (btnVideo != null) {
                    if (video != null) {
                        btnVideo.setVisibility(View.VISIBLE);
                        btnVideo.setEnabled(true); btnVideo.setAlpha(1f);
                        btnVideo.setOnClickListener(v -> video.onPeerVideoClick(p));
                    } else {
                        btnVideo.setVisibility(View.GONE);
                    }
                }
            }
        }
    }
}
