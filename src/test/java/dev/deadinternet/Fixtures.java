package dev.deadinternet;

import dev.deadinternet.config.LensProperties;
import dev.deadinternet.model.Account;
import dev.deadinternet.model.Conversation;
import dev.deadinternet.model.Post;
import dev.deadinternet.model.Reply;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Small builders for analysis tests. */
public final class Fixtures {

    public static final Instant POSTED_AT = Instant.parse("2026-10-06T10:00:00Z");

    private Fixtures() {}

    public static LensProperties properties() {
        return new LensProperties(
                new LensProperties.Similarity(0.72, 6),
                new LensProperties.Coordination(0.75, 0.55, 0.30, 0.15, 120, 3),
                new LensProperties.Thresholds(0.40, 0.65),
                new LensProperties.Jev("heuristic", "", "", 2, 4),
                new LensProperties.Falkor("localhost", 6380, "", ""),
                new LensProperties.Capture(true, "target/test-browser-profile", "chrome", true, 400, 6, false, "chrome"),
                30);
    }

    public static Account account(String id, int ageDays, int followers, int following, int posts) {
        return new Account(id, "user_" + id, ageDays, followers, following, posts);
    }

    public static Account established(String id) {
        return account(id, 2400, 900, 400, 3000);
    }

    public static Account young(String id) {
        return account(id, 30, 10, 1800, 6000);
    }

    /** Builds a conversation; each entry is (account, text, secondsAfterPost). */
    public static Conversation conversation(Object... triples) {
        var replies = new ArrayList<Reply>();
        for (int i = 0; i < triples.length; i += 3) {
            replies.add(new Reply("reply_" + (replies.size() + 1), (Account) triples[i], (String) triples[i + 1],
                    POSTED_AT.plusSeconds(((Number) triples[i + 2]).longValue())));
        }
        return new Conversation(new Post("post_1", "root", "AI agents are going to change software.", POSTED_AT),
                List.copyOf(replies));
    }
}
