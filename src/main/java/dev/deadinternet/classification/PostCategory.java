package dev.deadinternet.classification;

/**
 * What kind of posting an account does, for grouping a feed where accounts are otherwise unrelated. Categories
 * describe the content, never who is behind the account.
 */
public final class PostCategory {
    public static final String SCAM = "scam";
    public static final String ENGAGEMENT_BAIT = "engagement_bait";
    public static final String FOLLOW_FARMING = "follow_farming";
    public static final String GENERIC_COMMENT = "generic_comment";
    /** Congratulations, certifications, new jobs and work anniversaries. */
    public static final String MILESTONE = "milestone";
    /** AI-written and substantive: explains, teaches or informs. */
    public static final String AI_USEFUL = "ai_useful";
    /** Advertising: sells a product, service, course or the author's own offer, AI-written or not. */
    public static final String AI_AD = "ai_ad";
    /** AI-written filler: platitudes, recycled advice, nothing a reader takes away. */
    public static final String AI_LOW_VALUE = "ai_low_value";
    public static final String CONTENT_FARM = "content_farm";
    public static final String TOO_LITTLE_TEXT = "too_little_text";
    public static final String PERSONAL = "personal";
    public static final String MIXED = "mixed";
    /** Automation-like for behavioral reasons (repetition, bursts) rather than a recognizable content pattern. */
    public static final String AUTOMATED = "automated";

    private PostCategory() {}

    /** The category when no content pattern stands out: it follows the label. */
    public static String fromLabel(Classification classification) {
        return switch (classification) {
            case HUMAN_LIKE -> PERSONAL;
            case UNCERTAIN -> MIXED;
            case AUTOMATION_LIKE -> AUTOMATED;
        };
    }
}
