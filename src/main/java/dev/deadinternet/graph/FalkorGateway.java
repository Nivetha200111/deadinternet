package dev.deadinternet.graph;

import com.falkordb.Driver;
import com.falkordb.Record;
import org.springframework.stereotype.Component;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Thin access layer over JFalkorDB: one FalkorDB graph per analysis, named {@code lens_<analysisId>}. */
@Component
public class FalkorGateway {

    static final String PREFIX = "lens_";
    private static final Pattern SAFE_ID = Pattern.compile("[a-f0-9]{32}");

    private final Driver driver;

    public FalkorGateway(Driver driver) {
        this.driver = driver;
    }

    public static boolean isValidId(String analysisId) {
        return analysisId != null && SAFE_ID.matcher(analysisId).matches();
    }

    public List<Record> write(String analysisId, String cypher, Map<String, Object> params) {
        return run(analysisId, cypher, params, false);
    }

    public List<Record> read(String analysisId, String cypher, Map<String, Object> params) {
        return run(analysisId, cypher, params, true);
    }

    public List<Record> read(String analysisId, String cypher) {
        return read(analysisId, cypher, Map.of());
    }

    private List<Record> run(String analysisId, String cypher, Map<String, Object> params, boolean readOnly) {
        var graph = driver.graph(graphName(analysisId));
        try {
            var result = readOnly ? graph.readOnlyQuery(cypher, params) : graph.query(cypher, params);
            var records = new ArrayList<Record>(result.size());
            result.forEach(records::add);
            return records;
        } catch (JedisConnectionException e) {
            throw new GraphUnavailableException("FalkorDB is not reachable. Start it with: docker compose up -d", e);
        } catch (JedisDataException e) {
            throw new GraphQueryException(e.getMessage(), e);
        }
    }

    public boolean exists(String analysisId) {
        return listAnalysisIds().contains(analysisId);
    }

    public List<String> listAnalysisIds() {
        try {
            return driver.listGraphs().stream().filter(g -> g.startsWith(PREFIX))
                    .map(g -> g.substring(PREFIX.length())).filter(FalkorGateway::isValidId).toList();
        } catch (JedisConnectionException e) {
            throw new GraphUnavailableException("FalkorDB is not reachable. Start it with: docker compose up -d", e);
        }
    }

    public void delete(String analysisId) {
        if (!exists(analysisId)) return;
        driver.graph(graphName(analysisId)).deleteGraph();
    }

    public boolean ping() {
        try (var connection = driver.getConnection()) {
            return "PONG".equals(connection.ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String graphName(String analysisId) {
        if (!isValidId(analysisId)) throw new IllegalArgumentException("Invalid analysis id");
        return PREFIX + analysisId;
    }
}
