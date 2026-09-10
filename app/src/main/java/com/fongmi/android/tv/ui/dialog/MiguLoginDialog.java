package com.fongmi.android.tv.ui.dialog;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

/**
 * 咪咕账号登录弹窗：输入 UID + Token（从咪咕网页端登录后获取）。
 * 保存到 Prefers（migu_uid / migu_token），内置 Python 代理取流时自动携带账号（蓝光1080p）。
 */
public class MiguLoginDialog {

    /** 本次进程是否已弹过（未配置账号时只在打开直播后提示一次，避免骚扰）。 */
    private static volatile boolean shownOnce = false;

    public static void showIfNeeded(FragmentActivity activity) {
        if (shownOnce) return;
        String uid = Prefers.getString("migu_uid", "");
        String token = Prefers.getString("migu_token", "");
        if (!uid.isEmpty() && !token.isEmpty()) return; // 已配置账号，不再提示
        shownOnce = true;
        show(activity);
    }

    public static void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing()) return;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
        root.setPadding(pad, pad, pad, pad);

        TextView tip = new TextView(activity);
        tip.setText("咪咕账号登录（可选）：登录后自动切换蓝光1080p，非会员自动降回高清。\nUID 和 Token 在咪咕网页版(miguvideo.com)登录后，从浏览器开发者工具抓取。");
        tip.setTextSize(13);
        tip.setLineSpacing(0, 1.15f);
        root.addView(tip);

        EditText uidInput = new EditText(activity);
        uidInput.setHint("咪咕 UID");
        uidInput.setInputType(InputType.TYPE_CLASS_TEXT);
        uidInput.setText(Prefers.getString("migu_uid", ""));
        uidInput.setTypeface(Typeface.DEFAULT);
        root.addView(uidInput, marginParams(activity, 0, 12, 0, 4));

        EditText tokenInput = new EditText(activity);
        tokenInput.setHint("咪咕 Token");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT);
        tokenInput.setText(Prefers.getString("migu_token", ""));
        tokenInput.setTypeface(Typeface.DEFAULT);
        root.addView(tokenInput, marginParams(activity, 0, 4, 0, 0));

        new AlertDialog.Builder(activity)
                .setTitle("咪咕账号登录")
                .setView(root)
                .setPositiveButton("保存并播放", (d, w) -> {
                    String uid = uidInput.getText().toString().trim();
                    String token = tokenInput.getText().toString().trim();
                    if (uid.isEmpty() || token.isEmpty()) return;
                    Prefers.put("migu_uid", uid);
                    Prefers.put("migu_token", token);
                })
                .setNegativeButton("暂不登录", null)
                .setNeutralButton("清除账号", (d, w) -> {
                    Prefers.remove("migu_uid");
                    Prefers.remove("migu_token");
                })
                .show();
    }

    private static LinearLayout.LayoutParams marginParams(FragmentActivity activity, int l, int t, int r, int b) {
        int d = (int) (activity.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(l * d, t * d, r * d, b * d);
        return lp;
    }
}
