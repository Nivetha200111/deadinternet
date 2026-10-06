package dev.deadinternet.capture;

/**
 * Progress of a live capture, polled by the UI.
 *
 * @param frame increases whenever a new screenshot of the page is available at /api/captures/{id}/frame
 */
public record CaptureStatus(String id, String url, String site, String stage, int collected, int skipped,
                            String message, String analysisId, long frame) {}
