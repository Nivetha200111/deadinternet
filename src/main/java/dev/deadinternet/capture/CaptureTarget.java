package dev.deadinternet.capture;

import java.util.regex.Pattern;

/**
 * What the live capture reads: a single X / LinkedIn post (its replies), or the signed-in home feed.
 *
 * @param feed true for a home feed: many accounts' posts rather than one post's replies
 */
public record CaptureTarget(String site, String url, boolean feed) {

    public CaptureTarget(String site, String url) {
        this(site, url, false);
    }

    public static final CaptureTarget X_FEED = new CaptureTarget("X", "https://x.com/home", true);
    public static final CaptureTarget LINKEDIN_FEED = new CaptureTarget("LinkedIn", "https://www.linkedin.com/feed/", true);

    private static final Pattern X = Pattern.compile(
            "^https?://(?:www\\.|mobile\\.)?(?:x|twitter)\\.com/([A-Za-z0-9_]{1,15})/status/(\\d+)(?:[/?#].*)?$");
    private static final Pattern X_HOME = Pattern.compile("^https?://(?:www\\.|mobile\\.)?(?:x|twitter)\\.com/?(?:home/?)?(?:[?#].*)?$");
    private static final Pattern LINKEDIN_HOME = Pattern.compile("^https?://(?:www\\.)?linkedin\\.com/(?:feed/?)?(?:[?#].*)?$");
    private static final Pattern LINKEDIN = Pattern.compile(
            "^https?://(?:www\\.)?linkedin\\.com/(feed/update/urn:li:(?:activity|ugcPost|share):\\d+|posts/[^/?#]*-(?:activity|ugcPost|share)-\\d+[^/?#]*)/?(?:[?#].*)?$");

    /** @throws IllegalArgumentException for anything that is not an X / LinkedIn post or home feed */
    public static CaptureTarget parse(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (X_HOME.matcher(url).matches()) return X_FEED;
        if (LINKEDIN_HOME.matcher(url).matches()) return LINKEDIN_FEED;
        var x = X.matcher(url);
        if (x.matches()) return new CaptureTarget("X", "https://x.com/" + x.group(1) + "/status/" + x.group(2));
        var linkedIn = LINKEDIN.matcher(url);
        if (linkedIn.matches()) return new CaptureTarget("LinkedIn", "https://www.linkedin.com/" + linkedIn.group(1) + "/");
        throw new IllegalArgumentException(
                "Use your X or LinkedIn feed, or paste a single post URL like x.com/<user>/status/<id>");
    }
}
