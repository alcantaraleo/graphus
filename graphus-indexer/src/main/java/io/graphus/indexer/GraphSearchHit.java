package io.graphus.indexer;

import java.util.Map;

public record GraphSearchHit(double score, String text, Map<String, Object> metadata) {
}
