package com.fongmi.android.tv.ui.dialog;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayerSetting;

/**
 * 播放设置弹窗：智能去广开关（过滤采集站 HLS 插播广告）。
 * 电视遥控器：菜单键呼出，D-pad 上下移动焦点，OK 切换开关。
 */
public class PlayerSettingsDialog {

    public static void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing()) return;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
        root.setPadding(pad, pad, pad, pad);

        TextView tip = new TextView(activity);
        tip.setText("智能去广：自动识别并跳过采集站视频中的插播广告切片（HLS/m3u8 有效）。关闭后完全直连不过滤。");
        tip.setTextSize(13);
        tip.setLineSpacing(0, 1.15f);
        root.addView(tip);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (activity.getResources().getDisplayMetrics().density * 18), 0, 0);

        TextView label = new TextView(activity);
        label.setText(activity.getString(R.string.player_adblock));
        label.setTextSize(16);
        row.addView(label);

        Switch sw = new Switch(activity);
        sw.setChecked(PlayerSetting.isAdFilter());
        sw.setPadding((int) (activity.getResources().getDisplayMetrics().density * 16), 0, 0, 0);
        row.addView(sw);
        root.addView(row);

        new AlertDialog.Builder(activity)
                .setTitle("播放设置")
                .setView(root)
                .setPositiveButton("保存", (d, w) -> PlayerSetting.putAdFilter(sw.isChecked()))
                .setNegativeButton("取消", null)
                .show();
    }
}
