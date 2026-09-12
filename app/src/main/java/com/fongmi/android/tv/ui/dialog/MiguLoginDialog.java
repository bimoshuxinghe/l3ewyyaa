package com.fongmi.android.tv.ui.dialog;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.chaquo.MiguServer;
import com.github.catvod.utils.Prefers;

/**
 * 咪咕账号登录弹窗：支持遥控器输入（D-pad + OK 弹软键盘），也支持"手机扫码绑定"。
 * 扫码绑定：电视显示二维码（http://<局域网IP>:9980/bind?t=token）→ 手机扫码 →
 * 手机上填 UID/Token → 写回本机 → 内置 Python 代理自动切蓝光1080p。
 */
public class MiguLoginDialog {

    /** 本次进程是否已弹过（未配置账号时只在打开直播后提示一次，避免骚扰）。 */
    private static volatile boolean shownOnce = false;

    /** 扫码绑定类型：咪咕账号（UID/Token）。 */
    public static final String TYPE_MIGU = "migu";
    /** 扫码绑定类型：TMDB API Key。 */
    public static final String TYPE_TMDB = "tmdb";

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
        tip.setText("咪咕账号登录（可选）：登录后自动切换蓝光1080p，非会员自动降回高清。\n· 遥控器直接输入，或点「扫码绑定」用手机填\n· UID/Token 在 miguvideo.com 登录后从浏览器开发者工具抓取");
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

        // 按钮行：扫码绑定 / 清除账号（Button 原生支持遥控器 D-pad 焦点）
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int bpad = (int) (activity.getResources().getDisplayMetrics().density * 10);
        Button scanBtn = new Button(activity);
        scanBtn.setText("扫码绑定");
        scanBtn.setPadding(bpad, bpad / 2, bpad, bpad / 2);
        scanBtn.setOnClickListener(v -> showQr(activity, TYPE_MIGU));
        row.addView(scanBtn, marginParams(activity, 0, 14, 8, 0));

        Button clearBtn = new Button(activity);
        clearBtn.setText("清除账号");
        clearBtn.setPadding(bpad, bpad / 2, bpad, bpad / 2);
        clearBtn.setOnClickListener(v -> {
            Prefers.remove("migu_uid");
            Prefers.remove("migu_token");
        });
        row.addView(clearBtn, marginParams(activity, 8, 14, 0, 0));
        root.addView(row);

        new AlertDialog.Builder(activity)
                .setTitle("咪咕账号登录")
                .setView(root)
                .setPositiveButton("保存并播放", (d, w) -> save(uidInput, tokenInput))
                .setNegativeButton("暂不登录", null)
                .show();
    }

    /** 扫码绑定对话框：显示二维码 + 操作提示。type 见 TYPE_MIGU / TYPE_TMDB。 */
    public static void showQr(FragmentActivity activity, String type) {
        if (activity == null || activity.isFinishing()) return;
        if (type == null) type = TYPE_MIGU;
        boolean tmdb = TYPE_TMDB.equals(type);
        String url = "http://" + MiguServer.getLocalIp() + ":9980/bind?type=" + type;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
        root.setPadding(pad, pad, pad, pad);

        // 二维码尺寸自适应屏幕（不挤掉下方按钮，也不会太小扫不出）
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int qrSize = Math.round(Math.min(dm.widthPixels, dm.heightPixels) * 0.42f / dm.density);
        qrSize = Math.max(qrSize, 220);
        qrSize = Math.min(qrSize, 400);

        ImageView qr = new ImageView(activity);
        qr.setAdjustViewBounds(true);
        qr.setPadding(0, 8, 0, 8);
        root.addView(qr);

        // 二维码后台生成，避免在 TV 盒子主线程上同步编码导致弹窗卡顿（接口加载后更明显）
        final int fQrSize = qrSize;
        Task.submit(() -> {
            Bitmap bmp = QRCode.getBitmap(url, fQrSize, 2);
            App.post(() -> qr.setImageBitmap(bmp));
        });

        TextView tip = new TextView(activity);
        if (tmdb) {
            tip.setText("1. 用手机微信/浏览器扫上面的二维码\n2. 在手机页面上填 TMDB API Key，点「绑定到电视」\n3. 手机显示绑定成功后，按遥控器 OK 关闭本窗口\n\n提示：手机和电视需在同一 WiFi");
        } else {
            tip.setText("1. 用手机微信/浏览器扫上面的二维码\n2. 在手机页面上填 UID 和 Token，点「绑定到电视」\n3. 手机显示绑定成功后，按遥控器 OK 关闭本窗口\n\n提示：手机和电视需在同一 WiFi");
        }
        tip.setTextSize(14);
        tip.setLineSpacing(0, 1.2f);
        root.addView(tip);

        new AlertDialog.Builder(activity)
                .setTitle(tmdb ? "手机扫码绑定 TMDB" : "手机扫码绑定")
                .setView(root)
                .setPositiveButton("完成", null)
                .setNegativeButton("取消", null)
                .show();
    }

    private static void save(EditText uidInput, EditText tokenInput) {
        String uid = uidInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();
        if (uid.isEmpty() || token.isEmpty()) return;
        Prefers.put("migu_uid", uid);
        Prefers.put("migu_token", token);
    }

    private static LinearLayout.LayoutParams marginParams(FragmentActivity activity, int l, int t, int r, int b) {
        int d = (int) (activity.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(l * d, t * d, r * d, b * d);
        return lp;
    }
}
