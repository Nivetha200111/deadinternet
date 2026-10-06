package dev.deadinternet.classification;

public class JevException extends RuntimeException {
    public JevException(String message) {
        super(message);
    }

    public JevException(String message, Throwable cause) {
        super(message, cause);
    }
}
