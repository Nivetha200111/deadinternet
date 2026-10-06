package dev.deadinternet.dto;

/** Live pipeline progress, polled by the UI while an analysis runs. */
public record AnalysisStatus(String id, String status, String stage, boolean demo, String provider, Progress progress,
                             String error) {

    public record Progress(int replies, int accounts, int comparisons, int similarityEdges, int coordinationEdges,
                           int classified, int clusters) {}
}
