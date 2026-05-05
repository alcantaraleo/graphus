package io.graphus.indexer;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphusVectorRuntimeTest {

    @Test
    void defaults_when_no_config(@TempDir Path tempDir) throws Exception {
        Path state = tempDir.resolve("fresh");

        GraphusVectorRuntime.Resolved resolved =
                GraphusVectorRuntime.merge(state.toAbsolutePath().normalize(), "col", null, null, null, null, null);

        assertEquals(VectorBackend.CHROMA, resolved.backend());
        assertEquals("local", resolved.embedding());
        assertEquals("http://localhost:8000", resolved.storeConfig().chromaBaseUrl());
        assertEquals(300, resolved.storeConfig().chromaTimeout().getSeconds());
    }

    @Test
    void persisted_config_applies_when_cli_omitted(@TempDir Path tempDir) throws Exception {
        Path state = tempDir.resolve(".graphus-state");
        GraphusConfigRegistry.save(
                state,
                new GraphusConfig(
                        "chroma",
                        "http://saved:7654",
                        88,
                        null,
                        "openai",
                        "saved-coll"));

        GraphusVectorRuntime.Resolved resolved =
                GraphusVectorRuntime.merge(state.toAbsolutePath().normalize(), "runtime-coll", null, null, null, null, null);

        assertEquals("http://saved:7654", resolved.storeConfig().chromaBaseUrl());
        assertEquals(88, resolved.storeConfig().chromaTimeout().getSeconds());
        assertEquals("openai", resolved.embedding());
        assertEquals("runtime-coll", resolved.storeConfig().collectionName());
    }

    @Test
    void sqlite_uses_explicit_file_or_defaults_under_state(@TempDir Path tempDir) throws Exception {
        Path state = tempDir.resolve("sqlite-state");

        GraphusConfigRegistry.save(
                state,
                new GraphusConfig("chroma", "http://x:8000", 10, null, "local", "x"));

        Path explicit = Files.createTempFile(tempDir, "vec", ".db").toAbsolutePath().normalize();

        GraphusVectorRuntime.Resolved sqliteExplicit =
                GraphusVectorRuntime.merge(
                        state.toAbsolutePath().normalize(),
                        "c",
                        "sqlite",
                        null,
                        null,
                        explicit.toString(),
                        null);

        assertEquals(VectorBackend.SQLITE, sqliteExplicit.backend());
        assertEquals(explicit.normalize(), sqliteExplicit.storeConfig().sqliteDatabasePath());

        GraphusVectorRuntime.Resolved sqliteDefault =
                GraphusVectorRuntime.merge(
                        state.toAbsolutePath().normalize(), "c", "sqlite", null, null, null, null);

        assertEquals(
                state.resolve("graphus.db").toAbsolutePath().normalize(),
                sqliteDefault.storeConfig().sqliteDatabasePath().toAbsolutePath().normalize());

        GraphusConfig copied = GraphusVectorRuntime.persistableCopy("persisted-coll", sqliteDefault);
        assertEquals("sqlite", copied.db());
        assertEquals(state.resolve("graphus.db").toAbsolutePath().normalize().toString(), copied.dbFile());
    }

    @Test
    void chroma_endpoint_overrides_even_when_prior_state_exists(@TempDir Path tempDir) throws Exception {
        Path state = tempDir.resolve("state");

        GraphusConfigRegistry.save(
                state, new GraphusConfig("chroma", "http://old:9999", 9, null, "openai", "k"));

        GraphusVectorRuntime.Resolved overridden =
                GraphusVectorRuntime.merge(
                        state.toAbsolutePath().normalize(),
                        "c",
                        null,
                        "http://fresh:6543",
                        33,
                        null,
                        null);

        assertEquals("http://fresh:6543", overridden.storeConfig().chromaBaseUrl());
        assertEquals(33, overridden.storeConfig().chromaTimeout().getSeconds());
    }

    @Test
    void unsupported_db_throws_illegal_argument(@TempDir Path tempDir) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                GraphusVectorRuntime.merge(
                                        tempDir.resolve("s").toAbsolutePath().normalize(),
                                        "coll",
                                        "mongodb",
                                        null,
                                        null,
                                        null,
                                        null));

        assertTrue(exception.getMessage() != null && exception.getMessage().contains("Unsupported"));
    }
}

