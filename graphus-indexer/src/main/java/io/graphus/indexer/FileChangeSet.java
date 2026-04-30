package io.graphus.indexer;

import java.util.HashSet;
import java.util.Set;

public record FileChangeSet(Set<String> added, Set<String> modified, Set<String> deleted) {

    public boolean hasChanges() {
        return !added.isEmpty() || !modified.isEmpty() || !deleted.isEmpty();
    }

    public Set<String> toReindex() {
        Set<String> result = new HashSet<>(added);
        result.addAll(modified);
        return result;
    }

    public int totalChanges() {
        return added.size() + modified.size() + deleted.size();
    }
}
