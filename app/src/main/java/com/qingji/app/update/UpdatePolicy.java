package com.qingji.app.update;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Android-independent release selection and download trust boundaries. */
public final class UpdatePolicy {
    public static final String REPO = "Iloveshe/qingji-android";
    public static final String APP_ID = "com.qingji.app.next";
    // Legacy installs only read update.json; keep their update checks on their own channel.
    public static final String MANIFEST_NAME = "update-next.json";
    public static final String RELEASES = "https://github.com/" + REPO + "/releases";
    public static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=30";
    public static final long MAX_APK_BYTES = 128L * 1024 * 1024;
    public static final long CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000;
    private static final Set<String> HOSTS = Set.of("api.github.com", "github.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com", "objects-origin.githubusercontent.com");

    private UpdatePolicy() {}

    public static boolean shouldCheck(boolean enabled, long now, long last) {
        return enabled && (last <= 0 || now < last || now - last >= CHECK_INTERVAL_MS);
    }

    public static boolean trustedTransport(String address) {
        try {
            URI u = URI.create(address);
            return "https".equals(u.getScheme()) && u.getUserInfo() == null &&
                    (u.getPort() == -1 || u.getPort() == 443) && u.getFragment() == null &&
                    HOSTS.contains(u.getHost());
        } catch (IllegalArgumentException ex) { return false; }
    }

    public static String assetUrl(String tag, String name) {
        if (tag == null || !tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?") ||
                name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}") || name.contains(".."))
            throw new IllegalArgumentException("Invalid release asset");
        return "https://github.com/" + REPO + "/releases/download/" + tag + "/" + name;
    }

    public static final class Offer {
        public final String tag, version, apkName, sha256, notes;
        public final long code, bytes;
        public final int minSdk;
        public final boolean preview;

        public Offer(String tag, String version, long code, int minSdk, String apkName,
                     long bytes, String sha256, String notes, boolean preview) {
            assetUrl(tag, apkName);
            if (version == null || !tag.equals("v" + version) || code <= 0 || minSdk < 26 ||
                    bytes <= 0 || bytes > MAX_APK_BYTES || !apkName.endsWith(".apk") ||
                    sha256 == null || !sha256.matches("[a-fA-F0-9]{64}"))
                throw new IllegalArgumentException("Invalid update metadata");
            this.tag = tag; this.version = version; this.code = code; this.minSdk = minSdk;
            this.apkName = apkName; this.bytes = bytes; this.sha256 = sha256.toLowerCase(Locale.ROOT);
            this.notes = notes == null ? "" : notes.substring(0, Math.min(notes.length(), 6000));
            this.preview = preview;
        }

        public boolean eligible(long installedCode, int deviceSdk, boolean includePreview) {
            return code > installedCode && minSdk <= deviceSdk && (!preview || includePreview);
        }

        public String url() { return assetUrl(tag, apkName); }
        public String page() { return RELEASES + "/tag/" + tag; }
    }
}
