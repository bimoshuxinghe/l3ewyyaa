package com.fongmi.android.tv.ui.adapter;

import android.os.Build;
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
        // View.setForeground 仅 API 23+（LinearLayout 无 FrameLayout 的早期实现），Android 5 跳过
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) holder.binding.card.setForeground(FocusColor.posterForeground());
        holder.binding.name.setText(item.getName());
        ImgUtil.load(item.getName(), item.getPic(), holder.binding.image);
        String remarks = item.getRemarks();
        if (!TextUtils.isEmpty(remarks)) {
            holder.binding.remarks.setText(remarks);
            holder.binding.remarks.setVisibility(View.VISIBLE);
        } else {
            holder.binding.remarks.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        private final AdapterCinemaPosterBinding binding;

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
                float scale = hasFocus ? 1.1f : 1.0f;
                itemView.animate().scaleX(scale).scaleY(scale).setDuration(200).start();
                itemView.setZ(hasFocus ? 16f : 0f);
            });
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
