package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.splashscreen.SplashScreen;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.CustomTitleView;
import com.fongmi.android.tv.ui.custom.CustomVerticalGridView;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.presenter.FuncPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Guard;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.google.common.collect.Lists;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import com.fongmi.android.tv.bean.HomeBanner;
import com.fongmi.android.tv.ui.presenter.HomeBannerPresenter;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.fragment.FolderFragment;

public class HomeActivity extends BaseActivity implements CustomTitleView.Listener, VodPresenter.OnClickListener, FuncPresenter.OnClickListener, HistoryPresenter.OnClickListener, TypeAdapter.OnClickListener {

    private static final int HOME_RECOMMEND_LIMIT = 15;
    private static final int HOME_RECOMMEND_SOURCE_LIMIT = 8;

    private ActivityHomeBinding mBinding;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mFuncAdapter;
    private ArrayObjectAdapter mAdapter;
    private HistoryPresenter mPresenter;
    private SiteViewModel mViewModel;
    private Result mResult;
    private Clock mClock;
    private TypeAdapter mTypeAdapter;
    private View mOldView;
    // 首页推荐回退：站点无首页推荐时，用第一个分类内容替代
    private boolean mHomeFallback;
    private List<Class> mPendingTypes;
    private ServiceConnection mPlaybackServiceConnection;
    private PlaybackService mPlaybackService;
    private final BroadcastReceiver mNetworkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateNetworkState();
        }
    };

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 设置为大屏UI时，从桌面/通知等入口启动 App 会先进入 HomeActivity（Launcher），
        // 在此重定向到 CinemaHomeActivity，避免每次打开都显示普通UI
        if (com.fongmi.android.tv.setting.Setting.isHomeCapsule()) {
            Intent original = getIntent();
            Intent redirect = new Intent(this, CinemaHomeActivity.class);
            redirect.setAction(original.getAction());
            if (original.getData() != null) redirect.setData(original.getData());
            if (original.getExtras() != null) redirect.putExtras(original.getExtras());
            redirect.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(redirect);
            finish();
            return;
        }
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    private final List<Vod> mHomeRecommends = new ArrayList<>();
    /** loadHomeRecommends 并发保护：避免配置刷新后重复 submit 多个线程，
     *  让前一个线程 submit 完成后再允许下一次；每个线程内部拿到 home site 后还要再校验一次
     *  home key 是否仍然与当前 getHome().getKey() 一致，避免用户在刷新中切了首页源导致数据乱 */
    private volatile boolean mLoadingHomeRecommends = false;

    private void loadHomeRecommends() {
        loadHomeRecommends(false);
    }

    private void loadHomeRecommends(boolean forceRefresh) {
        final Site home = getHome();
        final String homeKey = home == null ? "" : (home.getKey() == null ? "" : home.getKey());
        // 启动时如果 home 站点还没初始化（initConfig 还没跑完），就不要白跑 —— 等 onRefreshEvent HOME
        // 触发时再调用一次即可，避免第一次就展示 15 条占位 vod
        if (!forceRefresh && TextUtils.isEmpty(homeKey)) {
            return;
        }
        if (mLoadingHomeRecommends) {
            // 强制刷新场景：如果当前已有一次在跑，直接 return；等待完成后 UI 会拿到最新数据
            return;
        }
        final List<Site> sites = getRecommendSites();
        mLoadingHomeRecommends = true;
        // hasCached 升到 final，让后面的 lambda/内部类能引用
        final boolean[] hasCachedHolder = {false};
        try {
            // 有缓存先立刻展示缓存：保证 Config 刚就绪的瞬间 UI 有真实海报图，不是空占位
            synchronized (mHomeRecommends) {
                mHomeRecommends.clear();
                for (Site site : sites) addCachedRecommends(mHomeRecommends, site);
                if (!mHomeRecommends.isEmpty()) {
                    hasCachedHolder[0] = true;
                }
            }
            if (hasCachedHolder[0]) {
                final List<Vod> snapshot = new ArrayList<>();
                synchronized (mHomeRecommends) { snapshot.addAll(mHomeRecommends); }
                runOnUiThread(() -> setHomeBanner(snapshot));
            }
        } finally {
            // 无论缓存分支是否抛错，都放行到下面网络分支
        }
        final boolean hasCached = hasCachedHolder[0];

        // 首页 Banner 推荐数据属于低优先级加载，改走大线程池，
        // 避免与首页/分类内容请求争抢 5 线程池导致内容加载排队超时
        com.fongmi.android.tv.utils.Task.largeExecutor().submit(() -> {
            try {
                List<Vod> recommends = new ArrayList<>();
                // forceRefresh = true 时直接走网络；forceRefresh = false 时有缓存就先展示缓存，
                // 但只要还没成功拿到网络数据就继续请求（避免缓存过期图一直停在那）
                if (!forceRefresh && hasCached) {
                    // 先把缓存再塞一份给 recommends 集合（下面 size>0 时会再次 setHomeBanner）
                    synchronized (mHomeRecommends) { recommends.addAll(mHomeRecommends); }
                }
                for (Site site : sites) {
                    try {
                        com.fongmi.android.tv.bean.Result result = com.fongmi.android.tv.api.SiteApi.homeContent(site);
                        if (result != null && result.getList() != null && !result.getList().isEmpty()) {
                            com.fongmi.android.tv.setting.Setting.putHomeRecommend(site.getKey(), result.toString());
                            // 有了真实网络结果 → 先清空 recommends 再按网络 add（否则缓存残留会一直顶在前面）
                            if (!forceRefresh && hasCached && !recommends.isEmpty()) {
                                recommends.clear();
                            }
                            addRecommends(recommends, site, result);
                        } else if (recommends.isEmpty()) {
                            addCachedRecommends(recommends, site);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (recommends.isEmpty()) addCachedRecommends(recommends, site);
                    }
                }
                // 防并发：真正写回前再校验一次 homeKey（如果用户在刷新中切了首页源，这批数据就作废）
                Site latestHome = getHome();
                String latestHomeKey = latestHome == null ? "" : (latestHome.getKey() == null ? "" : latestHome.getKey());
                if (!TextUtils.isEmpty(homeKey) && !homeKey.equals(latestHomeKey)) {
                    return;
                }
                if (!recommends.isEmpty()) {
                    synchronized (mHomeRecommends) {
                        mHomeRecommends.clear();
                        mHomeRecommends.addAll(recommends);
                    }
                    runOnUiThread(() -> {
                        List<Vod> snap;
                        synchronized (mHomeRecommends) { snap = new ArrayList<>(mHomeRecommends); }
                        setHomeBanner(snap);
                    });
                }
            } finally {
                mLoadingHomeRecommends = false;
            }
        });
    }

    private List<Site> getRecommendSites() {
        // 首页推荐海报直接使用当前用户选中的首页源（比如"豆瓣推荐"那个），
        // 不再用假的 iqiyi/tencent 源，保证海报图漂亮、且 siteKey 正确不会跳搜索。
        Site home = getHome();
        if (home != null && !TextUtils.isEmpty(home.getKey())) {
            return Collections.singletonList(home);
        }
        // 兜底：如果当前首页源还没初始化，用 VodConfig 里第一个源
        List<Site> sites = VodConfig.get().getSites();
        if (sites != null && !sites.isEmpty()) {
            return Collections.singletonList(sites.get(0));
        }
        return Collections.emptyList();
    }

    private Site createRecommendSite(String key, String name, String api) {
        Site site = new Site();
        site.setKey(key);
        site.setName(name);
        site.setApi(api);
        site.setType(3);
        return site;
    }

    private void addCachedRecommends(List<Vod> recommends, Site site) {
        try {
            String cache = com.fongmi.android.tv.setting.Setting.getHomeRecommend(site.getKey());
            if (cache.isEmpty()) cache = "iqiyi".equals(site.getKey()) ? com.fongmi.android.tv.setting.Setting.getIqiyiRecommends() : "";
            if (!cache.isEmpty()) addRecommends(recommends, site, com.fongmi.android.tv.bean.Result.fromJson(cache));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addRecommends(List<Vod> recommends, Site site, com.fongmi.android.tv.bean.Result result) {
        if (result == null || result.getList() == null) return;
        int count = 0;
        for (Vod vod : result.getList()) {
            if (recommends.size() >= HOME_RECOMMEND_LIMIT || count >= HOME_RECOMMEND_SOURCE_LIMIT) break;
            vod.setSite(site);
            recommends.add(vod);
            count++;
        }
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (!Guard.enforce(this)) return; // 远程服务开关：未获放行则终止初始化
        mClock = Clock.create(mBinding.clock).format("MM/dd E HH:mm");
        mBinding.progressLayout.showProgress();
        PermissionUtil.requestNotify(this);
        DLNARendererService.start(this);
        Updater.create().start(this);
        setRecyclerView();
        setTypeAdapter();
        setViewModel();
        setAdapter();
        // 先尝试从 savedInstanceState 恢复保存的接口数据（避免播放返回后数据丢失）
        restoreSavedState(savedInstanceState);
        if (com.fongmi.android.tv.setting.Setting.isSyncAutoSync()) {
            checkSync();
        } else {
            initConfig();
        }
        setTitle();
        setLogo();
        bindPlaybackService();
        loadHomeRecommends();
    }

    /**
     * 从 onSaveInstanceState 保存的数据中恢复首页结果与推荐海报
     * 用于：进入播放页面后系统回收HomeActivity，返回时直接恢复上次数据而不用等新的网络请求
     */
    private void restoreSavedState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            mResult = Result.empty();
            return;
        }
        boolean restored = false;
        try {
            String savedResult = savedInstanceState.getString("saved_home_result");
            if (savedResult != null && !savedResult.isEmpty()) {
                Result cached = Result.fromJson(savedResult);
                if (cached != null && cached.getList() != null && !cached.getList().isEmpty()) {
                    addVideo(mResult = cached);
                    restored = true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            String savedRecommends = savedInstanceState.getString("saved_home_recommends");
            if (savedRecommends != null && !savedRecommends.isEmpty()) {
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.List<Vod>>() {}.getType();
                java.util.List<Vod> list = com.fongmi.android.tv.App.gson().fromJson(savedRecommends, type);
                if (list != null && !list.isEmpty()) {
                    mHomeRecommends.clear();
                    mHomeRecommends.addAll(list);
                    setHomeBanner(mHomeRecommends);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!restored) mResult = Result.empty();
    }

    private boolean mSyncDone;

    private void checkSync() {
        mSyncDone = false;
        Notify.show(R.string.sync_syncing);
        com.fongmi.android.tv.utils.WebDavSync.download(new Callback() {
            @Override
            public void success() {
                if (mSyncDone) return;
                mSyncDone = true;
                Notify.show(R.string.sync_success);
                initConfig();
            }

            @Override
            public void error() {
                if (mSyncDone) return;
                mSyncDone = true;
                Notify.show(R.string.sync_fail);
                initConfig();
            }
        });
        App.post(() -> {
            if (mSyncDone) return;
            mSyncDone = true;
            Notify.show(R.string.sync_fail);
            initConfig();
        }, 3000);
    }

    @Override
    protected void initEvent() {
        mBinding.title.setListener(this);
        mBinding.search.setOnClickListener(view -> SearchActivity.start(this));
        mBinding.keep.setOnClickListener(view -> KeepActivity.start(this));
        mBinding.live.setOnClickListener(view -> LiveActivity.start(this));
        mBinding.push.setOnClickListener(view -> PushActivity.start(this));
        if (mBinding.history != null) mBinding.history.setOnClickListener(view -> HistoryActivity.start(this));
        mBinding.setting.setOnClickListener(view -> SettingActivity.start(this));
        mBinding.net.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                } catch (Exception e2) {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception e3) {
                        // ignore
                    }
                }
            }
        });
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                boolean isTop = (position <= 1);
                mBinding.toolbar.setVisibility(isTop ? View.VISIBLE : View.GONE);
                mBinding.recyclerType.setVisibility((isTop && mTypeAdapter != null && mTypeAdapter.getItemCount() > 0) ? View.VISIBLE : View.GONE);
                if (mPresenter.isDelete()) setHistoryDelete(false);
            }
        });
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mBinding.recyclerType.setSelectedPosition(position + 1);
                mBinding.recyclerType.requestFocus();
            }
        });
        mBinding.recyclerType.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                onChildSelected(child);
            }
        });
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(HomeBanner.class, new HomeBannerPresenter(this));
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), FuncPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), HistoryPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
     }

    private void setTypeAdapter() {
        mBinding.recyclerType.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.recyclerType.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.recyclerType.setAdapter(mTypeAdapter = new TypeAdapter(this));

        List<Class> items = new ArrayList<>();
        Class home = new Class();
        home.setTypeName(ResUtil.getString(R.string.vod_home));
        home.setTypeId("home");
        items.add(home);
        mTypeAdapter.addAll(items);
        mBinding.recyclerType.setVisibility(View.VISIBLE);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, result -> {
            mAdapter.remove("progress");
            boolean hasValidResult = result != null && result.getList() != null && !result.getList().isEmpty();
            boolean hasValidSavedData = mResult != null && mResult.getList() != null && !mResult.getList().isEmpty();

            if (hasValidResult) {
                int index = getRecommendIndex();
                if (mAdapter.size() > index) {
                    mAdapter.removeItems(index, mAdapter.size() - index);
                }
                // 回退模式：用分类内容填充 Banner 轮播
                if (mHomeFallback && mHomeRecommends.isEmpty()) {
                    synchronized (mHomeRecommends) {
                        for (Vod vod : result.getList()) {
                            if (mHomeRecommends.size() >= HOME_RECOMMEND_LIMIT) break;
                            vod.setSite(getHome());
                            mHomeRecommends.add(vod);
                        }
                    }
                }
                addVideo(mResult = result);
                Cache.clear().put(result);
                // 回退模式下使用之前保存的分类列表（categoryContent 不返回分类）
                List<Class> types = mHomeFallback ? mPendingTypes : result.getTypes();
                mHomeFallback = false;
                mPendingTypes = null;
                setTypes(types != null ? types : result.getTypes());

                Result cacheResult = new Result();
                cacheResult.setList(result.getList());
                com.fongmi.android.tv.setting.Setting.putHomeRecommend(getHome().getKey(), cacheResult.toString());
            } else if (!hasValidSavedData) {
                if (mHomeFallback) {
                    mHomeFallback = false;
                    List<Class> types = mPendingTypes;
                    mPendingTypes = null;
                    if (types != null && !types.isEmpty()) {
                        setTypes(types);
                        if (mBinding.recyclerType != null && mTypeAdapter != null && mTypeAdapter.getItemCount() > 1) {
                            mBinding.recyclerType.setSelectedPosition(1);
                            onCategoryClick(1);
                        }
                    } else {
                        mBinding.progressLayout.showEmpty();
                    }
                } else if (result != null && result.getTypes() != null && !result.getTypes().isEmpty()) {
                    setTypes(result.getTypes());
                    mBinding.progressLayout.showContent();
                    if (mBinding.recyclerType != null && mTypeAdapter != null && mTypeAdapter.getItemCount() > 1) {
                        mBinding.recyclerType.setSelectedPosition(1);
                        onCategoryClick(1);
                    }
                } else {
                    mBinding.progressLayout.showEmpty();
                }
            }
            // else: result 为空但 mResult 有 savedInstanceState 恢复的有效数据，保留现有数据，仅移除 progress
        });
    }

    private void setAdapter() {
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        mAdapter.add(R.string.home_recommend);
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                showContent();
            }
        };
    }

    private void showContent() {
        mBinding.progressLayout.showContent();
        checkAction(getIntent());
        setFocus();
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                LiveActivity.start(getActivity());
            }
        });
    }

    private void setFocus() {
        mBinding.title.setSelected(true);
        App.post(() -> mBinding.title.setFocusable(true), 100);
        // 始终强制聚焦分类栏首页 tab：用 post 等待 RecyclerView 布局完成后再 requestFocus
        // 不检查 title.hasFocus()，因为 title 默认会自动获得焦点导致整个逻辑被跳过
        mBinding.recyclerType.post(() -> {
            if (mBinding.recyclerType.getVisibility() == View.VISIBLE) {
                mBinding.recyclerType.setSelectedPosition(0);
                if (mBinding.recyclerType.getChildAt(0) != null) {
                    mBinding.recyclerType.getChildAt(0).requestFocus();
                } else {
                    // 子 View 还没布局，再等一帧
                    mBinding.recyclerType.post(() -> {
                        mBinding.recyclerType.setSelectedPosition(0);
                        if (mBinding.recyclerType.getChildAt(0) != null) {
                            mBinding.recyclerType.getChildAt(0).requestFocus();
                        } else {
                            mBinding.recyclerType.requestFocus();
                        }
                    });
                }
                return;
            }
            // 分类栏不可用时，聚焦第一个内容行
            for (int i = 0; i < mBinding.recycler.getAdapter().getItemCount(); i++) {
                RecyclerView.ViewHolder vh = mBinding.recycler.findViewHolderForAdapterPosition(i);
                if (vh == null || vh.itemView == null) continue;
                View focusable = findFirstFocusable(vh.itemView);
                if (focusable != null) {
                    mBinding.recycler.scrollToPosition(i);
                    focusable.requestFocus();
                    return;
                }
            }
            mBinding.recycler.requestFocus();
        });
    }

    private void getVideo() {
        mResult = Result.empty();
        mHomeFallback = false;
        mPendingTypes = null;
        int index = getRecommendIndex();
        boolean gone = mAdapter.indexOf("progress") == -1;
        boolean hasItem = gone && mAdapter.size() > index;
        if (hasItem) mAdapter.removeItems(index, mAdapter.size() - index);

        if (mTypeAdapter != null) {
            List<Class> items = new ArrayList<>();
            Class home = new Class();
            home.setTypeName(ResUtil.getString(R.string.vod_home));
            home.setTypeId("home");
            items.add(home);
            mTypeAdapter.addAll(items);
            mBinding.recyclerType.setVisibility(View.VISIBLE);
        }

        String cache = com.fongmi.android.tv.setting.Setting.getHomeRecommend(getHome().getKey());
        if (!cache.isEmpty()) {
            try {
                Result cachedResult = Result.fromJson(cache);
                if (cachedResult != null && cachedResult.getList() != null && !cachedResult.getList().isEmpty()) {
                    addVideo(mResult = cachedResult);
                    gone = false;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (gone) mAdapter.add("progress");
        mViewModel.homeContent();
    }

    private void addVideo(Result result) {
        List<Vod> list = result.getList();
        List<Vod> gridList = new ArrayList<>(list);
        
        setHomeBanner(mHomeRecommends);

        Style style = result.getStyle(getHome().getStyle());
        if (style.isList()) mAdapter.addAll(mAdapter.size(), gridList);
        else addGrid(gridList, style);
    }

    private void addGrid(List<Vod> items, Style style) {
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style);
        for (List<Vod> part : Lists.partition(items, Product.getColumn(style))) {
            ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
            adapter.addAll(0, part);
            rows.add(new ListRow(adapter));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private void setFunc() {
        setHomeBanner(mHomeRecommends);
    }

    private void getHistory() {
        getHistory(false);
    }

    private void getHistory(boolean renew) {
        List<History> items = History.get();
        int historyIndex = getHistoryIndex();
        boolean exist = historyIndex != -1;
        if (renew) mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        if (items.isEmpty() && exist) {
            removeHistory(historyIndex);
        } else if (!items.isEmpty()) {
            if (!exist) {
                mAdapter.add(0, R.string.home_history);
                mAdapter.add(1, new ListRow(mHistoryAdapter));
            } else if (renew) {
                removeHistory(historyIndex);
                mAdapter.add(historyIndex, R.string.home_history);
                mAdapter.add(historyIndex + 1, new ListRow(mHistoryAdapter));
            }
        }
        mHistoryAdapter.setItems(items, new BaseDiffCallback<History>());
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    private void clearHistory() {
        removeHistory(getHistoryIndex());
        History.delete(VodConfig.getCid());
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
        com.fongmi.android.tv.utils.WebDavSync.upload(null);
    }

    private int getHistoryIndex() {
        return mAdapter.indexOf(R.string.home_history);
    }

    private void removeHistory(int index) {
        if (index == -1) return;
        int count = mAdapter.size() > index + 1 && mAdapter.get(index + 1) instanceof ListRow ? 2 : 1;
        mAdapter.removeItems(index, count);
    }

    private int getRecommendIndex() {
        return mAdapter.indexOf(R.string.home_recommend) + 1;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.history();
                RefreshEvent.home();
                setLogo();
                break;
            case COMMON:
                setFunc();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                getVideo();
                setTitle();
                // initConfig() 异步跑完后 VodConfig.getHome() 才会有豆瓣推荐等真实站点，
                // 这里再触发一次 loadHomeRecommends(force=true) 走网络，
                // 否则 initView 时那次 home==null 直接 return，banner 就永远停在占位"暂无推荐"
                loadHomeRecommends(true);
                break;
            case HISTORY:
                getHistory();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(this, event.history().save(VodConfig.getCid()));
        } else {
            VodConfig.load(event.config(), getCallback(event));
        }
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    @Override
    public void onItemClick(Func item) {
        if (item.getResId() == R.string.home_vod) VodActivity.start(this, mResult);
        else if (item.getResId() == R.string.home_live) LiveActivity.start(this);
        else if (item.getResId() == R.string.home_keep) KeepActivity.start(this);
        else if (item.getResId() == R.string.home_push) PushActivity.start(this);
        else if (item.getResId() == R.string.home_search) SearchActivity.start(this);
        else if (item.getResId() == R.string.home_setting) SettingActivity.start(this);
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) mViewModel.action(getHome().getKey(), item.getAction());
        else if (getHome().isIndex()) CollectActivity.start(this, item.getName());
        else {
            String siteKey = TextUtils.isEmpty(item.getSiteKey()) ? getHome().getKey() : item.getSiteKey();
            // 轮播海报现在使用当前首页源（如豆瓣推荐）的真实siteKey，不再硬跳搜索；
            // 只有当 vodId 为空时才走搜索兜底（防止白屏）。
            String vodId = item.getId();
            if (vodId == null || vodId.isEmpty() || vodId.startsWith("placeholder_")) {
                SearchActivity.start(this, item.getName());
            } else {
                VideoActivity.start(this, siteKey, vodId, item.getName(), item.getPic());
            }
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction()) return false;
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        mHistoryAdapter.remove(item.delete());
        if (mHistoryAdapter.size() > 0) return;
        removeHistory(getHistoryIndex());
        mPresenter.setDelete(false);
        com.fongmi.android.tv.utils.WebDavSync.upload(null);
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) clearHistory();
        else setHistoryDelete(true);
        return true;
    }

    @Override
    public void showDialog() {
        SiteDialog.create().show(this);
    }

    @Override
    public void onRefresh() {
        getVideo();
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    private void updateNetworkState() {
        int state = Util.getNetworkState();
        if (state == 1) {
            mBinding.net.setImageResource(R.drawable.ic_net_wifi);
            mBinding.net.setVisibility(View.VISIBLE);
        } else if (state == 2) {
            mBinding.net.setImageResource(R.drawable.ic_net_ethernet);
            mBinding.net.setVisibility(View.VISIBLE);
        } else {
            mBinding.net.setImageResource(R.drawable.ic_net_disconnected);
            mBinding.net.setVisibility(View.VISIBLE);
        }
    }

    private boolean isToolbarFocused() {
        View focus = getCurrentFocus();
        return focus == mBinding.title || focus == mBinding.search || focus == mBinding.keep || focus == mBinding.live || focus == mBinding.push || focus == mBinding.history || focus == mBinding.setting || focus == mBinding.net;
    }

    /** 当前焦点是否在分类 recyclerType（或它的子项）上 */
    private boolean isRecyclerTypeFocused() {
        View focus = getCurrentFocus();
        if (focus == null || mBinding.recyclerType == null) return false;
        // AndroidX ViewCompat 包路径是 androidx.core.view.ViewCompat，
        // 为了不用再引入 import，直接手写递归祖先判断
        View v = focus;
        while (v != null) {
            if (v == mBinding.recyclerType) return true;
            android.view.ViewParent p = v.getParent();
            if (!(p instanceof View)) break;
            v = (View) p;
        }
        return false;
    }

    /** 聚焦首页第一个可聚焦的内容行（跳过不可聚焦的 header）
     *  找不到时兜底：让 recycler 先聚焦
     */
    private boolean requestFocusOnFirstContent() {
        try {
            RecyclerView rv = mBinding.recycler;
            if (rv == null || rv.getAdapter() == null || rv.getAdapter().getItemCount() <= 0) return false;
            int count = rv.getAdapter().getItemCount();
            for (int i = 0; i < count; i++) {
                RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(i);
                if (vh == null || vh.itemView == null) continue;
                View focusable = findFirstFocusable(vh.itemView);
                if (focusable != null) {
                    rv.scrollToPosition(i);
                    return focusable.requestFocus();
                }
            }
            if (rv.getChildCount() > 0) return rv.getChildAt(0).requestFocus();
            rv.scrollToPosition(0);
            return rv.requestFocus();
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }

    /** 递归查找 View 树中第一个可聚焦且可见的 View */
    private View findFirstFocusable(View view) {
        if (view == null) return null;
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.isFocusable()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findFirstFocusable(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 判断当前焦点是否在 recycler 中第一行内容上（position 0 或 1） */
    private boolean isFirstContentRowFocused() {
        try {
            View focus = getCurrentFocus();
            if (focus == null) return false;
            RecyclerView rv = mBinding.recycler;
            if (rv == null) return false;
            View v = focus;
            boolean inRecycler = false;
            while (v != null) {
                if (v == rv) { inRecycler = true; break; }
                Object p = v.getParent();
                v = (p instanceof View) ? (View) p : null;
            }
            if (!inRecycler) return false;
            int position = mBinding.recycler.getSelectedPosition();
            return position == 0 || position == 1;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 从分类栏按 ↓ 时，聚焦 ViewPager 里当前 FolderFragment 的第一个海报 */
    private boolean requestFocusOnPager() {
        try {
            if (mBinding.pager.getVisibility() != View.VISIBLE) return false;
            FolderFragment fragment = getFragment();
            if (fragment == null) return false;
            View view = fragment.getView();
            if (view == null) return false;
            // 递归查找第一个可聚焦的海报
            View focusable = findFirstFocusable(view);
            if (focusable != null) return focusable.requestFocus();
            // 内容还没加载，延迟再试
            App.post(() -> {
                FolderFragment f = getFragment();
                if (f == null) return;
                View v = f.getView();
                if (v == null) return;
                View target = findFirstFocusable(v);
                if (target != null) target.requestFocus();
            }, 200);
            return true;
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }

    /** 判断当前焦点是否已到分类内容列表的第一行 */
    private boolean isPagerListAtTop() {
        try {
            View focus = getCurrentFocus();
            if (focus == null) return false;
            View v = focus;
            while (v != null) {
                if (v instanceof CustomVerticalGridView) {
                    return ((CustomVerticalGridView) v).getSelectedPosition() <= 0;
                }
                Object p = v.getParent();
                v = (p instanceof View) ? (View) p : null;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return false;
    }

    /** 判断当前焦点是否在 ViewPager（非首页分类的内容区）内 */
    private boolean isPagerContentFocused() {
        try {
            View focus = getCurrentFocus();
            if (focus == null) return false;
            if (mBinding.pager.getVisibility() != View.VISIBLE) return false;
            // 焦点在 pager 内部
            View v = focus;
            while (v != null) {
                if (v == mBinding.pager) return true;
                Object p = v.getParent();
                v = (p instanceof View) ? (View) p : null;
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) showDialog();
        if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event)) {
            // 工具栏（顶部）按 ↓ → 先聚焦分类
            if (isToolbarFocused()) {
                if (mBinding.recyclerType.getVisibility() == View.VISIBLE) {
                    return mBinding.recyclerType.requestFocus();
                } else if (requestFocusOnFirstContent()) {
                    return true;
                } else if (mBinding.recycler.getChildCount() > 0) {
                    return mBinding.recycler.getChildAt(0).requestFocus();
                }
            }
            // 分类 recyclerType（横排那排「首页 综合配置 ...」）按 ↓
            else if (isRecyclerTypeFocused() && mBinding.recyclerType.getVisibility() == View.VISIBLE) {
                int catPos = mBinding.recyclerType.getSelectedPosition();
                if (catPos == 0) {
                    // 首页 → 聚焦第一个内容行
                    if (requestFocusOnFirstContent()) return true;
                } else {
                    // 非首页分类 → 聚焦 ViewPager 里的海报列表
                    if (requestFocusOnPager()) return true;
                }
            }
        }
        // 按 ↑ 键处理
        if (KeyUtil.isActionDown(event) && KeyUtil.isUpKey(event)) {
            // 海报列表第一行按 ↑ → 回到分类栏
            if (isFirstContentRowFocused()) {
                if (mBinding.recyclerType.getVisibility() == View.VISIBLE) {
                    return mBinding.recyclerType.requestFocus();
                }
            }
            // ViewPager 里的海报按 ↑ → 先检查列表是否已到第一行，到顶才回到分类栏
            if (isPagerContentFocused()) {
                if (isPagerListAtTop()) {
                    return mBinding.recyclerType.requestFocus();
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClock.start();
        registerReceiver(mNetworkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        updateNetworkState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mClock.stop();
        unregisterReceiver(mNetworkReceiver);
    }

    private FolderFragment getFragment() {
        if (mBinding.pager.getAdapter() == null || mBinding.pager.getAdapter().getCount() == 0) return null;
        return (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    @Override
    protected void onBackInvoked() {
        FolderFragment folder = getFragment();
        if (mBinding.progressLayout.isProgress()) {
            showContent();
        } else if (mPresenter.isDelete()) {
            setHistoryDelete(false);
        } else if (mBinding.pager.getVisibility() == View.VISIBLE && folder != null && folder.canBack()) {
            folder.goBack();
        } else if (isPagerContentFocused()) {
            // 焦点在 ViewPager 海报上 → 先回到分类栏
            mBinding.recyclerType.requestFocus();
        } else if (mBinding.recyclerType.getVisibility() == View.VISIBLE && mBinding.recyclerType.getSelectedPosition() != 0) {
            mBinding.recyclerType.setSelectedPosition(0);
            onCategoryClick(0);
        } else if (mBinding.recycler.getVisibility() == View.VISIBLE && mBinding.recycler.getSelectedPosition() != 0) {
            mBinding.recycler.scrollToPosition(0);
        } else {
            exitApp();
        }
    }

    /** Banner 已移除，始终返回 false */
    private boolean isBannerFocused() {
        return false;
    }

    private void exitApp() {
        if (mPlaybackService != null) mPlaybackService.shutdown();
        else stopService(new Intent(this, PlaybackService.class));
        super.onBackInvoked();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 保存首页API数据与推荐海报，防止系统回收Activity后返回丢失
        try {
            if (mResult != null && mResult.getList() != null && !mResult.getList().isEmpty()) {
                outState.putString("saved_home_result", mResult.toString());
            }
            if (mHomeRecommends != null && !mHomeRecommends.isEmpty()) {
                outState.putString("saved_home_recommends", com.fongmi.android.tv.App.gson().toJson(mHomeRecommends, new com.google.gson.reflect.TypeToken<java.util.List<Vod>>() {}.getType()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        unbindPlaybackService();
        DLNARendererService.stop(this);
        // 仅在用户真正退出App时才清空单例配置与数据源
        // 否则系统回收Activity（如播放视频时内存不足）后返回首页，VodConfig/LiveConfig为空会导致接口无数据
        if (isFinishing() || isChangingConfigurations()) {
            LiveConfig.get().clear();
            VodConfig.get().clear();
            OkHttp.get().clear();
            Source.get().exit();
            Server.get().stop();
        }
        AppDatabase.backup();
        super.onDestroy();
    }

    private void setHomeBanner(List<Vod> recommends) {
        mBinding.live.setVisibility(LiveConfig.hasUrl() ? View.VISIBLE : View.GONE);
        updateCoverBg(recommends);
    }

    private String mLastCoverUrl = "";

    private void updateCoverBg(List<Vod> recommends) {
        if (recommends == null || recommends.isEmpty()) {
            mBinding.coverBg.setVisibility(View.INVISIBLE);
            mLastCoverUrl = "";
            return;
        }
        Vod item = recommends.get(0);
        String url = item.getBackdrop();
        if (TextUtils.isEmpty(url)) url = item.getPic();
        if (TextUtils.isEmpty(url) || url.equals(mLastCoverUrl)) return;
        mLastCoverUrl = url;
        ImgUtil.load(this, url, new com.fongmi.android.tv.impl.CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                mBinding.coverBg.setImageDrawable(resource);
                mBinding.coverBg.setVisibility(View.VISIBLE);
            }
            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
            }
        });
    }

    private void bindPlaybackService() {
        bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND_ACTION), mPlaybackServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                mPlaybackService = ((PlaybackService.LocalBinder) binder).getService();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mPlaybackService = null;
            }
        }, BIND_AUTO_CREATE);
    }

    private void unbindPlaybackService() {
        if (mPlaybackServiceConnection != null) {
            unbindService(mPlaybackServiceConnection);
            mPlaybackServiceConnection = null;
            mPlaybackService = null;
        }
    }

    private void setTypes(List<Class> types) {
        if (types.isEmpty()) {
            mBinding.recyclerType.setVisibility(View.GONE);
            mBinding.pager.setVisibility(View.GONE);
            mBinding.progressLayout.setVisibility(View.VISIBLE);
            return;
        }

        List<Class> items = new ArrayList<>();
        Class home = new Class();
        home.setTypeName(ResUtil.getString(R.string.vod_home));
        home.setTypeId("home");
        items.add(home);
        items.addAll(types);

        mTypeAdapter.addAll(items);
        mBinding.recyclerType.setVisibility(View.VISIBLE);

        // 分类列表没变时复用现有 PageAdapter，避免每次首页刷新都重建整个 ViewPager，
        // 导致已创建的分类 Fragment 全部销毁、切 tab 时重新发 categoryContent 请求
        if (mBinding.pager.getAdapter() instanceof PageAdapter && sameTypes(((PageAdapter) mBinding.pager.getAdapter()).mTypes, types)) {
            ((PageAdapter) mBinding.pager.getAdapter()).mTypes.clear();
            ((PageAdapter) mBinding.pager.getAdapter()).mTypes.addAll(types);
            mBinding.pager.getAdapter().notifyDataSetChanged();
        } else {
            mBinding.pager.setAdapter(new PageAdapter(getSupportFragmentManager(), types));
        }

        onCategoryClick(0);
    }

    private boolean sameTypes(List<Class> oldTypes, List<Class> newTypes) {
        if (oldTypes == null || newTypes == null || oldTypes.size() != newTypes.size()) return false;
        for (int i = 0; i < oldTypes.size(); i++) {
            if (!Objects.equals(oldTypes.get(i).getTypeId(), newTypes.get(i).getTypeId())) return false;
        }
        return true;
    }

    private void onCategoryClick(int position) {
        if (position == 0) {
            mBinding.progressLayout.setVisibility(View.VISIBLE);
            mBinding.pager.setVisibility(View.GONE);
        } else {
            mBinding.progressLayout.setVisibility(View.GONE);
            mBinding.pager.setVisibility(View.VISIBLE);
            mBinding.pager.setCurrentItem(position - 1, false);
        }
    }

    private void updateFilter(Class item) {
        if (Cache.get(item).isEmpty()) return;
        item.setFilter(!item.getFilter());
        getFragment().toggleFilter(item.getFilter());
        mTypeAdapter.notifyItemChanged(mTypeAdapter.indexOf(item));
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child) {
        if (mOldView != null) mOldView.setSelected(false);
        if ((mOldView = child != null ? child.itemView : null) == null) return;
        mOldView.setSelected(true);
        // 切页防抖从 100ms 缩短到 30ms，提升遥控器分类切换跟手度
        App.post(mRunnable, 30);
    }

    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            int position = mBinding.recyclerType.getSelectedPosition();
            onCategoryClick(position);
        }
    };

    @Override
    public void onItemClick(Class item) {
        int position = mTypeAdapter.indexOf(item);
        onCategoryClick(position);
        if (position > 0) updateFilter(item);
    }

    @Override
    public void onRefresh(Class item) {
        if (mBinding.pager.getAdapter() != null) {
            int pagePos = mTypeAdapter.indexOf(item) - 1;
            if (pagePos >= 0 && pagePos < mBinding.pager.getAdapter().getCount()) {
                FolderFragment fragment = (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, pagePos);
                fragment.onRefresh();
            }
        }
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        private final List<Class> mTypes;

        public PageAdapter(@NonNull FragmentManager fm, List<Class> types) {
            super(fm);
            this.mTypes = types;
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mTypes.get(position);
            return FolderFragment.newInstance(getHome().getKey(), type);
        }

        @Override
        public int getCount() {
            return mTypes.size();
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }
    }
}
