package dev.deadinternet.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import dev.deadinternet.config.LensProperties;
import dev.deadinternet.dto.GraphView;
import dev.deadinternet.model.Conversation;
import dev.deadinternet.service.ConversationAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live capture: opens a real X / LinkedIn post in the browser the server drives, scrolls the thread while reading
 * it with collector.js, analyzes what it collected, then draws classification badges into the live page. Screenshots
 * are kept so the UI can show the page scrolling.
 */
@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);
    private static final int FRAME_MS = 250;

    enum Stage { STARTING, LOADING, NEEDS_LOGIN, SCROLLING, ANALYZING, COMPLETE, FAILED }

    /** Mutable progress for one capture; written on the browser thread, read by status polls. */
    static final class Run {
        final String id;
        final CaptureTarget target;
        volatile Stage stage = Stage.STARTING;
        volatile int collected, skipped;
        volatile String message, analysisId;
        volatile byte[] frame;
        volatile long frameVersion;
        volatile boolean stopRequested;

        Run(String id, CaptureTarget target) {
            this.id = id;
            this.target = target;
        }

        boolean finished() {
            return stage == Stage.COMPLETE || stage == Stage.FAILED || stage == Stage.NEEDS_LOGIN;
        }

        CaptureStatus snapshot() {
            return new CaptureStatus(id, target.url(), target.site(), stage.name(), collected, skipped, message,
                    analysisId, frameVersion);
        }
    }

    private final BrowserSession browser;
    private final ConversationAnalysisService analyses;
    private final ObjectMapper mapper;
    private final LensProperties.Capture config;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private volatile Run active;

    public CaptureService(BrowserSession browser, ConversationAnalysisService analyses, ObjectMapper mapper,
                          LensProperties properties) {
        this.browser = browser;
        this.analyses = analyses;
        this.mapper = mapper;
        this.config = properties.capture();
    }

    public boolean enabled() {
        return config.enabled();
    }

    private void requireEnabled() {
        if (!config.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Live capture runs only in the local Lens app");
        }
    }

    public CaptureStatus start(String url) {
        requireEnabled();
        try {
            if (config.allowLocalPages() && url != null && url.matches("http://(localhost|127\\.0\\.0\\.1):\\d+/.*")) {
                boolean feed = url.matches(".*/(home|feed/)$");
                boolean linkedIn = url.contains("/feed/");
                return start(new CaptureTarget(linkedIn ? "LinkedIn" : "X", url, feed));
            }
            return start(CaptureTarget.parse(url));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Visible for tests, which capture local fixture pages instead of the real sites. */
    synchronized CaptureStatus start(CaptureTarget target) {
        if (active != null && !active.finished()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A capture is already running");
        }
        var run = new Run(UUID.randomUUID().toString().replace("-", ""), target);
        runs.put(run.id, run);
        active = run;
        browser.submit(() -> {
            execute(run);
            return null;
        });
        return run.snapshot();
    }

    public CaptureStatus status(String id) {
        return find(id).snapshot();
    }

    public Optional<byte[]> frame(String id) {
        return Optional.ofNullable(find(id).frame);
    }

    /** Ends scrolling early; whatever was collected so far is analyzed. */
    public CaptureStatus stop(String id) {
        var run = find(id);
        run.stopRequested = true;
        return run.snapshot();
    }

    /** Opens a plain, non-automated Chrome window on the site's login page; see {@link BrowserSession#openSignIn}. */
    public void openSignIn(String site) {
        requireEnabled();
        if (active != null && !active.finished()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for the running capture to finish first");
        }
        String url = "LinkedIn".equalsIgnoreCase(site) ? "https://www.linkedin.com/login" : "https://x.com/i/flow/login";
        try {
            browser.openSignIn(url);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Couldn't open Chrome for sign-in (" + e.getMessage() + "). Check lens.capture.chrome-path.");
        }
    }

    public boolean signInOpen() {
        return browser.signInOpen();
    }

    /** Brings the capture window forward, for example so the user can log in. */
    public void showBrowser() {
        requireEnabled();
        browser.submit(() -> {
            browser.page().bringToFront();
            return null;
        });
    }

    private Run find(String id) {
        var run = runs.get(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Capture not found");
        return run;
    }

    // ------------------------------------------------------------------ the capture itself (browser thread)

    private void execute(Run run) {
        try {
            run.stage = Stage.LOADING;
            run.message = run.target.feed() ? "Opening your " + run.target.site() + " feed" : "Opening the post";
            Page page = browser.page();
            page.bringToFront();
            page.navigate(run.target.url(), new Page.NavigateOptions()
                    .setTimeout(45_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            Conversation conversation = run.target.feed() ? collectFeed(run, page) : collectThread(run, page);
            if (conversation != null) analyzeAndBadge(run, page, conversation);
        } catch (PlaywrightException e) {
            log.warn("Capture {} failed: {}", run.id, e.getMessage());
            fail(run, "The browser stopped responding: " + firstLine(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Capture {} failed", run.id, e);
            fail(run, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** One post's replies. Returns null when the run already ended (login wall or nothing found). */
    private Conversation collectThread(Run run, Page page) {
        var thread = new CapturedThread(config.maxReplies());
        // Wait for the post to render; a login wall shows up as a redirect.
        for (int i = 0; i < 60 && !thread.hasPost(); i++) {
            capture(page, run);
            if (loginRequired(page.url())) {
                needsLogin(run, page);
                return null;
            }
            thread.add(scan(page));
            if (!thread.hasPost()) page.waitForTimeout(FRAME_MS);
        }
        if (!thread.hasPost()) {
            fail(run, "Couldn't find the post on the page. If " + run.target.site()
                    + " is asking you to log in, do that in the Lens browser window and try again.");
            return null;
        }
        run.message = "Scrolling the thread";
        scroll(run, page, true, () -> {
            thread.add(scan(page));
            run.skipped = thread.skipped();
            return thread.size();
        }, thread::full);
        if (thread.size() == 0) {
            fail(run, "No replies with text were found under this post.");
            return null;
        }
        return thread.toConversation();
    }

    /** The signed-in home feed: many accounts' posts. Returns null when the run already ended. */
    private Conversation collectFeed(Run run, Page page) {
        var feed = new CapturedFeed(config.maxReplies());
        for (int i = 0; i < 80 && feed.size() == 0; i++) {
            capture(page, run);
            if (loginRequired(page.url())) {
                needsLogin(run, page);
                return null;
            }
            feed.add(scanFeed(page));
            if (feed.size() == 0) page.waitForTimeout(FRAME_MS);
        }
        if (feed.size() == 0) {
            // Logged-out visitors are sent to a landing page instead of the feed.
            needsLogin(run, page);
            return null;
        }
        run.message = "Scrolling your feed";
        scroll(run, page, false, () -> {
            feed.add(scanFeed(page));
            run.skipped = feed.skipped();
            run.message = "Scrolling your feed · " + feed.accounts() + " accounts so far";
            return feed.size();
        }, feed::full);
        return feed.toConversation(run.target.site(), Instant.now());
    }

    /**
     * Scrolls until the page stops yielding new items, the cap or time limit is reached, or the user stops it.
     * LinkedIn is quick to flag automated browsing, so it is paced more slowly.
     */
    private void scroll(Run run, Page page, boolean openHiddenSections, IntSupplier collect,
                        BooleanSupplier full) {
        run.stage = Stage.SCROLLING;
        long deadline = System.currentTimeMillis() + config.maxMinutes() * 60_000L;
        int stepMs = "LinkedIn".equals(run.target.site()) ? 2_500 : 1_250;
        run.collected = collect.getAsInt();
        int idle = 0, last = run.collected;
        while (!run.stopRequested && idle < 5 && !full.getAsBoolean() && System.currentTimeMillis() < deadline) {
            if (openHiddenSections) page.evaluate("() => window.DeadInternetLensCollector.clickMore()");
            page.evaluate("() => window.scrollBy({ top: window.innerHeight * 0.8, behavior: 'smooth' })");
            for (int waited = 0; waited < stepMs; waited += FRAME_MS) {
                page.waitForTimeout(FRAME_MS);
                capture(page, run);
            }
            run.collected = collect.getAsInt();
            idle = run.collected == last ? idle + 1 : 0;
            last = run.collected;
        }
    }

    private void analyzeAndBadge(Run run, Page page, Conversation conversation) {
        run.stage = Stage.ANALYZING;
        String noun = conversation.isFeed() ? " posts" : "LinkedIn".equals(run.target.site()) ? " comments" : " replies";
        run.message = "Analyzing " + conversation.replies().size() + noun;
        var analysis = analyses.start(conversation, false);
        run.analysisId = analysis.id();
        while ("RUNNING".equals(analysis.status())) {
            page.waitForTimeout(FRAME_MS);
            analysis = analyses.status(analysis.id());
        }
        if (!"COMPLETE".equals(analysis.status())) {
            fail(run, "Analysis failed: " + analysis.error());
            return;
        }
        // Badge the items in the live page; an observer keeps re-badging as the site re-renders.
        page.evaluate("""
                (items) => {
                  const C = window.DeadInternetLensCollector;
                  const rescan = () => (C.detect()?.isFeed() ? C.scanFeed() : C.scan());
                  window.__lensResults = new Map(items.map((i) => [i.replyId, i]));
                  C.clearBadges();
                  rescan();
                  C.decorate(window.__lensResults);
                  if (window.__lensObserver) return;
                  let pending = false;
                  window.__lensObserver = new MutationObserver(() => {
                    if (pending) return;
                    pending = true;
                    setTimeout(() => { pending = false; rescan(); C.decorate(window.__lensResults); }, 300);
                  });
                  window.__lensObserver.observe(document.body, { childList: true, subtree: true });
                  window.scrollTo({ top: 0, behavior: 'smooth' });
                }
                """, badgeItems(analyses.graph(analysis.id())));
        for (int i = 0; i < 6; i++) {
            page.waitForTimeout(FRAME_MS);
            capture(page, run);
        }
        run.message = conversation.replies().size() + noun + " analyzed";
        run.stage = Stage.COMPLETE;
        log.info("Capture {} of {} complete: {}{}, analysis {}", run.id, run.target.url(),
                conversation.replies().size(), noun, analysis.id());
    }

    private FeedScan scanFeed(Page page) {
        Object raw = page.evaluate("() => window.DeadInternetLensCollector ? window.DeadInternetLensCollector.scanFeed() : null");
        return raw == null ? null : mapper.convertValue(raw, FeedScan.class);
    }

    private ThreadScan scan(Page page) {
        Object raw = page.evaluate("() => window.DeadInternetLensCollector ? window.DeadInternetLensCollector.scan() : null");
        return raw == null ? null : mapper.convertValue(raw, ThreadScan.class);
    }

    private void capture(Page page, Run run) {
        try {
            run.frame = page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(60));
            run.frameVersion++;
        } catch (PlaywrightException e) {
            log.debug("Screenshot skipped: {}", e.getMessage());
        }
    }

    static boolean loginRequired(String url) {
        return url.contains("/i/flow/login") || url.matches("https://(www\\.)?(x|twitter)\\.com/login.*")
                || url.contains("linkedin.com/authwall") || url.contains("linkedin.com/login")
                || url.contains("linkedin.com/uas/login") || url.contains("linkedin.com/checkpoint");
    }

    private void needsLogin(Run run, Page page) {
        run.message = run.target.site() + " needs you to sign in. Press Sign in: a normal Chrome window opens, sign in "
                + "there yourself, then close that window. Lens never sees your password, and the login is remembered.";
        run.stage = Stage.NEEDS_LOGIN;
        page.bringToFront();
    }

    private static void fail(Run run, String message) {
        run.message = message;
        run.stage = Stage.FAILED;
    }

    private static String firstLine(String message) {
        return message == null ? "unknown error" : message.lines().findFirst().orElse(message);
    }

    /** Per-reply badge data; labels use the server's default thresholds. */
    static List<Map<String, Object>> badgeItems(GraphView graph) {
        var clusterIndex = new HashMap<String, Integer>();
        graph.clusters().forEach(c -> clusterIndex.put(c.id(), c.index()));
        var items = new ArrayList<Map<String, Object>>();
        for (var a : graph.accounts()) {
            for (var reply : a.replies()) {
                var item = new HashMap<String, Object>();
                item.put("replyId", reply.id());
                item.put("username", a.username());
                item.put("classification", a.classification());
                item.put("automation", a.automationLikelihood());
                item.put("coordination", a.coordinationLikelihood());
                item.put("cluster", a.clusterId() == null ? null : clusterIndex.get(a.clusterId()));
                items.add(item);
            }
        }
        return items;
    }
}
