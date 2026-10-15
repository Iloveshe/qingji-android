import com.qingji.app.update.UpdatePolicy;

public final class UpdateTests {
    private static int passed;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++;
    }
    private static void rejects(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException expected) { passed++; return; }
        throw new AssertionError(label);
    }
    private static UpdatePolicy.Offer offer(long code, int sdk, boolean preview) {
        return new UpdatePolicy.Offer("v0.1.2", "0.1.2", code, sdk, "qingji-0.1.2-debug.apk",
                50_000_000, "ab".repeat(32), "Release notes", preview);
    }
    public static void main(String[] args) {
        long now = 100_000_000;
        check(UpdatePolicy.shouldCheck(true, now, 0), "First launch checks");
        check(!UpdatePolicy.shouldCheck(false, now, 0), "Disabled never auto-checks");
        check(!UpdatePolicy.shouldCheck(true, now, now - 60_000), "Repeated launches are throttled");
        check(UpdatePolicy.shouldCheck(true, now, now - UpdatePolicy.CHECK_INTERVAL_MS), "Interval boundary checks");
        check(UpdatePolicy.shouldCheck(true, now, now + 1), "Clock reset does not suppress checks forever");
        check(offer(3,26,false).eligible(2,36,false), "New compatible stable update");
        check(!offer(2,26,false).eligible(2,36,true), "Same version never offered");
        check(!offer(1,26,false).eligible(2,36,true), "Downgrade never offered");
        check(!offer(3,37,false).eligible(2,36,true), "Unsupported Android version rejected");
        check(!offer(3,26,true).eligible(2,36,false), "Stable channel excludes preview");
        check(offer(3,26,true).eligible(2,36,true), "Preview channel includes preview");
        check(offer(10,26,false).eligible(9,36,false), "Numeric versionCode order");
        check(UpdatePolicy.trustedTransport(UpdatePolicy.API), "GitHub metadata accepted");
        check(UpdatePolicy.trustedTransport("https://release-assets.githubusercontent.com/path?sig=abc"), "Signed CDN redirect accepted");
        check(!UpdatePolicy.trustedTransport("http://github.com/a"), "HTTPS downgrade rejected");
        check(!UpdatePolicy.trustedTransport("https://github.com.evil.test/a"), "Lookalike host rejected");
        check(!UpdatePolicy.trustedTransport("https://github.com@evil.test/a"), "User-info host trick rejected");
        check(!UpdatePolicy.trustedTransport("https://evil@github.com/a"), "Credentials in URL rejected");
        check(!UpdatePolicy.trustedTransport("https://github.com:444/a"), "Unexpected port rejected");
        check(!UpdatePolicy.trustedTransport("https://github.com/a#fragment"), "Fragment rejected");
        check(!UpdatePolicy.trustedTransport("file:///tmp/update.apk"), "Local URL rejected");
        check(!UpdatePolicy.trustedTransport("https://raw.githubusercontent.com/other/repo/main/app.apk"), "Other content hosts rejected");
        check(UpdatePolicy.assetUrl("v0.1.2", "update.json").equals(
                "https://github.com/Iloveshe/qingji-android/releases/download/v0.1.2/update.json"), "Pinned repository path");
        rejects(() -> UpdatePolicy.assetUrl("../../other", "update.json"), "Tag path traversal");
        rejects(() -> UpdatePolicy.assetUrl("v0.1.2", "../update.apk"), "Asset path traversal");
        rejects(() -> UpdatePolicy.assetUrl("v0.1.2", "app.apk?url=evil"), "Asset query injection");
        rejects(() -> new UpdatePolicy.Offer("v0.1.3","0.1.2",3,26,"app.apk",10,"ab".repeat(32),"",false), "Tag/version mismatch");
        rejects(() -> new UpdatePolicy.Offer("v0.1.2","0.1.2",3,26,"app.apk",0,"ab".repeat(32),"",false), "Empty package");
        rejects(() -> new UpdatePolicy.Offer("v0.1.2","0.1.2",3,26,"app.apk",UpdatePolicy.MAX_APK_BYTES+1,"ab".repeat(32),"",false), "Oversized package");
        rejects(() -> new UpdatePolicy.Offer("v0.1.2","0.1.2",3,26,"app.apk",10,"bad-hash","",false), "Invalid digest");
        rejects(() -> new UpdatePolicy.Offer("v0.1.2","0.1.2",3,26,"app.zip",10,"ab".repeat(32),"",false), "Not an APK");
        System.out.println("Update policy tests passed: " + passed);
    }
}
