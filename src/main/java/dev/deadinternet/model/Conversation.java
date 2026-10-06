package dev.deadinternet.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What gets analyzed. A thread is a post and its replies. A feed is many accounts' posts gathered from a home feed;
 * they hang off a synthetic root "post" that stands for the feed itself.
 *
 * @param kind "thread" (the default when omitted) or "feed"
 */
public record Conversation(@NotNull @Valid Post post,
                           @NotEmpty @Size(max = 400) List<@Valid Reply> replies,
                           @Pattern(regexp = "thread|feed") String kind) {

    public static final String THREAD = "thread";
    public static final String FEED = "feed";

    public Conversation {
        if (kind == null) kind = THREAD;
    }

    public Conversation(Post post, List<Reply> replies) {
        this(post, replies, THREAD);
    }

    public boolean isFeed() {
        return FEED.equals(kind);
    }
}
