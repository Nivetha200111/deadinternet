package dev.deadinternet.analysis;

import dev.deadinternet.config.LensProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns connected components of the coordination graph (computed by FalkorDB's algo.WCC) into clusters. Deliberately
 * simple and debuggable: a cluster is a component with at least {@code minClusterSize} accounts.
 */
@Service
public class ClusterDetectionService {

    private final int minClusterSize;

    @Autowired
    public ClusterDetectionService(LensProperties properties) {
        this(properties.coordination().minClusterSize());
    }

    public ClusterDetectionService(int minClusterSize) {
        this.minClusterSize = minClusterSize;
    }

    /**
     * @param componentByAccount account id → component id, in a stable account order
     * @return clusters ordered by size (largest first), ties broken by first member order
     */
    public List<DetectedCluster> detect(Map<String, Long> componentByAccount) {
        var members = new LinkedHashMap<Long, List<String>>();
        componentByAccount.forEach((account, component) ->
                members.computeIfAbsent(component, k -> new ArrayList<>()).add(account));
        var groups = members.values().stream().filter(g -> g.size() >= minClusterSize)
                .sorted(Comparator.comparingInt(List<String>::size).reversed()).toList();
        var clusters = new ArrayList<DetectedCluster>();
        for (var group : groups) {
            int index = clusters.size() + 1;
            clusters.add(new DetectedCluster("cluster_%02d".formatted(index), index, List.copyOf(group)));
        }
        return clusters;
    }
}
