package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Person;
import com.fongmi.android.tv.databinding.AdapterPersonBinding;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.TmdbUtil;

import java.util.ArrayList;
import java.util.List;

public class PersonAdapter extends RecyclerView.Adapter<PersonAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Person> mItems;

    public PersonAdapter(OnClickListener listener) {
        this.listener = listener;
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
        ImgUtil.load(item.getName(), profileUrl, holder.binding.avatar, false);
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final AdapterPersonBinding binding;

        public ViewHolder(@NonNull AdapterPersonBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            listener.onItemClick(mItems.get(getLayoutPosition()));
        }
    }
}
