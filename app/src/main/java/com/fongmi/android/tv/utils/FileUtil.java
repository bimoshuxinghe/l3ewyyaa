package com.fongmi.android.tv.utils;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.Callback;
import com.github.catvod.utils.Path;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.util.Enumeration;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FileUtil {

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    public static File getWall(int index) {
        return Path.files("wallpaper_" + index);
    }

    public static File getWallCache() {
        return Path.files("wallpaper_cache");
    }

    public static void openFile(File file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(getShareUri(file), FileUtil.getMimeType(file.getName()));
        App.get().startActivity(intent);
    }

    public static void gzipCompress(File target) {
        byte[] buffer = new byte[16384];
        try (FileInputStream is = new FileInputStream(target); GZIPOutputStream os = new GZIPOutputStream(new FileOutputStream(target.getAbsolutePath() + ".gz"))) {
            int read;
            while ((read = is.read(buffer)) > 0) os.write(buffer, 0, read);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            Path.clear(target);
        }
    }

    public static void gzipDecompress(File target, File path) {
        byte[] buffer = new byte[16384];
        try (GZIPInputStream is = new GZIPInputStream(new BufferedInputStream(new FileInputStream(target))); BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(path))) {
            int read;
            while ((read = is.read(buffer)) != -1) os.write(buffer, 0, read);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void zipDecompress(File target, File path) {
        try (ZipFile zip = new ZipFile(target)) {
            Enumeration<?> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry) entries.nextElement();
                File out = new File(path, entry.getName());
                if (entry.isDirectory()) out.mkdirs();
                else Path.copy(zip.getInputStream(entry), out);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void clearCache(Callback callback) {
        Task.execute(() -> {
            Path.clear(Path.cache());
            App.post(callback::success);
        });
    }

    public static void getCacheSize(Callback callback) {
        Task.execute(() -> {
            String usage = byteCountToDisplaySize(getDirectorySize(Path.cache()));
            App.post(() -> callback.success(usage));
        });
    }

    public static long getDirectorySize(File dir) {
        long size = 0;
        if (dir == null) return 0;
        if (dir.isDirectory()) for (File file : Path.list(dir)) size += getDirectorySize(file);
        else size = dir.length();
        return size;
    }

    public static long getAvailableStorageSpace(File file) {
        try {
            StatFs stat = new StatFs(file.getAbsolutePath());
            return stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        } catch (Exception e) {
            return 0;
        }
    }

    public static Uri getShareUri(String path) {
        return getShareUri(new File(path.replace("file://", "")));
    }

    public static Uri getShareUri(File file) {
        return FileProvider.getUriForFile(App.get(), App.get().getPackageName() + ".provider", file);
    }

    private static String getMimeType(String fileName) {
        String mimeType = URLConnection.guessContentTypeFromName(fileName);
        return TextUtils.isEmpty(mimeType) ? "*/*" : mimeType;
    }

    public static String byteCountToDisplaySize(long size) {
        if (size <= 0) return ResUtil.getString(R.string.none);
        String[] units = new String[]{"bytes", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private static InputStream openInputStream(Uri uri) throws IOException {
        InputStream input = App.get().getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Unable to open source file");
        return input;
    }

    public static void copyAtomically(Uri source, File target) throws IOException {
        try (InputStream input = openInputStream(source)) {
            copyAtomically(input, target);
        }
    }

    public static void copyAtomically(File source, File target) throws IOException {
        if (source.getCanonicalFile().equals(target.getCanonicalFile())) return;
        try (InputStream input = new FileInputStream(source)) {
            copyAtomically(input, target);
        }
    }

    public static void writeAtomically(byte[] data, File target) throws IOException {
        try (InputStream input = new ByteArrayInputStream(data)) {
            copyAtomically(input, target);
        }
    }

    private static void copyAtomically(InputStream input, File target) throws IOException {
        File temp = createTempFile(target);
        try {
            copyToFile(input, temp);
            Path.move(temp, target);
        } finally {
            Path.clear(temp);
        }
    }

    private static File createTempFile(File target) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null) throw new IOException("Target has no parent directory");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Unable to create target directory");
        return File.createTempFile("copy-", ".tmp", parent);
    }

    private static void copyToFile(InputStream input, File target) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            transfer(input, output, Long.MAX_VALUE);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void transfer(InputStream input, OutputStream output, long maxBytes) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long totalBytes = 0L;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
            totalBytes += count;
            if (totalBytes > maxBytes) throw new IOException("File is too large");
            output.write(buffer, 0, count);
        }
    }

    public static String getDisplayName(Uri uri) {
        return getDisplayName(uri, uri.toString());
    }

    public static String getDisplayName(Uri uri, String fallback) {
        String name = ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme()) ? queryDisplayName(uri) : null;
        if (!TextUtils.isEmpty(name)) return name;
        name = uri.getLastPathSegment();
        return TextUtils.isEmpty(name) ? fallback : name;
    }

    private static String queryDisplayName(Uri uri) {
        String[] projection = {OpenableColumns.DISPLAY_NAME};
        try (Cursor cursor = App.get().getContentResolver().query(uri, projection, null, null, null)) {
            return cursor == null || !cursor.moveToFirst() ? null : cursor.getString(0);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
