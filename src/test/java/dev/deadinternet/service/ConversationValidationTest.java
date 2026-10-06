package dev.deadinternet.service;

import dev.deadinternet.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static dev.deadinternet.Fixtures.account;
import static dev.deadinternet.Fixtures.established;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationValidationTest {

    @Test
    void acceptsMultipleRepliesFromOneAccount() {
        var a = established("a");
        assertThatCode(() -> ConversationAnalysisService.validate(Fixtures.conversation(a, "one", 1, a, "two", 5)))
                .doesNotThrowAnyException();
    }

    @Test
    void handleOnlyConversationJsonDeserializes() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var conversation = mapper.readValue("""
                {"post": {"id": "1", "author": "root", "text": "hello", "createdAt": "2026-10-06T10:00:00Z"},
                 "replies": [{"id": "2", "author": {"id": "someone", "username": "someone"},
                              "text": "hi", "createdAt": "2026-10-06T10:00:05Z"}]}
                """, dev.deadinternet.model.Conversation.class);
        assertThat(conversation.replies().getFirst().author().hasMetadata()).isFalse();
        assertThat(conversation.replies().getFirst().author().postsPerDay()).isNull();
        assertThatCode(() -> ConversationAnalysisService.validate(conversation)).doesNotThrowAnyException();
    }

    @Test
    void rejectsRepliesBeforeThePost() {
        assertThatThrownBy(() -> ConversationAnalysisService.validate(Fixtures.conversation(established("a"), "early", -10)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("predates");
    }

    @Test
    void rejectsOneAccountIdWithTwoUsernames() {
        var first = account("a", 100, 1, 1, 1);
        var renamed = new dev.deadinternet.model.Account("a", "someone_else", 100, 1, 1, 1);
        assertThatThrownBy(() -> ConversationAnalysisService.validate(Fixtures.conversation(first, "x", 1, renamed, "y", 2)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("two usernames");
    }
}
