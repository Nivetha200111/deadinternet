package dev.deadinternet.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptureTargetTest {

    @Test
    void normalizesXAndTwitterPostUrls() {
        assertThat(CaptureTarget.parse("https://twitter.com/Some_User/status/1234567890?s=20"))
                .isEqualTo(new CaptureTarget("X", "https://x.com/Some_User/status/1234567890"));
        assertThat(CaptureTarget.parse(" https://mobile.x.com/a/status/42/photo/1 ").url())
                .isEqualTo("https://x.com/a/status/42");
    }

    @Test
    void acceptsLinkedInPostUrls() {
        assertThat(CaptureTarget.parse("https://www.linkedin.com/feed/update/urn:li:activity:7123456789012345678/?utm_source=share"))
                .isEqualTo(new CaptureTarget("LinkedIn", "https://www.linkedin.com/feed/update/urn:li:activity:7123456789012345678/"));
        assertThat(CaptureTarget.parse("https://linkedin.com/posts/jane-doe_ai-agents-activity-7123456789012345678-AbCd").site())
                .isEqualTo("LinkedIn");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://x.com/home", "https://x.com", "https://twitter.com/", "https://x.com/home?ref=1"})
    void xHomeMeansTheSignedInFeed(String url) {
        assertThat(CaptureTarget.parse(url)).isEqualTo(CaptureTarget.X_FEED);
        assertThat(CaptureTarget.X_FEED.feed()).isTrue();
    }

    @Test
    void linkedInFeed() {
        assertThat(CaptureTarget.parse("https://www.linkedin.com/feed/")).isEqualTo(CaptureTarget.LINKEDIN_FEED);
        assertThat(CaptureTarget.parse("https://linkedin.com/feed")).isEqualTo(CaptureTarget.LINKEDIN_FEED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://x.com/someone", "https://evil.example/x.com/a/status/1",
            "https://www.linkedin.com/in/jane-doe/", "javascript:alert(1)", "https://x.com.evil.example/a/status/1"})
    void rejectsAnythingThatIsNotASinglePost(String url) {
        assertThatThrownBy(() -> CaptureTarget.parse(url)).isInstanceOf(IllegalArgumentException.class);
    }
}
