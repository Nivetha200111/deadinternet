package dev.deadinternet.controller;

import dev.deadinternet.dto.AccountDetail;
import dev.deadinternet.dto.ClusterDetail;
import dev.deadinternet.dto.GraphPath;
import dev.deadinternet.graph.FalkorGateway;
import dev.deadinternet.graph.GraphQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Graph-native lookups, each answered by FalkorDB traversals within one analysis graph. */
@RestController
@RequestMapping("/api")
public class GraphController {

    private final GraphQueryService graph;

    public GraphController(GraphQueryService graph) {
        this.graph = graph;
    }

    @GetMapping("/accounts/{id}")
    public AccountDetail account(@PathVariable String id, @RequestParam String analysis) {
        return graph.account(requireAnalysis(analysis), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    @GetMapping("/clusters/{id}")
    public ClusterDetail cluster(@PathVariable String id, @RequestParam String analysis) {
        return graph.cluster(requireAnalysis(analysis), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cluster not found"));
    }

    @GetMapping("/path")
    public GraphPath path(@RequestParam String analysis, @RequestParam String from, @RequestParam String to) {
        if (from.equals(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose two different accounts");
        return graph.path(requireAnalysis(analysis), from, to);
    }

    private static String requireAnalysis(String analysis) {
        if (!FalkorGateway.isValidId(analysis)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found");
        }
        return analysis;
    }
}
