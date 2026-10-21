package com.qingji.app.update;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.qingji.app.QingJiApp;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit APK downloads; automatic checks never send ledger data or install silently. */
public final class UpdateManager {
    public interface Listener {
        void onUpdateState();
        void onUpdateAvailable(UpdatePolicy.Offer offer);
        void onCheckFinished(boolean manual, String error, boolean found);
        void onDownloadReady();
        void onInstallVerified(File apk);
        void onUpdateError(String message);
    }
    private final QingJiApp app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private Listener listener;
    private volatile boolean checking, downloading, verifying;
    private volatile UpdatePolicy.Offer offer;
    private volatile String status;
    private long promptedCode;

    public UpdateManager(QingJiApp app) {
        this.app = app;
        status = app.config.text("update_status", "尚未检查更新");
    }

    public void attach(Listener value) {
        listener = value;
        notifyState();
        deliverOffer();
    }
    public void detach(Listener value) { if (listener == value) listener = null; }
    public boolean busy() { return checking || downloading || verifying; }
    public boolean downloading() { return downloading; }
    public String status() { return status; }
    public UpdatePolicy.Offer offer() { return offer; }
    public boolean autoCheck() { return app.config.prefs.getBoolean("update_auto", true); }
    public boolean previews() { return app.config.prefs.getBoolean("update_previews", true); }
    public void changeChannel() {
        if (offer != null && offer.preview && !previews()) offer = null;
        promptedCode = 0;
        if (!busy()) check(true); else notifyState();
    }
    public String currentVersion() {
        try { return installed().versionName; } catch (Exception ex) { return "未知"; }
    }
    public long lastCheck() { return app.config.prefs.getLong("update_last_success", 0); }
    private PackageInfo installed() throws PackageManager.NameNotFoundException {
        return app.getPackageManager().getPackageInfo(app.getPackageName(), signatureFlags());
    }
    private static int signatureFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }
    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
    private void notifyState() { if (listener != null) listener.onUpdateState(); }
    private void deliverOffer() {
        if (listener != null && offer != null && offer.code != promptedCode) {
            promptedCode = offer.code;
            listener.onUpdateAvailable(offer);
        }
    }
    private void state(String text) {
        status = text;
        app.config.putText("update_status", text);
        notifyState();
    }

    public void check(boolean manual) {
        if (busy()) return;
        long now = System.currentTimeMillis();
        if (!manual && !UpdatePolicy.shouldCheck(autoCheck(), now,
                app.config.prefs.getLong("update_last_attempt", 0))) return;
        checking = true;
        app.config.prefs.edit().putLong("update_last_attempt", now).apply();
        state("正在连接 GitHub 检查版本…");
        network.execute(() -> {
            try {
                long current = versionCode(installed());
                boolean includePreview = previews();
                JSONArray releases = new JSONArray(readText(UpdatePolicy.API, 2 * 1024 * 1024));
                UpdatePolicy.Offer selected = null;
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.getJSONObject(i);
                    if (release.optBoolean("draft") || (!includePreview && release.optBoolean("prerelease"))) continue;
                    String tag = release.getString("tag_name");
                    JSONArray assets = release.getJSONArray("assets");
                    JSONObject metadata = findAsset(assets, UpdatePolicy.MANIFEST_NAME);
                    if (metadata == null) continue; // Skip releases from the legacy signing/application channel.
                    String metadataUrl = UpdatePolicy.assetUrl(tag, UpdatePolicy.MANIFEST_NAME);
                    if (!metadataUrl.equals(metadata.getString("browser_download_url"))) throw new IOException("metadata URL");
                    JSONObject json = new JSONObject(readText(metadataUrl, 48 * 1024));
                    UpdatePolicy.Offer candidate = parse(json, tag, release.optBoolean("prerelease"), release.optString("body"));
                    JSONObject apk = findAsset(assets, candidate.apkName);
                    if (apk == null || apk.getLong("size") != candidate.bytes ||
                            !candidate.url().equals(apk.getString("browser_download_url"))) throw new IOException("APK asset");
                    String digest = apk.optString("digest");
                    if (!digest.isEmpty() && !digest.equals("sha256:" + candidate.sha256)) throw new IOException("asset digest");
                    if (candidate.eligible(current, Build.VERSION.SDK_INT, includePreview) &&
                            (selected == null || candidate.code > selected.code)) selected = candidate;
                }
                UpdatePolicy.Offer selectedResult = selected;
                main.post(() -> {
                    UpdatePolicy.Offer result = selectedResult != null && selectedResult.preview && !previews() ? null : selectedResult;
                    checking = false; offer = result;
                    app.config.prefs.edit().putLong("update_last_success", System.currentTimeMillis()).apply();
                    state(result == null ? "当前已是此更新渠道的最新版本" : "发现新版本 " + result.version);
                    if (manual && result != null && listener != null) promptedCode = result.code;
                    if (listener != null) listener.onCheckFinished(manual, null, result != null);
                    deliverOffer();
                });
            } catch (Exception ex) {
                main.post(() -> {
                    checking = false;
                    String error = "检查失败，请确认能访问 GitHub 后重试";
                    state(error);
                    if (listener != null) listener.onCheckFinished(manual, error, offer != null);
                });
            }
        });
    }

    private static JSONObject findAsset(JSONArray assets, String name) throws Exception {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (name.equals(a.optString("name")) && "uploaded".equals(a.optString("state"))) return a;
        }
        return null;
    }
    private static UpdatePolicy.Offer parse(JSONObject json, String tag, boolean preview, String notes) throws Exception {
        if (json.getInt("schemaVersion") != 1 || !UpdatePolicy.APP_ID.equals(json.getString("applicationId")))
            throw new IOException("application metadata");
        return new UpdatePolicy.Offer(tag, json.getString("versionName"), json.getLong("versionCode"),
                json.getInt("minSdk"), json.getString("apkName"), json.getLong("apkSize"),
                json.getString("sha256"), notes, preview);
    }

    private File directory() { return new File(app.getCacheDir(), "updates"); }
    private File readyFile() { return new File(directory(), "update.apk"); }
    public boolean hasDownload() { return readyFile().isFile() && !app.config.text("update_ready", "").isEmpty(); }

    public void download() {
        UpdatePolicy.Offer selected = offer;
        if (busy() || selected == null) return;
        downloading = true;
        state("正在下载 " + selected.version + " · 0%");
        network.execute(() -> {
            File temporary = new File(directory(), "update.apk.part");
            HttpURLConnection connection = null;
            try {
                if (!directory().isDirectory() && !directory().mkdirs()) throw new IOException("cache directory");
                connection = connect(selected.url());
                long declared = connection.getContentLengthLong();
                if (declared > 0 && declared != selected.bytes) throw new IOException("download length");
                long count = 0; int lastPercent = -1;
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024]; int length;
                    while ((length = in.read(buffer)) != -1) {
                        count += length;
                        if (count > selected.bytes) throw new IOException("oversized APK");
                        sha.update(buffer, 0, length); out.write(buffer, 0, length);
                        int percent = (int)(count * 100 / selected.bytes);
                        if (percent / 5 != lastPercent / 5 || lastPercent < 0) {
                            lastPercent = percent;
                            main.post(() -> state("正在下载 " + selected.version + " · " + percent + "%"));
                        }
                    }
                }
                if (count != selected.bytes || !hex(sha.digest()).equals(selected.sha256)) throw new IOException("SHA-256 mismatch");
                validateApk(temporary, selected);
                if (!temporary.renameTo(readyFile())) throw new IOException("cache rename");
                JSONObject saved = new JSONObject().put("schemaVersion", 1).put("applicationId", UpdatePolicy.APP_ID)
                        .put("versionName", selected.version).put("versionCode", selected.code).put("minSdk", selected.minSdk)
                        .put("apkName", selected.apkName).put("apkSize", selected.bytes).put("sha256", selected.sha256);
                app.config.putText("update_ready", saved.toString());
                main.post(() -> { downloading = false; state("新版本下载完成，已校验哈希和签名"); if (listener != null) listener.onDownloadReady(); });
            } catch (Exception ex) {
                main.post(() -> {
                    downloading = false; state("下载或安全校验失败，未安装。请重试或前往发布页下载");
                    if (listener != null) listener.onUpdateError(status);
                });
            } finally {
                if (connection != null) connection.disconnect();
                if (temporary.exists()) temporary.delete();
            }
        });
    }

    public void verifyForInstall() {
        if (busy()) return;
        verifying = true; state("正在复核安装包…");
        network.execute(() -> {
            try {
                JSONObject saved = new JSONObject(app.config.text("update_ready", ""));
                UpdatePolicy.Offer ready = parse(saved, "v" + saved.getString("versionName"), false, "");
                validateApk(readyFile(), ready);
                main.post(() -> {
                    verifying = false; state("安装包校验通过，请在系统页面确认安装");
                    if (listener != null) listener.onInstallVerified(readyFile());
                });
            } catch (Exception ex) {
                readyFile().delete(); app.config.putText("update_ready", "");
                main.post(() -> {
                    verifying = false; state("安装包已失效或校验失败，请重新检查并下载");
                    if (listener != null) listener.onUpdateError(status);
                });
            }
        });
    }

    private void validateApk(File file, UpdatePolicy.Offer selected) throws Exception {
        if (!file.isFile() || file.length() != selected.bytes) throw new IOException("APK size");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        if (!hex(digest.digest()).equals(selected.sha256)) throw new IOException("APK digest");
        PackageInfo current = installed();
        PackageInfo archive = app.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), signatureFlags());
        if (archive == null || !app.getPackageName().equals(archive.packageName) ||
                versionCode(archive) != selected.code || !selected.version.equals(archive.versionName) ||
                !selected.eligible(versionCode(current), Build.VERSION.SDK_INT, true) ||
                archive.applicationInfo == null || archive.applicationInfo.minSdkVersion != selected.minSdk ||
                !certificates(current).equals(certificates(archive)) || certificates(current).isEmpty())
            throw new IOException("APK identity/signature/version");
    }

    @SuppressWarnings("deprecation")
    private static Set<String> certificates(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 ?
                (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) for (Signature s : signatures)
            result.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
        return result;
    }
    private static String hex(byte[] data) {
        StringBuilder result = new StringBuilder();
        for (byte b : data) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private static String readText(String url, int limit) throws Exception {
        HttpURLConnection connection = connect(url);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > limit) throw new IOException("metadata too large");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally { connection.disconnect(); }
    }
    private static HttpURLConnection connect(String address) throws Exception {
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!UpdatePolicy.trustedTransport(address)) throw new IOException("untrusted update host");
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "QingJi-Android-Updater");
            connection.setRequestProperty("Accept", "application/json, application/octet-stream");
            try {
                int code = connection.getResponseCode();
                if (code == 200) return connection;
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("redirect without location");
                    address = new URL(new URL(address), location).toString();
                    connection.disconnect();
                } else throw new IOException("HTTP " + code);
            } catch (Exception ex) { connection.disconnect(); throw ex; }
        }
        throw new IOException("too many redirects");
    }
}
