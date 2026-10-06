package dev.deadinternet.classification;

public enum ClassificationSource {
    /** Returned by the JEV classifier. */
    JEV,
    /** Local deterministic heuristic, used deliberately because no JEV endpoint is configured. */
    LOCAL_HEURISTIC,
    /** JEV was configured but failed for this account; the result is forced to uncertain. */
    HEURISTIC_FALLBACK
}
