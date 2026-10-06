package dev.deadinternet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.deadinternet.dto.AnalysisStatus;
import dev.deadinternet.graph.FalkorGateway;
import dev.deadinternet.graph.GraphUnavailableException;
import dev.deadinternet.model.Conversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** The bundled, entirely fictional demo conversation, analyzed at startup so the page opens on a finished graph. */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    private final ConversationAnalysisService analyses;
    private final FalkorGateway falkor;
    private final Conversation conversation;
    private final boolean analyzeOnStartup;
    private volatile String currentId;

    public DemoService(ConversationAnalysisService analyses, FalkorGateway falkor, ObjectMapper mapper,
                       @Value("${lens.demo.analyze-on-startup:true}") boolean analyzeOnStartup) {
        this.analyses = analyses;
        this.falkor = falkor;
        this.analyzeOnStartup = analyzeOnStartup;
        try (var in = new ClassPathResource("demo-conversation.json").getInputStream()) {
            this.conversation = mapper.readValue(in, Conversation.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Bundled demo-conversation.json is missing or invalid", e);
        }
    }

    public Conversation conversation() {
        return conversation;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void analyzeOnStartup() {
        if (!analyzeOnStartup) return;
        try {
            reset();
        } catch (RuntimeException e) {
            log.warn("Demo analysis not started: {}", e.getMessage());
        }
    }

    /** The current demo analysis, starting one if none exists yet. */
    public synchronized AnalysisStatus current() {
        if (currentId == null) return reset();
        return analyses.status(currentId);
    }

    /** Re-runs the full demo pipeline and removes earlier demo graphs from FalkorDB. */
    public synchronized AnalysisStatus reset() {
        try {
            for (String id : falkor.listAnalysisIds()) {
                var rows = falkor.read(id, "MATCH (n:Analysis) RETURN n.demo AS demo", Map.of());
                if (rows.isEmpty() || Boolean.TRUE.equals(rows.getFirst().getValue("demo"))) analyses.delete(id);
            }
        } catch (GraphUnavailableException e) {
            log.debug("Skipping demo cleanup: {}", e.getMessage());
        }
        var status = analyses.start(conversation, true);
        currentId = status.id();
        return status;
    }
}
