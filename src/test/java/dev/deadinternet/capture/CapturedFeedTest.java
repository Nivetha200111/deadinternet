package dev.deadinternet.capture;

import dev.deadinternet.model.Conversation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapturedFeedTest {

    private static ThreadScan.Item post(String id, String handle, String text, String at) {
        return new ThreadScan.Item(id, handle, text, at);
    }

    @Test
    void feedPostsHangOffASyntheticRootBeforeTheOldestPost() {
        var feed = new CapturedFeed(400);
        feed.add(new FeedScan("X", List.of(
                post("10", "alice", "Shipping a new release today", "2026-10-06T12:00:00.000Z"),
                post("11", "bot_1", "Exactly this. Everyone needs to pay attention.", "2026-10-06T09:30:00.000Z"),
                post("12", "photo", "", "2026-10-06T10:00:00.000Z"))));
        feed.add(new FeedScan("X", List.of(
                post("11", "bot_1", "Exactly this. Everyone needs to pay attention.", "2026-10-06T09:30:00.000Z"),
                post("13", "Bot_1", "Exactly this! Everyone needs to pay attention.", "2026-10-06T09:31:00.000Z"))));

        assertThat(feed.size()).isEqualTo(3);
        assertThat(feed.skipped()).isEqualTo(1);
        assertThat(feed.accounts()).isEqualTo(2);
        var conversation = feed.toConversation("X", Instant.parse("2026-10-06T13:00:00Z"));
        assertThat(conversation.kind()).isEqualTo(Conversation.FEED);
        assertThat(conversation.isFeed()).isTrue();
        assertThat(conversation.post().createdAt()).isEqualTo(Instant.parse("2026-10-06T09:29:59Z"));
        assertThat(conversation.post().text()).contains("3 posts from 2 accounts");
        assertThat(conversation.replies()).extracting(r -> r.author().id()).containsExactly("alice", "bot_1", "bot_1");
        assertThat(conversation.replies()).allSatisfy(r -> assertThat(r.createdAt()).isAfter(conversation.post().createdAt()));
    }

    @Test
    void emptyFeedCannotBeAnalyzed() {
        assertThatThrownBy(() -> new CapturedFeed(10).toConversation("LinkedIn", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void threadIsTheDefaultKind() {
        assertThat(dev.deadinternet.Fixtures.conversation(dev.deadinternet.Fixtures.established("a"), "x", 1).kind())
                .isEqualTo(Conversation.THREAD);
    }
}
