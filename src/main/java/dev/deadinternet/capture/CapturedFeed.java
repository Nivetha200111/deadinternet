package dev.deadinternet.capture;

import dev.deadinternet.model.Account;
import dev.deadinternet.model.Conversation;
import dev.deadinternet.model.Post;
import dev.deadinternet.model.Reply;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Accumulates the posts seen while scrolling a home feed. The analysis treats them like replies under a synthetic
 * root post that stands for the feed, so the same text, timing and coordination signals apply across accounts.
 */
final class CapturedFeed {

    private final int maxPosts;
    private final Map<String, ThreadScan.Item> posts = new LinkedHashMap<>();
    private final Set<String> skipped = new HashSet<>();

    CapturedFeed(int maxPosts) {
        this.maxPosts = maxPosts;
    }

    void add(FeedScan scan) {
        if (scan == null) return;
        for (var item : scan.items()) {
            if (posts.containsKey(item.id()) || posts.size() >= maxPosts) continue;
            if (!item.hasText()) {
                skipped.add(item.id());
                continue;
            }
            skipped.remove(item.id());
            posts.put(item.id(), item);
        }
    }

    int size() {
        return posts.size();
    }

    int skipped() {
        return skipped.size();
    }

    boolean full() {
        return posts.size() >= maxPosts;
    }

    long accounts() {
        return posts.values().stream().map(p -> p.handle().toLowerCase()).distinct().count();
    }

    Conversation toConversation(String site, Instant capturedAt) {
        if (posts.isEmpty()) throw new IllegalStateException("No posts with text were found in the feed");
        // The root sits just before the oldest post, so every feed post "follows" it.
        Instant oldest = posts.values().stream().map(p -> Instant.parse(p.createdAt()))
                .min(Comparator.naturalOrder()).orElseThrow();
        var accounts = new LinkedHashMap<String, Account>();
        var items = posts.values().stream().map(p -> {
            String id = p.handle().toLowerCase();
            var author = accounts.computeIfAbsent(id, k -> Account.handleOnly(k, p.handle()));
            return new Reply(p.id(), author, p.text(), Instant.parse(p.createdAt()));
        }).toList();
        var root = new Post("feed-" + capturedAt.getEpochSecond(), "your " + site + " feed",
                "Your " + site + " feed: " + posts.size() + " posts from " + accounts.size() + " accounts, captured "
                        + capturedAt.toString().substring(0, 16).replace('T', ' ') + " UTC",
                oldest.minusSeconds(1));
        return new Conversation(root, items, Conversation.FEED);
    }
}
