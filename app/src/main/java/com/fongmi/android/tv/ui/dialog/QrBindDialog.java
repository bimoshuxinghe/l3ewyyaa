package com.fongmi.android.tv.ui.dialog;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.chaquo.YspServer;

/**
 * 扫码绑定对话框：电视显示二维码（http://<局域网IP>:9980/bind?type=tmdb）→ 手机扫码 →
 * 手机上填 TMDB API Key → 写回本机 Prefers（刮削立即生效）。
 * 绑定页面由内置 Python 服务 bind_server(9980) 提供。
 */
public class QrBindDialog {

    /** 扫码绑定类型：TMDB API Key。 */
    public static final String TYPE_TMDB = "tmdb";

    public static void show(FragmentActivity activity) {
        show(activity, TYPE_TMDB);
    }

    public static void show(FragmentActivity activity, String type) {
        if (activity == null || activity.isFinishing()) return;
        if (type == null) type = TYPE_TMDB;
        String url = "http://" + YspServer.getLocalIp() + ":9980/bind?type=" + type;
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
        Bitmap bmp = QRCode.getBitmap(url, qrSize, 2);
        qr.setImageBitmap(bmp);
        qr.setAdjustViewBounds(true);
        qr.setPadding(0, 8, 0, 8);
        root.addView(qr);

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
