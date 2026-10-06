package dev.deadinternet.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An account as seen in the conversation. Metadata is optional: a thread scraped from a page often carries only the
 * handle, and the analysis then relies on text, timing and graph signals.
 */
public record Account(@NotBlank @Size(max = 100) String id,
                      @NotBlank @Size(max = 100) String username,
                      @Min(0) Integer accountAgeDays,
                      @Min(0) Integer followers,
                      @Min(0) Integer following,
                      @Min(0) Integer totalPosts) {

    public static Account handleOnly(String id, String username) {
        return new Account(id, username, null, null, null, null);
    }

    public boolean hasMetadata() {
        return accountAgeDays != null || followers != null || following != null || totalPosts != null;
    }

    /** null when either count is unknown. */
    public Double followerFollowingRatio() {
        return followers == null || following == null ? null : (double) followers / Math.max(1, following);
    }

    /** null when post count or age is unknown. */
    public Double postsPerDay() {
        return totalPosts == null || accountAgeDays == null ? null : (double) totalPosts / Math.max(1, accountAgeDays);
    }
}
