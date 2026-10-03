package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterCinemaPosterBinding;
import com.fongmi.android.tv.utils.FocusColor;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

public class CinemaPosterAdapter extends RecyclerView.Adapter<CinemaPosterAdapter.ViewHolder> {

    /** 非聚焦卡片透明度：压暗以突出当前焦点，对应 QuickTVUI 的层次感 */
    private static final float DIM_ALPHA = 0.45f;

    private final OnClickListener listener;
    private final List<Vod> items = new ArrayList<>();

    public interface OnClickListener {
        void onItemClick(Vod item);

        boolean onLongClick(Vod item);
    }

    public CinemaPosterAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<Vod> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public Vod getItem(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterCinemaPosterBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Vod item = items.get(position);
        holder.binding.card.setForeground(FocusColor.posterForeground());
        holder.binding.name.setText(item.getName());
        ImgUtil.load(item.getName(), item.getPic(), holder.binding.image);
        holder.bindRemarks(item.getRemarks());
        // ViewHolder 复用时必须复位焦点态残留的透明度/缩放，否则会出现"部分卡片偏暗"
        holder.resetFocusState();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        private final AdapterCinemaPosterBinding binding;
        private String remarks = "";

        ViewHolder(AdapterCinemaPosterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnClickListener(this);
            itemView.setOnLongClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position >= 0 && position < items.size() && listener != null) {
                    return listener.onLongClick(items.get(position));
                }
                return false;
            });
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                // QuickTVUI 观感核心：聚焦项浮起并提亮，其余压暗形成主次
                float scale = hasFocus ? 1.06f : 1.0f;
                itemView.animate().scaleX(scale).scaleY(scale).setDuration(200).start();
                itemView.setAlpha(hasFocus ? 1.0f : DIM_ALPHA);
                itemView.setZ(hasFocus ? 16f : 0f);
                // 角标仅在聚焦时出现，静止画面更干净
                binding.remarks.setVisibility(hasFocus && !TextUtils.isEmpty(remarks) ? View.VISIBLE : View.GONE);
            });
        }

        void bindRemarks(String text) {
            remarks = text == null ? "" : text;
            binding.remarks.setText(remarks);
        }

        /** 复位焦点动画与透明度，避免复用时残留上一次的状态 */
        void resetFocusState() {
            itemView.animate().cancel();
            itemView.setScaleX(1.0f);
            itemView.setScaleY(1.0f);
            itemView.setAlpha(DIM_ALPHA);
            itemView.setZ(0f);
            binding.remarks.setVisibility(View.GONE);
        }

        @Override
        public void onClick(View v) {
            int position = getBindingAdapterPosition();
            if (position >= 0 && position < items.size() && listener != null) {
                listener.onItemClick(items.get(position));
            }
        }
    }
}
