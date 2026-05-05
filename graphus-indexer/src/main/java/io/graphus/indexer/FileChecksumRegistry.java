package io.graphus.indexer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class FileChecksumRegistry {

    private static final String CHECKSUMS_FILE = "checksums.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> checksums;

    private FileChecksumRegistry(Map<String, String> checksums) {
        this.checksums = new HashMap<>(checksums);
    }

    public static FileChecksumRegistry empty() {
        return new FileChecksumRegistry(Map.of());
    }

    public static boolean exists(Path stateDir) {
        return Files.exists(stateDir.resolve(CHECKSUMS_FILE));
    }

    public static FileChecksumRegistry load(Path stateDir) throws IOException {
        Path file = stateDir.resolve(CHECKSUMS_FILE);
        Map<String, String> data = MAPPER.readValue(file.toFile(), new TypeReference<>() {});
        return new FileChecksumRegistry(data);
    }

    public void save(Path stateDir) throws IOException {
        Files.createDirectories(stateDir);
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(stateDir.resolve(CHECKSUMS_FILE).toFile(), checksums);
    }

    /**
     * Recomputes SHA-256 checksums for all Java and Kotlin source files under the given roots and
     * replaces the internal state entirely. Use this after a full {@code index} run.
     */
    public void recomputeAll(Path repositoryRoot, List<Path> normalizedSourceRoots) throws IOException {
        checksums.clear();
        for (Path file : discoverSourceFiles(normalizedSourceRoots)) {
            String relativePath = relativize(repositoryRoot, file);
            checksums.put(relativePath, computeChecksum(file));
        }
    }

    /**
     * Computes current checksums, diffs them against the stored registry, updates the
     * internal state to reflect the new reality, and returns the set of changes.
     * After calling this, invoke {@link #save(Path)} to persist the updated registry.
     */
    public FileChangeSet diffAndUpdate(Path repositoryRoot, List<Path> normalizedSourceRoots) throws IOException {
        Map<String, String> current = new HashMap<>();
        for (Path file : discoverSourceFiles(normalizedSourceRoots)) {
            String relativePath = relativize(repositoryRoot, file);
            current.put(relativePath, computeChecksum(file));
        }

        Set<String> added = new HashSet<>();
        Set<String> modified = new HashSet<>();
        Set<String> deleted = new HashSet<>();

        for (Map.Entry<String, String> entry : current.entrySet()) {
            String path = entry.getKey();
            String checksum = entry.getValue();
            if (!checksums.containsKey(path)) {
                added.add(path);
            } else if (!checksum.equals(checksums.get(path))) {
                modified.add(path);
            }
        }

        for (String storedPath : checksums.keySet()) {
            if (!current.containsKey(storedPath)) {
                deleted.add(storedPath);
            }
        }

        checksums.putAll(current);
        for (String deletedPath : deleted) {
            checksums.remove(deletedPath);
        }

        return new FileChangeSet(added, modified, deleted);
    }

    public static String computeChecksum(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Walks {@code normalizedSourceRoots} and returns every regular {@code .java} or {@code .kt}
     * file. Both Java and Kotlin source roots are accepted in the same call so callers can pass
     * a single combined list (Java + Kotlin roots flattened from the workspace).
     */
    public static List<Path> discoverSourceFiles(List<Path> normalizedSourceRoots) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path sourceRoot : normalizedSourceRoots) {
            if (!Files.exists(sourceRoot) || !Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(sourceRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(FileChecksumRegistry::hasIndexedExtension)
                        .forEach(result::add);
            }
        }
        return result;
    }

    /** @deprecated Use {@link #discoverSourceFiles(List)} which also enumerates Kotlin sources. */
    @Deprecated
    public static List<Path> discoverJavaFiles(List<Path> normalizedSourceRoots) throws IOException {
        return discoverSourceFiles(normalizedSourceRoots);
    }

    private static boolean hasIndexedExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".kt");
    }

    private static String relativize(Path repositoryRoot, Path file) {
        return repositoryRoot.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize())
                .toString();
    }
}
