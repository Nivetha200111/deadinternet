package dev.deadinternet.capture;

import dev.deadinternet.model.Account;
import dev.deadinternet.model.Conversation;
import dev.deadinternet.model.Post;
import dev.deadinternet.model.Reply;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Accumulates scans while the page scrolls. Both sites virtualize their threads, so each scan sees only what is
 * currently rendered; replies are merged by id and kept in the order they were first seen.
 */
final class CapturedThread {

    private final int maxReplies;
    private ThreadScan.Item post;
    private final Map<String, ThreadScan.Item> replies = new LinkedHashMap<>();
    private final Set<String> skipped = new HashSet<>();

    CapturedThread(int maxReplies) {
        this.maxReplies = maxReplies;
    }

    void add(ThreadScan scan) {
        if (scan == null) return;
        // Keep the first good read of the post; it scrolls out of the DOM later.
        if (scan.post() != null && (post == null || (!post.hasText() && scan.post().hasText()))) post = scan.post();
        for (var reply : scan.replies()) {
            if (replies.containsKey(reply.id()) || replies.size() >= maxReplies) continue;
            if (post != null && reply.id().equals(post.id())) continue;
            // Media-only replies can't be compared as text.
            if (!reply.hasText()) {
                skipped.add(reply.id());
                continue;
            }
            skipped.remove(reply.id());
            replies.put(reply.id(), reply);
        }
    }

    boolean hasPost() {
        return post != null;
    }

    int size() {
        return replies.size();
    }

    int skipped() {
        return skipped.size();
    }

    boolean full() {
        return replies.size() >= maxReplies;
    }

    /** Handle-only accounts: neither site shows account metadata in a thread. */
    Conversation toConversation() {
        if (post == null) throw new IllegalStateException("The original post was not found on the page");
        Instant postedAt = Instant.parse(post.createdAt());
        var accounts = new LinkedHashMap<String, Account>();
        var list = replies.values().stream().map(r -> {
            String id = r.handle().toLowerCase();
            var author = accounts.computeIfAbsent(id, k -> Account.handleOnly(k, r.handle()));
            Instant at = Instant.parse(r.createdAt());
            // Coarse relative timestamps can land before the post; a reply always follows it.
            return new Reply(r.id(), author, r.text(), at.isBefore(postedAt) ? postedAt : at);
        }).toList();
        String text = post.hasText() ? post.text() : "(post without text)";
        return new Conversation(new Post(post.id(), post.handle(), text, postedAt), list);
    }
}
