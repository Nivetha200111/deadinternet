package dev.deadinternet.controller;

import dev.deadinternet.dto.AnalysisStatus;
import dev.deadinternet.dto.ClusterView;
import dev.deadinternet.dto.GraphView;
import dev.deadinternet.model.Conversation;
import dev.deadinternet.service.ConversationAnalysisService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    private final ConversationAnalysisService analyses;

    public AnalysisController(ConversationAnalysisService analyses) {
        this.analyses = analyses;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnalysisStatus analyze(@Valid @RequestBody Conversation conversation) {
        return analyses.start(conversation, false);
    }

    @GetMapping("/{id}")
    public AnalysisStatus status(@PathVariable String id) {
        return analyses.status(id);
    }

    @GetMapping("/{id}/graph")
    public GraphView graph(@PathVariable String id) {
        return analyses.graph(id);
    }

    @GetMapping("/{id}/clusters")
    public List<ClusterView> clusters(@PathVariable String id) {
        return analyses.graph(id).clusters();
    }
}
