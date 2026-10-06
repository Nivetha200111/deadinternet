package dev.deadinternet.dto;

import java.util.List;

public record ClusterDetail(ClusterView cluster, List<Member> members, int coordinationEdges, double density) {

    public record Member(String accountId, String username, String classification, double automationLikelihood,
                         double coordinationLikelihood) {}
}
