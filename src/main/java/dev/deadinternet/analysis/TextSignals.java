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
 */
public record TextSignals(int emojis, double emojiDensity, List<String> urgencyTerms, int links,
                          List<String> shortenedLinks, List<String> messagingContacts, List<String> cryptoTerms,
                          int mentions, double uppercaseRatio, boolean threadHook, Double parentOverlap,
                          List<String> moneyClaims, List<String> jobLures, List<String> adultLures,
                          List<String> followFarming, boolean genericPraise) {

    public static final TextSignals NONE = new TextSignals(0, 0, List.of(), 0, List.of(), List.of(), List.of(), 0, 0,
            false, null, List.of(), List.of(), List.of(), List.of(), false);
}
