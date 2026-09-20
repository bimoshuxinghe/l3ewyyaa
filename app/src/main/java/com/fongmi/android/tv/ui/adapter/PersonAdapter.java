package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Person;
import com.fongmi.android.tv.databinding.AdapterPersonBinding;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.TmdbUtil;

import java.util.ArrayList;
import java.util.List;

public class PersonAdapter extends RecyclerView.Adapter<PersonAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Person> mItems;

    public PersonAdapter(OnClickListener listener) {
        this.mListener = listener;
        this.mItems = new ArrayList<>();
    }

    public interface OnClickListener {
        void onItemClick(Person item);
    }

    public void clear() {
        int size = mItems.size();
        mItems.clear();
        notifyItemRangeRemoved(0, size);
    }

    public PersonAdapter addAll(List<Person> items) {
        if (items == null) return this;
        mItems.addAll(items);
        notifyItemRangeInserted(0, mItems.size());
        return this;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterPersonBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Person item = mItems.get(position);
        holder.binding.name.setText(item.getName());
        if (!item.getCharacter().isEmpty()) {
            holder.binding.character.setText("饰 " + item.getCharacter());
            holder.binding.character.setVisibility(View.VISIBLE);
        } else if (!item.getJob().isEmpty()) {
            holder.binding.character.setText(item.getJob());
            holder.binding.character.setVisibility(View.VISIBLE);
        } else {
            holder.binding.character.setVisibility(View.GONE);
        }
        String profileUrl = item.hasProfile() ? TmdbUtil.buildProfileUrl(item.getProfilePath()) : "";
        if (profileUrl == null || profileUrl.isEmpty()) {
            holder.binding.avatar.setImageResource(R.drawable.artwork);
        } else {
            // 强制圆形裁剪，并按头像实际尺寸小图解码，避免全尺寸图拉大列表滚动/进详情页的卡顿
            Glide.with(holder.binding.avatar)
                    .load(ImgUtil.getUrl(profileUrl))
                    .circleCrop()
                    .override(ResUtil.dp2px(72) * 2, ResUtil.dp2px(72) * 2)
                    .placeholder(R.drawable.artwork)
                    .error(R.drawable.artwork)
                    .into(holder.binding.avatar);
        }
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterPersonBinding binding;

        public ViewHolder(@NonNull AdapterPersonBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
