package dev.deadinternet.analysis;

import java.util.List;

/**
 * Spam markers measured in an account's text: the lexical, link and contact patterns that phishing replies, airdrop
 * scams, engagement bait and affiliate spam share. They describe the content, not who wrote it.
 *
 * @param emojiDensity      emojis per word
 * @param urgencyTerms      pressure phrases such as "hurry" or "only 12 minutes left"
 * @param shortenedLinks    link-shortener domains, which hide where a link goes
 * @param messagingContacts off-platform contacts (Telegram, WhatsApp, "send a DM") typical of fake support replies
 * @param cryptoTerms       wallet, airdrop, giveaway and ticker language
 * @param uppercaseRatio    share of letters in upper case
 * @param threadHook        thread-bait markers: 🧵, 👇, "1/10", "so you don't have to", "unpopular opinion"
 * @param parentOverlap     word overlap between a reply and the post it answers (0-1); null for feed posts
 * @param moneyClaims       profit or recovery claims: "made $12,400 in 9 days", "helped me recover"
 * @param jobLures          work-from-home riches, "#Interested", "comment your email", "no experience needed"
 * @param adultLures        "check my bio", "lonely tonight", 🔞
 * @param followFarming     "follow for follow", "#F4F", "gain 1000 followers"
 * @param genericPraise     the whole text is interchangeable praise ("Great insights! Thanks for sharing")
 * @param milestones        congratulations, certifications, new jobs and work anniversaries
 * @param promotions        sales calls to action: "book a call", "link in comments", "50% off", "enroll now"
 * @param aiStyle           phrasing typical of AI-written posts: "delve", "game-changer", "in today's fast-paced"
 * @param hashtags          number of hashtags; promotional posts stack them
 * @param substantive       long enough and specific enough (numbers, steps, how-to) to teach a reader something
 */
public record TextSignals(int emojis, double emojiDensity, List<String> urgencyTerms, int links,
                          List<String> shortenedLinks, List<String> messagingContacts, List<String> cryptoTerms,
                          int mentions, double uppercaseRatio, boolean threadHook, Double parentOverlap,
                          List<String> moneyClaims, List<String> jobLures, List<String> adultLures,
                          List<String> followFarming, boolean genericPraise,
                          List<String> milestones, List<String> promotions, List<String> aiStyle,
                          int hashtags, boolean substantive) {

    public static final TextSignals NONE = new TextSignals(0, 0, List.of(), 0, List.of(), List.of(), List.of(), 0, 0,
            false, null, List.of(), List.of(), List.of(), List.of(), false,
            List.of(), List.of(), List.of(), 0, false);
}
