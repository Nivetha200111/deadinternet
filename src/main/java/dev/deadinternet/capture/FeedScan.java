package dev.deadinternet.capture;

import java.util.List;

/** The posts collector.js reads from a home feed in one pass. */
public record FeedScan(String site, List<ThreadScan.Item> items) {}
