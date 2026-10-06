package dev.deadinternet.graph;

public class GraphUnavailableException extends RuntimeException {
    public GraphUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
