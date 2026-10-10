package com.fongmi.android.tv.ui.activity;

import android.content.Intent;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.ActivitySplashBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;

/**
 * 极轻启动页（与手机版同样的结构）：
 * 主题里用全屏 windowBackground 铺「星落」图，首帧立即显示；
 * 停留约 1.2 秒后进入影院模式主页，期间主页在后台完成初始化。
 */
public class SplashActivity extends BaseActivity {

    private ActivitySplashBinding mBinding;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySplashBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(android.os.Bundle savedInstanceState) {
        App.post(this::next, 1200);
    }

    private void next() {
        if (isFinishing() || isDestroyed()) return;
        Intent original = getIntent();
        Intent next = new Intent(this, CinemaHomeActivity.class);
        if (original != null) {
            next.setAction(original.getAction());
            if (original.getData() != null) next.setData(original.getData());
            if (original.getExtras() != null) next.putExtras(original.getExtras());
        }
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(next);
        overridePendingTransition(0, 0);
        finish();
    }
}
