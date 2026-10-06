package dev.deadinternet.graph;

import dev.deadinternet.dto.AnalysisStatus;
import dev.deadinternet.service.ConversationAnalysisService;
import dev.deadinternet.service.DemoService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the bundled demo through the real pipeline against FalkorDB (docker compose up -d), exercising the WCC,
 * neighborhood and SPpaths queries. Skipped when FalkorDB is not reachable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "lens.demo.analyze-on-startup=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FalkorPipelineIntegrationTest {

    @Autowired ConversationAnalysisService analyses;
    @Autowired DemoService demo;
    @Autowired GraphQueryService graph;
    @Autowired FalkorGateway falkor;

    private String id;
    private Map<String, String> idsByUsername;

    @BeforeAll
    void analyzeDemo() throws InterruptedException {
        assumeTrue(falkor.ping(), "FalkorDB is not running; start it with docker compose up -d");
        AnalysisStatus status = analyses.start(demo.conversation(), false);
        id = status.id();
        for (int i = 0; i < 200 && "RUNNING".equals(status.status()); i++) {
            Thread.sleep(50);
            status = analyses.status(id);
        }
        assertThat(status.status()).isEqualTo("COMPLETE");
        idsByUsername = analyses.graph(id).accounts().stream()
                .collect(Collectors.toMap(a -> a.username(), a -> a.id(), (a, b) -> a));
    }

    @AfterAll
    void cleanUp() {
        if (id != null) analyses.delete(id);
    }

    @Test
    void graphViewIsReadBackFromFalkorDb() {
        var view = analyses.graph(id);
        assertThat(view.stats().replies()).isEqualTo(demo.conversation().replies().size());
        assertThat(view.accounts()).allSatisfy(a -> {
            assertThat(a.classification()).isNotNull();
            assertThat(a.classificationSource()).isEqualTo("LOCAL_HEURISTIC");
            assertThat(a.degree()).isGreaterThanOrEqualTo(a.replyCount());
        });
        assertThat(view.edges()).anyMatch(e -> e.type().equals("SIMILAR")).anyMatch(e -> e.type().equals("COORDINATED"));
        assertThat(view.score().score()).isBetween(0.0, 1.0);
        assertThat(view.thresholds().automation()).isEqualTo(0.65);
    }

    @Test
    void coordinatedGroupsBecomeClustersViaWcc() {
        var clusters = analyses.graph(id).clusters();
        assertThat(clusters).hasSizeGreaterThanOrEqualTo(3);
        var byMember = clusters.stream().flatMap(c -> c.accountIds().stream().map(a -> Map.entry(a, c)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        // Template bursts cluster together; the coordinating study group is its own human-like cluster.
        var burst = byMember.get(idsByUsername.get("future_stack_01"));
        assertThat(burst.accountIds()).contains(idsByUsername.get("future_stack_12"));
        assertThat(burst.averageAutomation()).isGreaterThanOrEqualTo(0.65);
        var study = byMember.get(idsByUsername.get("builders_club_01"));
        assertThat(study.averageAutomation()).isLessThan(0.4);
        assertThat(study.averageCoordination()).isGreaterThan(0.65);
        assertThat(byMember).doesNotContainKey(idsByUsername.get("maya_codes"));
    }

    @Test
    void accountNeighborhoodComesFromTraversals() {
        var detail = graph.account(id, idsByUsername.get("agent_daily_01")).orElseThrow();
        assertThat(detail.coordinatedWith()).isNotEmpty();
        assertThat(detail.reachableWithinTwoHops()).isGreaterThanOrEqualTo(detail.coordinatedWith().size());
        assertThat(detail.cluster()).isNotNull();
        assertThat(detail.account().replyCount()).isEqualTo(3);
    }

    @Test
    void clusterDetailReportsMembersAndDensity() {
        var cluster = analyses.graph(id).clusters().getFirst();
        var detail = graph.cluster(id, cluster.id()).orElseThrow();
        assertThat(detail.members()).hasSize(cluster.accountIds().size());
        assertThat(detail.density()).isBetween(0.0, 1.0);
        assertThat(detail.coordinationEdges()).isPositive();
    }

    @Test
    void pathFollowsBehavioralRelationshipsNotTheSharedPost() {
        var path = graph.path(id, idsByUsername.get("kim_bridges"), idsByUsername.get("nextwave_03"));
        assertThat(path.found()).isTrue();
        assertThat(path.nodes().getFirst().id()).isEqualTo(idsByUsername.get("kim_bridges"));
        assertThat(path.nodes().getLast().id()).isEqualTo(idsByUsername.get("nextwave_03"));
        assertThat(path.relationships()).extracting(r -> r.type()).doesNotContain("REPLIES_TO").contains("SIMILAR_TO");
        assertThat(path.nodes()).filteredOn(n -> n.type().equals("Reply")).allSatisfy(n -> assertThat(n.accountId()).isNotNull());

        var none = graph.path(id, idsByUsername.get("maya_codes"), idsByUsername.get("future_stack_01"));
        assertThat(none.found()).isFalse();
    }

    @Test
    void completedAnalysisStatusSurvivesWithoutTheInMemoryRegistry() {
        assertThat(analyses.status(id).status()).isEqualTo("COMPLETE");
    }
}
