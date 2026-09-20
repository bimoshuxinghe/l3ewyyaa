package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterStillBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 详情页「剧照」横向列表：展示 TMDb/豆瓣返回的横版剧照（backdrop），
 * 点击进入大图预览页（StillActivity），可在其中将剧照设置为电视/盒子桌面壁纸。
 */
public class StillAdapter extends RecyclerView.Adapter<StillAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<String> mItems;

    public StillAdapter(OnClickListener listener) {
        this.mListener = listener;
        this.mItems = new ArrayList<>();
    }

    public interface OnClickListener {
        void onStillClick(int index);
    }

    public void clear() {
        int size = mItems.size();
        mItems.clear();
        notifyItemRangeRemoved(0, size);
    }

    public void addAll(List<String> items) {
        if (items == null || items.isEmpty()) return;
        int start = mItems.size();
        mItems.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public List<String> getItems() {
        return mItems;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterStillBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String url = mItems.get(position);
        Glide.with(holder.binding.still)
                .load(ImgUtil.getUrl(url))
                .centerCrop()
                .placeholder(R.drawable.artwork)
                .error(R.drawable.artwork)
                .into(holder.binding.still);
        holder.binding.getRoot().setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) mListener.onStillClick(pos);
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterStillBinding binding;

        ViewHolder(@NonNull AdapterStillBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
