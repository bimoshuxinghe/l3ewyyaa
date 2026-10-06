package com.fongmi.android.tv.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;
import com.github.catvod.net.OkHttp;

import java.io.InputStream;

@GlideModule
public class OkGlideModule extends AppGlideModule {

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setLogLevel(Log.ERROR);
        // 磁盘缓存 250MB，站源海报反复加载不再重复走网络
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, 250 * 1024 * 1024));
        // 按设备内存动态分配内存缓存与 Bitmap 池，TV 盒子内存紧张时也能稳定运行
        MemorySizeCalculator calculator = new MemorySizeCalculator.Builder(context).build();
        // 32 位设备（armv7a 老电视/盒子）进程可寻址堆有限，是卡顿的主要根源：
        // 1) 海报墙统一降为 RGB_565 解码，位图内存直接减半（海报不透明，肉眼几乎无差）
        // 2) 内存缓存与 Bitmap 池各砍半，进一步降低 GC 压力与 OOM 概率
        if (isLowMemoryDevice(context)) {
            builder.setDefaultRequestOptions(RequestOptions.formatOf(DecodeFormat.PREFER_RGB_565));
            builder.setMemoryCache(new LruResourceCache(calculator.getMemoryCacheSize() / 2));
            builder.setBitmapPool(new LruBitmapPool(calculator.getBitmapPoolSize() / 2));
        } else {
            builder.setMemoryCache(new LruResourceCache(calculator.getMemoryCacheSize()));
            builder.setBitmapPool(new LruBitmapPool(calculator.getBitmapPoolSize()));
        }
    }

    private boolean isLowMemoryDevice(@NonNull Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRam = am != null && am.isLowRamDevice();
        // v7a 拆分包跑在 32 位设备上时，主 ABI 一定不带 "64"
        boolean is32Bit = Build.SUPPORTED_ABIS.length > 0 && !Build.SUPPORTED_ABIS[0].contains("64");
        return lowRam || is32Bit;
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(OkHttp.client()));
    }
}
