package com.voicechat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptateur RecyclerView pour afficher la liste des pairs disponibles.
 */
public class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.PeerViewHolder> {

    public interface OnPeerClickListener {
        void onPeerClick(Peer peer);
    }

    private List<Peer> peers = new ArrayList<>();
    private final OnPeerClickListener listener;

    public PeerAdapter(OnPeerClickListener listener) {
        this.listener = listener;
    }

    public void updatePeers(List<Peer> newPeers) {
        this.peers = new ArrayList<>(newPeers);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PeerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_peer, parent, false);
        return new PeerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PeerViewHolder holder, int position) {
        Peer peer = peers.get(position);
        holder.bind(peer, listener);
    }

    @Override
    public int getItemCount() { return peers.size(); }

    static class PeerViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvName;
        private final TextView tvIp;
        private final TextView tvStatus;
        private final View statusDot;

        public PeerViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName   = itemView.findViewById(R.id.tv_peer_name);
            tvIp     = itemView.findViewById(R.id.tv_peer_ip);
            tvStatus = itemView.findViewById(R.id.tv_peer_status);
            statusDot = itemView.findViewById(R.id.view_status_dot);
        }

        public void bind(Peer peer, OnPeerClickListener listener) {
            tvName.setText(peer.getDisplayName());
            tvIp.setText(peer.getAddress().getHostAddress());

            if (peer.isInCall()) {
                tvStatus.setText("En communication");
                statusDot.setBackgroundResource(R.drawable.dot_busy);
                itemView.setEnabled(false);
                itemView.setAlpha(0.5f);
            } else if (peer.isCalling()) {
                tvStatus.setText("Appel entrant...");
                statusDot.setBackgroundResource(R.drawable.dot_calling);
                itemView.setEnabled(false);
                itemView.setAlpha(0.8f);
            } else {
                tvStatus.setText("Disponible");
                statusDot.setBackgroundResource(R.drawable.dot_available);
                itemView.setEnabled(true);
                itemView.setAlpha(1.0f);
                itemView.setOnClickListener(v -> listener.onPeerClick(peer));
            }
        }
    }
}
