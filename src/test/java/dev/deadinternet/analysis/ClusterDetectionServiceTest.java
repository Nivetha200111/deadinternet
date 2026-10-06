package dev.deadinternet.analysis;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterDetectionServiceTest {

    private final ClusterDetectionService service = new ClusterDetectionService(3);

    @Test
    void componentsBelowMinimumSizeAreNotClustersAndLargestComesFirst() {
        var components = new LinkedHashMap<String, Long>();
        components.put("solo", 1L);
        components.put("p1", 2L);
        components.put("p2", 2L);
        components.put("s1", 3L);
        components.put("s2", 3L);
        components.put("s3", 3L);
        components.put("b1", 4L);
        components.put("b2", 4L);
        components.put("b3", 4L);
        components.put("b4", 4L);

        var clusters = service.detect(components);

        assertThat(clusters).hasSize(2);
        assertThat(clusters.get(0).id()).isEqualTo("cluster_01");
        assertThat(clusters.get(0).index()).isEqualTo(1);
        assertThat(clusters.get(0).accountIds()).containsExactly("b1", "b2", "b3", "b4");
        assertThat(clusters.get(1).accountIds()).containsExactly("s1", "s2", "s3");
    }

    @Test
    void noComponentsMeansNoClusters() {
        assertThat(service.detect(new LinkedHashMap<>())).isEmpty();
    }
}
