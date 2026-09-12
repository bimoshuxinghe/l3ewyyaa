package com.fongmi.android.tv.ui.activity;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.setting.PlayerSetting;

/**
 * 设置页：主界面浏览树「設置」节点点击后打开。
 * 内含「智能去广」开关（过滤片头/插播分片广告），弹窗关闭即退出本页。
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        root.setPadding(pad, pad, pad, pad);

        // 智能去广开关行
        LinearLayout adRow = new LinearLayout(this);
        adRow.setOrientation(LinearLayout.HORIZONTAL);
        adRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout adText = new LinearLayout(this);
        adText.setOrientation(LinearLayout.VERTICAL);
        TextView adTitle = new TextView(this);
        adTitle.setText("智能去广");
        adTitle.setTextSize(16);
        TextView adDesc = new TextView(this);
        adDesc.setText("过滤片头/插播分片广告，默认开启");
        adDesc.setTextSize(12);
        adDesc.setAlpha(0.6f);
        adText.addView(adTitle);
        adText.addView(adDesc);
        Switch adSwitch = new Switch(this);
        adSwitch.setChecked(PlayerSetting.isAdFilter());
        adSwitch.setOnCheckedChangeListener((b, checked) -> PlayerSetting.putAdFilter(checked));
        adRow.addView(adText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        adRow.addView(adSwitch);
        root.addView(adRow);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("設置")
                .setView(root)
                .setPositiveButton("保存", null)
                .setNegativeButton("關閉", null)
                .show();
        dialog.setOnDismissListener(d -> finish());
    }
}
