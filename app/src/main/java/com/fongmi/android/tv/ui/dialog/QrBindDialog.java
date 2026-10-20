package com.fongmi.android.tv.ui.dialog;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.chaquo.YspServer;

/**
 * 手机扫码绑定对话框：电视显示二维码（http://<局域网IP>:9980/bind）→
 * 手机扫码 → 在手机页面填 TMDB API Key 提交 → 写回本机即时生效。
 */
public class QrBindDialog {

    /** 扫码绑定类型：TMDB API Key。 */
    public static final String TYPE_TMDB = "tmdb";

    /** 扫码绑定对话框：显示二维码 + 操作提示。 */
    public static void showQr(FragmentActivity activity, String type) {
        if (activity == null || activity.isFinishing()) return;
        String url = "http://" + YspServer.getLocalIp() + ":9980/bind?type=" + TYPE_TMDB;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
        root.setPadding(pad, pad, pad, pad);

        // 二维码尺寸自适应屏幕（不挤掉下方提示，也不会太小扫不出）
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int qrSize = Math.round(Math.min(dm.widthPixels, dm.heightPixels) * 0.42f / dm.density);
        qrSize = Math.max(qrSize, 220);
        qrSize = Math.min(qrSize, 400);

        ImageView qr = new ImageView(activity);
        qr.setAdjustViewBounds(true);
        qr.setPadding(0, 8, 0, 8);
        root.addView(qr);

        // 二维码后台生成，避免在 TV 盒子主线程上同步编码导致弹窗卡顿
        final int fQrSize = qrSize;
        Task.submit(() -> {
            Bitmap bmp = QRCode.getBitmap(url, fQrSize, 2);
            App.post(() -> qr.setImageBitmap(bmp));
        });

        TextView tip = new TextView(activity);
        tip.setText("1. 用手机微信/浏览器扫上面的二维码\n2. 在手机页面上填 TMDB API Key，点「绑定到电视」\n3. 手机显示绑定成功后，按遥控器 OK 关闭本窗口\n\n提示：手机和电视需在同一 WiFi");
        tip.setTextSize(14);
        tip.setLineSpacing(0, 1.2f);
        root.addView(tip);

        new AlertDialog.Builder(activity)
                .setTitle("手机扫码绑定 TMDB")
                .setView(root)
                .setPositiveButton("完成", null)
                .setNegativeButton("取消", null)
                .show();
    }
}
