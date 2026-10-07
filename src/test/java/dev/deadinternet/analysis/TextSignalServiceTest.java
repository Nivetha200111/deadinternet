package dev.deadinternet.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextSignalServiceTest {

    private final TextSignalService service = new TextSignalService();

    @Test
    void fakeSupportReplyShowsContactAndPressure() {
        var t = service.analyze(List.of("Hey! Kindly send a direct message to @CryptoFixer_Hub or contact support via "
                + "telegram at t.me/wallet_helper_01 to fix your locked account immediately. They resolved mine within "
                + "minutes! 🙏✨"), "My wallet app says my account is suspended, anyone know why?");
        assertThat(t.messagingContacts()).contains("t.me/wallet_helper_01", "send a direct message");
        assertThat(t.urgencyTerms()).contains("kindly", "locked", "immediately");
        assertThat(t.mentions()).isEqualTo(1);
        assertThat(t.emojis()).isEqualTo(2);
        assertThat(t.parentOverlap()).isNotNull();
    }

    @Test
    void airdropScamShowsCryptoTermsAndALink() {
        var t = service.analyze(List.of("To celebrate our new partnership, we are giving away $5,000,000 in $ETH to our "
                + "community! 🎁 First 1000 users to connect their wallet at malicious-phishing-link.net get double "
                + "rewards. HURRY, only 12 minutes left! ⏳🚨"), null);
        assertThat(t.cryptoTerms()).contains("giving away", "$eth", "wallet", "double rewards");
        assertThat(t.links()).isEqualTo(1);
        assertThat(t.urgencyTerms()).contains("hurry", "only 12 minutes left");
        assertThat(t.emojis()).isEqualTo(3);
        assertThat(t.parentOverlap()).isNull();
    }

    @Test
    void threadBaitAndShortenersAreRecognized() {
        var t = service.analyze(List.of("Unpopular opinion: 99% of people use ChatGPT wrong. 🧵 [1/10]",
                "Full guide here: bit.ly/3xYz"), null);
        assertThat(t.threadHook()).isTrue();
        assertThat(t.shortenedLinks()).containsExactly("bit.ly");
    }

    @Test
    void ordinaryPostsCarryNoSpamMarkers() {
        var t = service.analyze(List.of("Telegram announced new group features today, and the rollout starts on 10/7.",
                "ordered rice bowl from california burrito"), null);
        assertThat(t.messagingContacts()).isEmpty();
        assertThat(t.cryptoTerms()).isEmpty();
        assertThat(t.urgencyTerms()).isEmpty();
        assertThat(t.threadHook()).isFalse();
        assertThat(t.shortenedLinks()).isEmpty();
    }

    @Test
    void offTopicReplyHasLowParentOverlap() {
        var onTopic = service.analyze(List.of("That desk setup lighting is great, which monitor arm is that?"),
                "My new desk setup with better lighting and a monitor arm");
        var offTopic = service.analyze(List.of("Wow this looks amazing! Found the exact same wireless charger 70% off here"),
                "My new desk setup with better lighting and a monitor arm");
        assertThat(onTopic.parentOverlap()).isGreaterThan(offTopic.parentOverlap());
        assertThat(offTopic.parentOverlap()).isLessThan(0.05);
    }

    @Test
    void scamShapesAreRecognizedAndTheirLookAlikesAreNot() {
        var invest = service.analyze(List.of("I was skeptical but thanks to @CryptoMentorJane I made $12,400 in just 9 days "
                + "with her trading signals."), null);
        assertThat(invest.moneyClaims()).isNotEmpty();
        assertThat(invest.mentions()).isEqualTo(1);
        var recovery = service.analyze(List.of("@SwiftRecoveryHub helped me recover my stolen crypto in 48 hours"), null);
        assertThat(recovery.moneyClaims()).isNotEmpty();
        var job = service.analyze(List.of("URGENT HIRING 🚨 Work from home, earn $500/day, no experience needed! Like and "
                + "comment #Interested and DM me on WhatsApp +1 555 0100"), null);
        assertThat(job.jobLures()).contains("#interested", "work from home", "no experience needed");
        assertThat(job.messagingContacts()).isNotEmpty();
        var adult = service.analyze(List.of("lonely tonight 😘 my private pics are in my bio 🔞"), null);
        assertThat(adult.adultLures()).contains("lonely tonight", "private pics", "🔞");
        var follow = service.analyze(List.of("Follow for follow! I follow back 100% #F4F #followback"), null);
        assertThat(follow.followFarming()).contains("follow for follow", "#f4f", "#followback");

        var bakeSale = service.analyze(List.of("Sold 200 cookies at the school fair and made $340, the kids were thrilled."), null);
        assertThat(bakeSale.mentions()).isZero();
        assertThat(bakeSale.messagingContacts()).isEmpty();
        var hiring = service.analyze(List.of("We're hiring a backend engineer in Berlin (Go, Postgres). Apply through our "
                + "careers page."), null);
        assertThat(hiring.jobLures()).isEmpty();
        var portfolio = service.analyze(List.of("My bio has the link to my illustration portfolio."), null);
        assertThat(portfolio.adultLures()).isEmpty();
    }

    @Test
    void genericPraiseNeedsToSayNothingSpecific() {
        assertThat(TextSignalService.isGenericPraise("Great insights! Thanks for sharing such valuable content. This is so "
                + "important for everyone to understand. 🙌")).isTrue();
        assertThat(TextSignalService.isGenericPraise("Well said @jane, so true!")).isTrue();
        assertThat(TextSignalService.isGenericPraise("Thanks for sharing. The part about partial indexes saved us hours on "
                + "our Postgres migration last week.")).isFalse();
        assertThat(TextSignalService.isGenericPraise("ordered rice bowl from california burrito")).isFalse();
    }
}
