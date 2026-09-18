package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivitySettingBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.impl.TmdbListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.setting.LiveSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.DohDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.ProxySubscriptionDialog;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.ui.dialog.TmdbDialog;
import com.fongmi.android.tv.ui.dialog.WebDavDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.FocusColor;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class SettingActivity extends BaseActivity implements ConfigListener, SiteListener, LiveListener, DohDialog.Listener, TmdbListener {

    private ActivitySettingBinding mBinding;
    private String[] size;
    private String[] search;
    private String[] searchType;
    private String[] searchThread;
    private String[] homeStyle;
    private String[] focusColor;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.vod.requestFocus();
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
        mBinding.versionText.setText(BuildConfig.VERSION_NAME);
        setCacheText();
        setOtherText();
    }

    private void setOtherText() {
        mBinding.dohText.setText(getDohList()[getDohIndex()]);
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
        mBinding.liveBootText.setText(getSwitch(LiveSetting.isBootGlobal()));
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[PlayerSetting.getSize()]);
        mBinding.searchText.setText((search = ResUtil.getStringArray(R.array.select_search))[Setting.getSearchMode()]);
        mBinding.searchTypeText.setText((searchType = ResUtil.getStringArray(R.array.select_search_type))[Setting.getSearchType()]);
        mBinding.searchThreadText.setText((searchThread = ResUtil.getStringArray(R.array.select_search_thread))[getSearchThreadIndex()]);
        mBinding.homeStyleText.setText((homeStyle = ResUtil.getStringArray(R.array.select_home_style))[Setting.getHomeStyle()]);
        mBinding.focusColorText.setText((focusColor = ResUtil.getStringArray(R.array.select_focus_color))[FocusColor.getIndex()]);
        mBinding.proxySubText.setText(com.fongmi.android.tv.proxy.ProxySubscriptionManager.get().getSummary());
        setTmdbText();
    }

    private void setCacheText() {
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                mBinding.cacheText.setText(result);
            }
        });
    }

    private void setTmdbText() {
        String key = Setting.getTmdbApiKey();
        if (key.isEmpty()) {
            mBinding.tmdbApiText.setText(R.string.setting_tmdb_api_empty);
        } else {
            mBinding.tmdbApiText.setText(key.substring(0, Math.min(8, key.length())) + "...");
        }
    }

    @Override
    protected void initEvent() {
        mBinding.vod.setOnClickListener(this::onVod);
        mBinding.doh.setOnClickListener(this::setDoh);
        mBinding.proxySub.setOnClickListener(view -> ProxySubscriptionDialog.create().callback(() -> mBinding.proxySubText.setText(com.fongmi.android.tv.proxy.ProxySubscriptionManager.get().getSummary())).show(this));
        mBinding.live.setOnClickListener(this::onLive);
        mBinding.wall.setOnClickListener(this::onWall);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.player.setOnClickListener(this::onPlayer);
        mBinding.danmaku.setOnClickListener(this::onDanmaku);
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.sync.setOnClickListener(view -> onSync());
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.vodHome.setOnClickListener(this::onVodHome);
        mBinding.live.setOnLongClickListener(this::onLiveEdit);
        mBinding.liveHome.setOnClickListener(this::onLiveHome);
        mBinding.wall.setOnLongClickListener(this::onWallEdit);
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.liveBoot.setOnClickListener(this::setLiveBoot);
        mBinding.search.setOnClickListener(this::setSearch);
        mBinding.searchType.setOnClickListener(this::setSearchType);
        mBinding.searchThread.setOnClickListener(this::setSearchThread);
        mBinding.homeStyle.setOnClickListener(this::setHomeStyle);
        mBinding.focusColor.setOnClickListener(this::setFocusColor);
        mBinding.tmdbApi.setOnClickListener(this::setTmdbApi);
        mBinding.vodHistory.setOnClickListener(this::onVodHistory);
        mBinding.liveHistory.setOnClickListener(this::onLiveHistory);
        mBinding.wallDefault.setOnClickListener(this::setWallDefault);
        mBinding.wallRefresh.setOnClickListener(this::setWallRefresh);
        mBinding.wallRefresh.setOnLongClickListener(this::onWallHistory);
    }

    @Override
    public void setConfig(Config config) {
        if (config.getUrl().startsWith("file")) {
            PermissionUtil.requestFile(this, allGranted -> load(config));
        } else {
            load(config);
        }
    }

    private void load(Config config) {
        switch (config.getType()) {
            case 0:
                VodConfig.load(config, getCallback());
                break;
            case 1:
                LiveConfig.load(config, getCallback());
                break;
            case 2:
                Setting.putWall(0);
                WallConfig.load(config, getCallback());
                break;
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(getActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                setCacheText();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void setLive(Live item) {
        LiveConfig.get().setHome(item);
    }

    private void onVod(View view) {
        ConfigDialog.create().vod().show(this);
    }

    private void onLive(View view) {
        ConfigDialog.create().live().show(this);
    }

    private void onWall(View view) {
        ConfigDialog.create().wall().show(this);
    }

    private boolean onVodEdit(View view) {
        ConfigDialog.create().vod().edit().show(this);
        return true;
    }

    private boolean onLiveEdit(View view) {
        ConfigDialog.create().live().edit().show(this);
        return true;
    }

    private boolean onWallEdit(View view) {
        ConfigDialog.create().wall().edit().show(this);
        return true;
    }

    private void onVodHome(View view) {
        SiteDialog.create().action().show(this);
    }

    private void onLiveHome(View view) {
        LiveDialog.create().action().show(this);
    }

    private void onVodHistory(View view) {
        HistoryDialog.create().vod().show(this);
    }

    private void onLiveHistory(View view) {
        HistoryDialog.create().live().show(this);
    }

    private void onPlayer(View view) {
        SettingPlayerActivity.start(this);
    }

    private void onDanmaku(View view) {
        SettingDanmakuActivity.start(this);
    }

    private void onVersion(View view) {
        Notify.show("TG群：https://t.me/tudouxiaolaba");
    }

    private void setWallDefault(View view) {
        Setting.putWall(Setting.getWall() == 4 ? 1 : Setting.getWall() + 1);
        Setting.putWallType(0);
        ConfigEvent.wall();
    }

    private void setWallRefresh(View view) {
        Setting.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private boolean onWallHistory(View view) {
        HistoryDialog.create().wall().show(this);
        return true;
    }

    private void setIncognito(View view) {
        Setting.putIncognito(!Setting.isIncognito());
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
    }

    private void setLiveBoot(View view) {
        LiveSetting.putBootGlobal(!LiveSetting.isBootGlobal());
        mBinding.liveBootText.setText(getSwitch(LiveSetting.isBootGlobal()));
    }

    private void setSize(View view) {
        int index = (PlayerSetting.getSize() + 1) % size.length;
        mBinding.sizeText.setText(size[index]);
        PlayerSetting.putSize(index);
        RefreshEvent.size();
    }

    private void setSearch(View view) {
        int index = (Setting.getSearchMode() + 1) % search.length;
        mBinding.searchText.setText(search[index]);
        Setting.putSearchMode(index);
    }

    private int getSearchThreadIndex() {
        int thread = Setting.getSearchThread();
        int best = 0;
        for (int i = 0; i < searchThread.length; i++) {
            if (Math.abs(Integer.parseInt(searchThread[i]) - thread) < Math.abs(Integer.parseInt(searchThread[best]) - thread)) best = i;
        }
        return best;
    }

    private void setSearchType(View view) {
        int index = (Setting.getSearchType() + 1) % searchType.length;
        mBinding.searchTypeText.setText(searchType[index]);
        Setting.putSearchType(index);
    }

    private void setSearchThread(View view) {
        int index = (getSearchThreadIndex() + 1) % searchThread.length;
        mBinding.searchThreadText.setText(searchThread[index]);
        Setting.putSearchThread(Integer.parseInt(searchThread[index]));
    }

    private void setHomeStyle(View view) {
        int index = (Setting.getHomeStyle() + 1) % homeStyle.length;
        mBinding.homeStyleText.setText(homeStyle[index]);
        Setting.putHomeStyle(index);
        Notify.show(R.string.setting_home_style);
        Intent intent = new Intent(this, Setting.isHomeCapsule() ? CinemaHomeActivity.class : HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void setFocusColor(View view) {
        int index = (FocusColor.getIndex() + 1) % focusColor.length;
        mBinding.focusColorText.setText(focusColor[index]);
        Setting.putFocusColor(index);
    }

    private void setTmdbApi(View view) {
        TmdbDialog.show(this);
    }

    @Override
    public void setTmdbConfig(String apiUrl, String imageUrl, String apiKey) {
        Setting.putTmdbApiUrl(apiUrl);
        Setting.putTmdbImageUrl(imageUrl);
        Setting.putTmdbApiKey(apiKey);
        setTmdbText();
        Notify.show(R.string.setting_tmdb_api_set);
    }

    private void setDoh(View view) {
        DohDialog.create().index(getDohIndex()).show(this);
    }

    @Override
    public void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }

    private void onCache(View view) {
        FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                setCacheText();
            }
        });
    }

    private void onBackup(View view) {
        PermissionUtil.requestFile(this, allGranted -> AppDatabase.backup(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.backup_success);
            }

            @Override
            public void error() {
                Notify.show(R.string.backup_fail);
            }
        }));
    }

    private void onRestore(View view) {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().callback(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                setOtherText();
                initConfig();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }).show(this));
    }

    private void onSync() {
        WebDavDialog.show(this, new Callback() {
            @Override
            public void success() {
                setOtherText();
                initConfig();
            }
        });
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init().load();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() != ConfigEvent.Type.COMMON) return;
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
    }

}
