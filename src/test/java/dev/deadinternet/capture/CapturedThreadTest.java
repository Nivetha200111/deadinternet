package dev.deadinternet.capture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapturedThreadTest {

    private static ThreadScan.Item item(String id, String handle, String text, String at) {
        return new ThreadScan.Item(id, handle, text, at);
    }

    private static final ThreadScan.Item POST = item("1", "root", "The post", "2026-10-06T10:00:00.000Z");

    @Test
    void mergesScansByIdInFirstSeenOrderAndSkipsMediaOnlyReplies() {
        var thread = new CapturedThread(400);
        thread.add(new ThreadScan("X", "1", POST, List.of(
                item("2", "Alice", "first", "2026-10-06T10:01:00.000Z"),
                item("3", "bob", "", "2026-10-06T10:02:00.000Z"))));
        thread.add(new ThreadScan("X", "1", null, List.of(
                item("2", "Alice", "first", "2026-10-06T10:01:00.000Z"),
                item("4", "carol", "second", "2026-10-06T10:03:00.000Z"))));

        assertThat(thread.size()).isEqualTo(2);
        assertThat(thread.skipped()).isEqualTo(1);
        var conversation = thread.toConversation();
        assertThat(conversation.replies()).extracting(r -> r.id()).containsExactly("2", "4");
        assertThat(conversation.replies().getFirst().author().id()).isEqualTo("alice");
        assertThat(conversation.replies().getFirst().author().username()).isEqualTo("Alice");
        assertThat(conversation.replies().getFirst().author().hasMetadata()).isFalse();
    }

    @Test
    void repeatRepliersBecomeOneAccount() {
        var thread = new CapturedThread(400);
        thread.add(new ThreadScan("X", "1", POST, List.of(
                item("2", "Bot", "same", "2026-10-06T10:01:00.000Z"),
                item("3", "bot", "same again", "2026-10-06T10:05:00.000Z"))));
        var replies = thread.toConversation().replies();
        assertThat(replies.get(0).author()).isSameAs(replies.get(1).author());
    }

    @Test
    void repliesNeverPredateThePost() {
        var thread = new CapturedThread(400);
        thread.add(new ThreadScan("LinkedIn", "1", POST, List.of(item("2", "x", "early", "2026-10-06T09:00:00.000Z"))));
        assertThat(thread.toConversation().replies().getFirst().createdAt()).isEqualTo(java.time.Instant.parse("2026-10-06T10:00:00Z"));
    }

    @Test
    void stopsAtTheReplyCap() {
        var thread = new CapturedThread(2);
        thread.add(new ThreadScan("X", "1", POST, List.of(item("2", "a", "x", POST.createdAt()),
                item("3", "b", "y", POST.createdAt()), item("4", "c", "z", POST.createdAt()))));
        assertThat(thread.size()).isEqualTo(2);
        assertThat(thread.full()).isTrue();
    }

    @Test
    void needsThePost() {
        var thread = new CapturedThread(400);
        thread.add(new ThreadScan("X", "1", null, List.of(item("2", "a", "x", POST.createdAt()))));
        assertThat(thread.hasPost()).isFalse();
        assertThatThrownBy(thread::toConversation).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recognizesLoginWalls() {
        assertThat(CaptureService.loginRequired("https://x.com/i/flow/login?redirect_after_login=%2Fa%2Fstatus%2F1")).isTrue();
        assertThat(CaptureService.loginRequired("https://www.linkedin.com/authwall?trk=x")).isTrue();
        assertThat(CaptureService.loginRequired("https://x.com/a/status/1")).isFalse();
    }
}
