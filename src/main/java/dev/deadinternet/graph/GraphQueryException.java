package dev.deadinternet.graph;

/** FalkorDB rejected a query. The message is logged, never sent to clients. */
public class GraphQueryException extends RuntimeException {
    public GraphQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
