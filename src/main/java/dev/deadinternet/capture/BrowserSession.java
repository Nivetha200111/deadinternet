package dev.deadinternet.capture;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.deadinternet.config.LensProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The real browser window the live capture drives. Launched lazily with a persistent profile of its own, so the
 * user logs in once (themselves, in that window) and stays logged in. Playwright is not thread-safe, so every
 * browser call runs on one dedicated thread via {@link #submit}.
 */
@Component
public class BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    private final LensProperties.Capture config;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "lens-browser");
        t.setDaemon(true);
        return t;
    });
    private Playwright playwright;
    private BrowserContext context;

    public BrowserSession(LensProperties properties) {
        this.config = properties.capture();
    }

    public <T> Future<T> submit(Callable<T> task) {
        return thread.submit(task);
    }

    /** The capture tab, launching the browser if needed. Call only from a task passed to {@link #submit}. */
    Page page() {
        if (signInOpen()) throw new IllegalStateException("Finish signing in and close the sign-in window first");
        if (context == null) launch();
        var pages = context.pages();
        return pages.isEmpty() ? context.newPage() : pages.getFirst();
    }

    private void launch() {
        if (playwright == null) {
            // Drive the installed browser; don't download Playwright's own.
            playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        }
        Path profile = Path.of(config.profileDir());
        try {
            Files.createDirectories(profile);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't create browser profile at " + profile, e);
        }
        var options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(config.headless())
                .setViewportSize(1280, 900)
                // Lets the reader and badge styles run on sites with strict content security policies.
                .setBypassCSP(true)
                // Use the real system keychain, so a login made in the plain sign-in window (see openSignIn) is
                // readable here: both windows share one profile.
                .setIgnoreDefaultArgs(List.of("--use-mock-keychain"));
        if (config.channel() != null && !config.channel().isBlank()) options.setChannel(config.channel());
        context = playwright.chromium().launchPersistentContext(profile, options);
        context.addInitScript(script("collector/collector.js") + badgeStyles());
        context.onClose(c -> context = null);
        log.info("Capture browser launched with profile {}", profile);
    }

    private static String badgeStyles() {
        String css = script("collector/content.css").replace("\\", "\\\\").replace("`", "\\`").replace("${", "\\${");
        return "\n;(() => { const add = () => { if (document.getElementById('dil-styles')) return;"
                + " const s = document.createElement('style'); s.id = 'dil-styles'; s.textContent = `" + css + "`;"
                + " (document.head || document.documentElement).appendChild(s); };"
                + " document.readyState === 'loading' ? document.addEventListener('DOMContentLoaded', add) : add(); })();";
    }

    private static String script(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing " + path + " on the classpath", e);
        }
    }

    private volatile Process signIn;

    /**
     * Opens a plain Chrome window, not driven by automation, on the capture profile so the user can sign in the
     * normal way (sites refuse sign-in from automated browsers). The automated window closes first because Chrome
     * locks a profile to one process. When the user closes this window, the login is saved in the profile.
     */
    public void openSignIn(String loginUrl) throws Exception {
        if (signInOpen()) return;
        submit(() -> {
            if (context != null) context.close();
            context = null;
            return null;
        }).get();
        Path profile = Path.of(config.profileDir());
        Files.createDirectories(profile);
        signIn = new ProcessBuilder(config.chromePath(), "--user-data-dir=" + profile.toAbsolutePath(),
                "--no-first-run", "--no-default-browser-check", "--new-window", loginUrl)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        log.info("Opened sign-in window at {}", loginUrl);
    }

    public boolean signInOpen() {
        return signIn != null && signIn.isAlive();
    }

    @PreDestroy
    void close() {
        if (signInOpen()) signIn.destroy();
        thread.submit(() -> {
            try {
                if (context != null) context.close();
                if (playwright != null) playwright.close();
            } catch (RuntimeException e) {
                log.debug("Closing capture browser: {}", e.getMessage());
            }
        });
        thread.shutdown();
    }
}
