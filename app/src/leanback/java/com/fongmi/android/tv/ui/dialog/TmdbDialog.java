package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogTmdbBinding;
import com.fongmi.android.tv.impl.TmdbListener;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class TmdbDialog extends BaseAlertDialog {

    private DialogTmdbBinding binding;

    public static void show(FragmentActivity activity) {
        new TmdbDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogTmdbBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.setting_tmdb_api).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        String apiUrl = Setting.getTmdbApiUrl();
        String imageUrl = Setting.getTmdbImageUrl();
        String apiKey = Setting.getTmdbApiKey();
        if (!TextUtils.isEmpty(apiUrl)) {
            binding.apiUrl.setText(apiUrl);
            binding.apiUrl.setSelection(apiUrl.length());
        }
        if (!TextUtils.isEmpty(imageUrl)) {
            binding.imageUrl.setText(imageUrl);
            binding.imageUrl.setSelection(imageUrl.length());
        }
        if (!TextUtils.isEmpty(apiKey)) {
            binding.apiKey.setText(apiKey);
            binding.apiKey.setSelection(apiKey.length());
        }
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(this::onPositive);
        binding.negative.setOnClickListener(this::onNegative);
        binding.scan.setOnClickListener(v -> MiguLoginDialog.showQr(requireActivity(), MiguLoginDialog.TYPE_TMDB));
        binding.apiKey.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) binding.positive.performClick();
            return true;
        });
    }

    private void onPositive(View view) {
        String apiUrl = binding.apiUrl.getText().toString().trim();
        String imageUrl = binding.imageUrl.getText().toString().trim();
        String apiKey = binding.apiKey.getText().toString().trim();
        // 扫码已写入 key 而输入框为空时，保留扫码结果
        if (apiKey.isEmpty()) apiKey = Setting.getTmdbApiKey();
        ((TmdbListener) requireActivity()).setTmdbConfig(apiUrl, imageUrl, apiKey);
        dismiss();
    }

    private void onNegative(View view) {
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.7f);
    }
}
