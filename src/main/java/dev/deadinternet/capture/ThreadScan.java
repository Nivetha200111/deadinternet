package dev.deadinternet.capture;

import java.util.List;

/** What collector.js reads from the page in one pass; createdAt is an ISO-8601 instant. */
public record ThreadScan(String site, String threadId, Item post, List<Item> replies) {

    public record Item(String id, String handle, String text, String createdAt) {
        boolean hasText() {
            return text != null && !text.isBlank();
        }
    }
}
