package com.fongmi.android.tv.ui.activity;

import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.core.splashscreen.SplashScreen;
import androidx.leanback.widget.HorizontalGridView;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.databinding.ActivityCinemaHomeBinding;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.CinemaCategoryAdapter;
import com.fongmi.android.tv.ui.adapter.CinemaPosterAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FocusColor;
import com.fongmi.android.tv.utils.Guard;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class CinemaHomeActivity extends BaseActivity implements
        CinemaPosterAdapter.OnClickListener,
        CinemaCategoryAdapter.OnClickListener,
        com.fongmi.android.tv.impl.SiteListener {

    private ActivityCinemaHomeBinding mBinding;
    private CinemaPosterAdapter mPosterAdapter;
    private CinemaCategoryAdapter mCategoryAdapter;
    private SiteViewModel mViewModel;
    private Result mResult;
    private Clock mClock;
    private boolean mConfigReady;
    private boolean mHasMovieSelected;
    private String mCurrentHeroName = "";
    private String mLastCoverUrl = "";
    private String mLastTitleUrl = "";
    private String mCurrentTypeId = "home";
    private List<Class> mHomeTypes;
    private List<String> mCurrentBackdrops = new ArrayList<>();
    private int mCurrentBackdropIndex = 0;
    private int mAppliedFocusColor = -1;
    private final Random mRandom = new Random();
    private final Handler mBackdropHandler = new Handler(Looper.getMainLooper());
    private Runnable mBackdropRunnable;

    // 短按智能选源播放：跨站点搜索后自动打开第一个可播源
    private final Handler mSmartHandler = new Handler(Looper.getMainLooper());
    private boolean mSmartPlaying;
    private String mSmartSelfKey = "";
    private Runnable mSmartTimeout;
    private final Observer<Result> mSmartSearchObserver = result -> {
        if (result == null || result.getList() == null) return;
        for (Vod vod : result.getList()) {
            if (TextUtils.isEmpty(vod.getId()) || TextUtils.isEmpty(vod.getSiteKey())) continue;
            if (TextUtils.equals(vod.getSiteKey(), mSmartSelfKey)) continue; // 跳过海报自身的死源
            stopSmartPlay();
            VideoActivity.start(CinemaHomeActivity.this, vod.getSiteKey(), vod.getId(), vod.getName(), vod.getPic());
            return;
        }
    };

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
        return mBinding = ActivityCinemaHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (!Guard.enforce(this)) return; // 远程服务开关：未获放行则终止初始化（保持弹窗提示流程，onResume/onPause 已加空判防 NPE）
        mConfigReady = true; // 标记 initView 走完，onDestroy 据此决定是否清理 smart play
        mClock = Clock.create(mBinding.clock).format("MM/dd E HH:mm");
        mBinding.loading.setVisibility(View.VISIBLE);
        mBinding.empty.setText(R.string.home_loading);
        com.fongmi.android.tv.utils.PermissionUtil.requestNotify(this);
        com.fongmi.android.tv.service.DLNARendererService.start(this);
        Updater.create().start(this);
        setPosterAdapter();
        setCategoryAdapter();
        setViewModel();
        setHero();
        applyFocusColor();
        initConfig();
        checkAction(getIntent());
    }

    private void setPosterAdapter() {
        mPosterAdapter = new CinemaPosterAdapter(this);
        mBinding.posters.setHorizontalSpacing(ResUtil.dp2px(12));
        mBinding.posters.setAdapter(mPosterAdapter);
        mBinding.posters.addOnChildViewHolderSelectedListener(new androidx.leanback.widget.OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, RecyclerView.ViewHolder child, int position, int subposition) {
                updateHero(position);
            }
        });
    }

    private void setCategoryAdapter() {
        mCategoryAdapter = new CinemaCategoryAdapter(this);
        mBinding.categories.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.categories.setAdapter(mCategoryAdapter);

        List<Class> items = new ArrayList<>();
        Class home = new Class();
        home.setTypeName(ResUtil.getString(R.string.vod_home));
        home.setTypeId("home");
        items.add(home);
        mCategoryAdapter.setItems(items);
        mBinding.categories.setVisibility(View.VISIBLE);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, result -> {
            mBinding.loading.setVisibility(View.GONE);
            if (result == null) return;
            // Always show categories if available (matching mobile UI behavior)
            if (result.getTypes() != null && !result.getTypes().isEmpty()) {
                mHomeTypes = result.getTypes();
                setCategories(result.getTypes());
            }
            if (result.getList() != null && !result.getList().isEmpty()) {
                // Has videos - show them
                mResult = result;
                mPosterAdapter.setItems(result.getList());
                if ("home".equals(mCurrentTypeId)) {
                    com.fongmi.android.tv.setting.Setting.putHomeRecommend(
                        getHome() != null ? getHome().getKey() : "", result.toString());
                }
                updateHero(0);
                mBinding.posters.requestFocus();
                com.fongmi.android.tv.bean.Cache.clear().put(result);
            } else if ("home".equals(mCurrentTypeId)) {
                // Home content returned no videos but may have categories
                if (mCategoryAdapter != null && mCategoryAdapter.getItemCount() > 1) {
                    // Categories available - show hint, user can click to view full grid
                    mBinding.loading.setVisibility(View.GONE);
                    mBinding.loadingProgress.setVisibility(View.GONE);
                } else {
                    mBinding.loading.setVisibility(View.VISIBLE);
                    mBinding.empty.setText(R.string.home_empty);
                    mBinding.loadingProgress.setVisibility(View.GONE);
                }
            } else {
                mBinding.loading.setVisibility(View.VISIBLE);
                mBinding.empty.setText(R.string.home_empty);
                mBinding.loadingProgress.setVisibility(View.GONE);
            }
        });
    }

    private void applyFocusColor() {
        mBinding.siteName.setBackground(FocusColor.navBackground());
        mBinding.search.setBackground(FocusColor.navBackground());
        mBinding.live.setBackground(FocusColor.navBackground());
        mBinding.keep.setBackground(FocusColor.navBackground());
        mBinding.history.setBackground(FocusColor.navBackground());
        mBinding.push.setBackground(FocusColor.navBackground());
        mBinding.setting.setBackground(FocusColor.navBackground());
        mBinding.net.setBackground(FocusColor.navBackground());
        int color = FocusColor.getIndex();
        if (color == mAppliedFocusColor) return;
        mAppliedFocusColor = color;
        if (mCategoryAdapter != null) mCategoryAdapter.notifyDataSetChanged();
        if (mPosterAdapter != null) mPosterAdapter.notifyDataSetChanged();
    }

    private void setHero() {
        if (!mHasMovieSelected) {
            mBinding.appTitle.setText(getString(R.string.app_name));
            mBinding.tagRow.setVisibility(View.GONE);
        }
    }

    private void updateHero(int position) {
        Vod item = mPosterAdapter.getItem(position);
        if (item == null) return;
        mHasMovieSelected = true;
        mCurrentHeroName = item.getName();
        stopBackdropRotation();
        mCurrentBackdrops.clear();
        mCurrentBackdropIndex = 0;
        mBinding.appTitle.setText(item.getName());
        showTitleText();
        if (!TextUtils.isEmpty(item.getActor())) {
            mBinding.actor.setText("主演：" + item.getActor());
            mBinding.actor.setVisibility(View.VISIBLE);
        } else {
            mBinding.actor.setVisibility(View.GONE);
        }
        String tipText = item.getContent();
        if (!TextUtils.isEmpty(tipText)) {
            mBinding.tip.setText("简介：" + tipText);
        } else if (!TextUtils.isEmpty(item.getRemarks())) {
            mBinding.tip.setText(item.getRemarks());
        } else {
            mBinding.tip.setText(R.string.home_tip);
        }
        updateTagRow(item);
        updateCoverBg(item);
    }

    private void updateTagRow(Vod item) {
        boolean hasTag = false;
        String year = item.getYear();
        String area = item.getArea();
        String type = item.getTypeName();
        if (!TextUtils.isEmpty(year)) {
            mBinding.tagYear.setText(year);
            mBinding.tagYear.setVisibility(View.VISIBLE);
            hasTag = true;
        } else {
            mBinding.tagYear.setVisibility(View.GONE);
        }
        if (!TextUtils.isEmpty(area)) {
            mBinding.tagArea.setText(area);
            mBinding.tagArea.setVisibility(View.VISIBLE);
            hasTag = true;
        } else {
            mBinding.tagArea.setVisibility(View.GONE);
        }
        if (!TextUtils.isEmpty(type)) {
            mBinding.tagType.setText(type);
            mBinding.tagType.setVisibility(View.VISIBLE);
            hasTag = true;
        } else {
            mBinding.tagType.setVisibility(View.GONE);
        }
        mBinding.tagRow.setVisibility(hasTag ? View.VISIBLE : View.GONE);
    }

    private void updateCoverBg(Vod item) {
        String itemName = item.getName();
        // 优先使用 TMDB API 获取横屏背景图、剧情简介和标题 Logo
        if (Setting.hasTmdbApiKey()) {
            final String fallbackUrl = getFallbackCoverUrl(item);
            com.fongmi.android.tv.utils.TmdbUtil.searchAsync(itemName, result -> {
                runOnUiThread(() -> {
                    if (!TextUtils.equals(mCurrentHeroName, itemName)) return;
                    // 使用 TMDB 标题 Logo 替换左侧文字标题
                    if (result.hasLogoUrl()) {
                        loadTitleLogo(itemName, result.getLogoUrl());
                    } else {
                        showTitleText();
                    }
                    // 保存多张背景图并启动随机轮换
                    if (result.hasBackdrops()) {
                        mCurrentBackdrops = new ArrayList<>(result.getBackdrops());
                    } else if (result.hasBackdrop()) {
                        mCurrentBackdrops.clear();
                        mCurrentBackdrops.add(result.getBackdropUrl());
                    }
                    if (!mCurrentBackdrops.isEmpty()) {
                        mCurrentBackdropIndex = mRandom.nextInt(mCurrentBackdrops.size());
                        loadCoverBg(itemName, mCurrentBackdrops.get(mCurrentBackdropIndex));
                        startBackdropRotation(itemName);
                    } else if (!TextUtils.isEmpty(fallbackUrl)) {
                        loadCoverBg(itemName, fallbackUrl);
                    } else {
                        hideCoverBg();
                    }
                    // 使用 TMDB 剧情简介更新
                    if (result.hasOverview()) {
                        mBinding.tip.setText(result.getOverview());
                    }
                });
            });
            return;
        }
        // 没有配置 TMDB API Key，使用站点提供的图片
        showTitleText();
        String coverUrl = getFallbackCoverUrl(item);
        if (TextUtils.isEmpty(coverUrl)) {
            hideCoverBg();
            return;
        }
        loadCoverBg(itemName, coverUrl);
    }

    private String getFallbackCoverUrl(Vod item) {
        String backdrop = item.getBackdrop();
        if (!TextUtils.isEmpty(backdrop)) return backdrop;
        return item.getPic();
    }

    private void hideCoverBg() {
        mLastCoverUrl = "";
        mBinding.coverBg.setVisibility(View.INVISIBLE);
    }

    private void loadCoverBg(String name, String coverUrl) {
        if (TextUtils.equals(mLastCoverUrl, coverUrl)) return;
        mLastCoverUrl = coverUrl;
        boolean isFirstLoad = mBinding.coverBg.getVisibility() != View.VISIBLE;
        if (isFirstLoad) {
            mBinding.coverBg.setVisibility(View.VISIBLE);
            mBinding.coverBg.setAlpha(0f);
            ImgUtil.loadBackdrop(name, coverUrl, mBinding.coverBg);
            mBinding.coverBg.animate().alpha(0.92f).setDuration(400).start();
        } else {
            mBinding.coverBg.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                ImgUtil.loadBackdrop(name, coverUrl, mBinding.coverBg);
                mBinding.coverBg.animate().alpha(0.92f).setDuration(400).start();
            }).start();
        }
    }

    private void startBackdropRotation(String itemName) {
        stopBackdropRotation();
        if (mCurrentBackdrops == null || mCurrentBackdrops.size() <= 1) return;
        mBackdropRunnable = new Runnable() {
            @Override
            public void run() {
                if (!TextUtils.equals(mCurrentHeroName, itemName)) return;
                if (mCurrentBackdrops == null || mCurrentBackdrops.size() <= 1) return;
                int nextIndex = mRandom.nextInt(mCurrentBackdrops.size());
                if (nextIndex == mCurrentBackdropIndex) {
                    nextIndex = (nextIndex + 1) % mCurrentBackdrops.size();
                }
                mCurrentBackdropIndex = nextIndex;
                loadCoverBg(itemName, mCurrentBackdrops.get(mCurrentBackdropIndex));
                if (mBackdropHandler != null) {
                    mBackdropHandler.postDelayed(this, 15000);
                }
            }
        };
        mBackdropHandler.postDelayed(mBackdropRunnable, 15000);
    }

    private void stopBackdropRotation() {
        if (mBackdropRunnable != null) {
            mBackdropHandler.removeCallbacks(mBackdropRunnable);
            mBackdropRunnable = null;
        }
    }

    private void loadTitleLogo(String name, String logoUrl) {
        if (TextUtils.equals(mLastTitleUrl, logoUrl)) return;
        mLastTitleUrl = logoUrl;
        mBinding.appTitle.setVisibility(View.GONE);
        mBinding.appTitleLogo.setVisibility(View.VISIBLE);
        ImgUtil.load(name, logoUrl, mBinding.appTitleLogo, false);
    }

    private void showTitleText() {
        mLastTitleUrl = "";
        mBinding.appTitleLogo.setVisibility(View.GONE);
        mBinding.appTitle.setVisibility(View.VISIBLE);
    }

    private void setCategories(List<Class> types) {
        if (types == null || types.isEmpty()) {
            mBinding.categories.setVisibility(View.GONE);
            return;
        }
        List<Class> items = new ArrayList<>();
        Class home = new Class();
        home.setTypeName(ResUtil.getString(R.string.vod_home));
        home.setTypeId("home");
        items.add(home);
        items.addAll(types);
        mCategoryAdapter.setItems(items);
        mBinding.categories.setVisibility(View.VISIBLE);
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
                mConfigReady = true;
                setTitle();
                updateSiteName();
                // Config loaded successfully, onConfigEvent(VOD) -> RefreshEvent.home() -> onRefreshEvent(HOME) will trigger loadHomeContent()
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                mConfigReady = true;
            }
        };
    }

    private void setTitle() {
        ImgUtil.logo(mBinding.logo);
        updateSiteName();
        if (mHasMovieSelected) return;
        List<String> items = Arrays.asList(getHome() != null ? getHome().getName() : "", getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.appTitle.setText(s));
    }

    private void updateSiteName() {
        Site home = getHome();
        if (home != null && !home.isEmpty()) {
            mBinding.siteName.setText(home.getName());
            mBinding.siteName.setVisibility(View.VISIBLE);
        } else {
            mBinding.siteName.setVisibility(View.GONE);
        }
    }

    private void loadHomeContent() {
        if (getHome() == null || getHome().isEmpty()) return;
        mCurrentTypeId = "home";
        mHasMovieSelected = false;
        mLastCoverUrl = "";
        mBinding.tagRow.setVisibility(View.GONE);
        mBinding.loading.setVisibility(View.VISIBLE);
        mBinding.loadingProgress.setVisibility(View.VISIBLE);
        mBinding.empty.setText(R.string.home_loading);
        // Load from cache first for instant display
        String cache = com.fongmi.android.tv.setting.Setting.getHomeRecommend(getHome().getKey());
        if (!cache.isEmpty()) {
            try {
                Result cachedResult = Result.fromJson(cache);
                if (cachedResult != null && cachedResult.getList() != null && !cachedResult.getList().isEmpty()) {
                    mResult = cachedResult;
                    mPosterAdapter.setItems(cachedResult.getList());
                    if (cachedResult.getTypes() != null && !cachedResult.getTypes().isEmpty()) {
                        mHomeTypes = cachedResult.getTypes();
                        setCategories(cachedResult.getTypes());
                    }
                    updateHero(0);
                    mBinding.loading.setVisibility(View.GONE);
                    mBinding.loadingProgress.setVisibility(View.GONE);
                    mBinding.posters.requestFocus();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // Always fetch from network (SiteViewModel.execute handles cancellation internally)
        mViewModel.homeContent();
    }

    private void loadCategoryContent(Class item) {
        if (getHome() == null || TextUtils.isEmpty(getHome().getKey())) return;
        mCurrentTypeId = item.getTypeId();
        mHasMovieSelected = false;
        mLastCoverUrl = "";
        mBinding.tagRow.setVisibility(View.GONE);
        mBinding.loading.setVisibility(View.VISIBLE);
        mBinding.loadingProgress.setVisibility(View.VISIBLE);
        mBinding.empty.setText(R.string.home_loading);
        mViewModel.categoryContent(getHome().getKey(), item.getTypeId(), "1", true, new HashMap<>());
    }

    private void checkAction(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            com.fongmi.android.tv.utils.PermissionUtil.requestFile(this, allGranted -> {
                if ("text/plain".equals(intent.getType()) || com.fongmi.android.tv.utils.UrlUtil.path(intent.getData()).endsWith(".m3u")) {
                    LiveConfig.load(com.fongmi.android.tv.bean.Config.find("file:/" + com.fongmi.android.tv.utils.FileChooser.getPathFromUri(intent.getData()), 1), new com.fongmi.android.tv.impl.Callback() {
                        @Override public void success() { LiveActivity.start(CinemaHomeActivity.this); }
                        @Override public void error(String msg) { Notify.show(msg); }
                    });
                } else {
                    VideoActivity.push(this, intent.getData().toString());
                }
            });
        }
    }

    @Override
    protected void initEvent() {
        mBinding.siteName.setOnClickListener(view -> {
            if (getHome() != null) {
                com.fongmi.android.tv.ui.dialog.SiteDialog.create().show(this);
            }
        });
        mBinding.search.setOnClickListener(view -> SearchActivity.start(this));
        mBinding.live.setOnClickListener(view -> LiveActivity.start(this));
        mBinding.keep.setOnClickListener(view -> KeepActivity.start(this));
        mBinding.history.setOnClickListener(view -> HistoryActivity.start(this));
        mBinding.push.setOnClickListener(view -> PushActivity.start(this));
        mBinding.setting.setOnClickListener(view -> SettingActivity.start(this));
        mBinding.net.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                } catch (Exception e2) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            }
        });
    }

    @Override
    public void onItemClick(Vod item) {
        if (getHome() == null) return;
        if (TextUtils.isEmpty(item.getName())) return;
        // 与 HomeActivity 保持一致：首页源被标记为 index（推荐/索引源，如“豆瓣推荐”）
        // 说明自身没有播放源，点海报走“跨站点智能选源播放”；
        // 其余真实有播放源的站点，直接打开播放（原逻辑），不要走豆瓣首页这套。
        if (getHome().isIndex()) {
            startSmartPlay(item);
            return;
        }
        String siteKey = TextUtils.isEmpty(item.getSiteKey()) ? getHome().getKey() : item.getSiteKey();
        String vodId = item.getId();
        if (TextUtils.isEmpty(vodId)) {
            SearchActivity.start(this, item.getName());
        } else {
            VideoActivity.start(this, siteKey, vodId, item.getName(), item.getPic());
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        // 长按：打开聚合搜索页手动挑选
        CollectActivity.start(this, item.getName());
        return true;
    }

    private void startSmartPlay(Vod item) {
        if (getHome() == null) return;
        String keyword = item.getName();
        List<Site> sites = VodConfig.get().getSites().stream().filter(Site::isSearchable).toList();
        if (sites.isEmpty()) {
            // 没有任何可搜索站点，退回普通搜索页
            SearchActivity.start(this, keyword);
            return;
        }
        stopSmartPlay();
        mSmartSelfKey = TextUtils.isEmpty(item.getSiteKey()) ? "" : item.getSiteKey();
        mSmartPlaying = true;
        mViewModel.resetSearch();
        mViewModel.getSearch().observe(this, mSmartSearchObserver);
        mViewModel.searchContent(sites, keyword, false);
        // 超时兜底：若一段时间无可用源，退回聚合搜索页让用户手动选
        final String fallback = keyword;
        mSmartTimeout = () -> {
            if (!mSmartPlaying) return;
            stopSmartPlay();
            CollectActivity.start(CinemaHomeActivity.this, fallback);
        };
        mSmartHandler.postDelayed(mSmartTimeout, 8000);
    }

    private void stopSmartPlay() {
        if (!mSmartPlaying) return;
        mSmartPlaying = false;
        mSmartHandler.removeCallbacks(mSmartTimeout);
        mViewModel.getSearch().removeObserver(mSmartSearchObserver);
        mViewModel.stopSearch();
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void onItemClick(Class item, int position) {
        if (position == 0) {
            loadHomeContent();
        } else {
            showCategoryGrid(position - 1);
        }
    }

    private void showCategoryGrid(int categoryIndex) {
        if (mHomeTypes == null || mHomeTypes.isEmpty()) return;
        Result result = new Result();
        result.setTypes(mHomeTypes);
        VodActivity.start(this, getHome().getKey(), result, categoryIndex);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.home();
                setTitle();
                break;
            case COMMON:
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
                setTitle();
                loadHomeContent();
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

    private boolean isNavFocused() {
        View focus = getCurrentFocus();
        return focus == mBinding.search || focus == mBinding.live || focus == mBinding.keep || focus == mBinding.history
                || focus == mBinding.push || focus == mBinding.setting || focus == mBinding.net
                || focus == mBinding.logo || focus == mBinding.siteName;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) {
            if (getHome() != null) {
                com.fongmi.android.tv.ui.dialog.SiteDialog.create().show(this);
            }
        }
        if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event)) {
            if (isNavFocused()) {
                if (mBinding.categories.getVisibility() == View.VISIBLE) {
                    return mBinding.categories.requestFocus();
                } else if (mBinding.posters.getChildCount() > 0) {
                    return mBinding.posters.requestFocus();
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mClock != null) mClock.start(); // Guard.enforce 失败提前 return 时 mClock 未初始化，空判防 NPE（崩溃弹窗根因）
        applyFocusColor();
        registerReceiver(mNetworkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        updateNetworkState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mClock != null) mClock.stop();
        unregisterReceiver(mNetworkReceiver);
        stopBackdropRotation();
    }

    @Override
    protected void onBackInvoked() {
        if (isNavFocused() && mBinding.posters.getChildCount() > 0) {
            mBinding.posters.requestFocus();
        } else if (mBinding.posters.hasFocus()) {
            if (mBinding.categories.getVisibility() == View.VISIBLE) {
                mBinding.categories.requestFocus();
            } else {
                mBinding.search.requestFocus();
            }
        } else {
            exitApp();
        }
    }

    private void exitApp() {
        super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        if (mConfigReady) stopSmartPlay(); // initView 未完成时 mViewModel/mSmartHandler 等尚未初始化，stopSmartPlay 内部访问它们会 NPE
        com.fongmi.android.tv.service.DLNARendererService.stop(this);
        if (isFinishing() || isChangingConfigurations()) {
            LiveConfig.get().clear();
            VodConfig.get().clear();
            com.github.catvod.net.OkHttp.get().clear();
            com.fongmi.android.tv.player.Source.get().exit();
            com.fongmi.android.tv.server.Server.get().stop();
        }
        com.fongmi.android.tv.db.AppDatabase.backup();
        super.onDestroy();
    }
}
