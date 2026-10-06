package dev.deadinternet.dto;

import java.util.List;

/** A shortest behavioral path between two accounts, found by FalkorDB. Shared replies to the post do not count. */
public record GraphPath(boolean found, List<Step> nodes, List<Link> relationships) {

    /** @param type Account or Reply; {@code accountId} is the owning account for replies */
    public record Step(String type, String id, String label, String accountId) {}

    public record Link(String type, String from, String to, Double similarity, Double score,
                       Long timeDifferenceSeconds) {}
}
