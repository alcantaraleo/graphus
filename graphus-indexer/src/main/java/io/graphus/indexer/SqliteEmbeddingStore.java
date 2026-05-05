package io.graphus.indexer;

import static dev.langchain4j.internal.Utils.randomUUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.store.embedding.CosineSimilarity;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * SQLite-backed {@link EmbeddingStore} with brute-force cosine similarity search executed in-process.
 *
 * <p>Each logical collection maps to a stable physical table name prefixed with {@value #TABLE_PREFIX}.</p>
 */
public final class SqliteEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final String TABLE_PREFIX = "graphus_embeddings_";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Connection connection;
    private final String quotedPhysicalTable;

    public SqliteEmbeddingStore(Path sqliteDatabaseFile, String logicalCollectionName) {
        Objects.requireNonNull(sqliteDatabaseFile, "sqliteDatabaseFile");
        Objects.requireNonNull(logicalCollectionName, "logicalCollectionName");

        Path databaseParent = sqliteDatabaseFile.toAbsolutePath().normalize().getParent();
        Connection opened;
        try {
            if (databaseParent != null) {
                Files.createDirectories(databaseParent);
            }
            opened = DriverManager.getConnection("jdbc:sqlite:" + sqliteDatabaseFile.toAbsolutePath());
            applyPragmas(opened);
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("Failed opening SQLite database: " + sqliteDatabaseFile, failure);
        }

        this.connection = opened;
        this.quotedPhysicalTable = quoteIdentifier(buildPhysicalTableName(logicalCollectionName));

        try {
            createTableIfMissing();
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Failed initializing embedding table in SQLite", sqlException);
        }
    }

    /**
     * Computes the physical SQLite table name derived from a logical collection name.
     *
     * <p>This is {@value #TABLE_PREFIX} + a normalized suffix.</p>
     */
    static String buildPhysicalTableName(String logicalCollectionName) {
        return TABLE_PREFIX + normalizeCollectionSuffix(logicalCollectionName);
    }

    /**
     * Visible for unit tests: normalization rules for the suffix after {@value #TABLE_PREFIX}.
     */
    static String normalizeCollectionSuffix(String logicalCollectionName) {
        String normalized =
                Objects.requireNonNullElse(logicalCollectionName, "").toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) {
            return "collection";
        }

        String sanitized = normalized.replaceAll("[^a-z0-9]", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^_+", "");
        sanitized = sanitized.replaceAll("_+$", "");
        if (sanitized.isEmpty()) {
            sanitized = "collection";
        }

        if (!Character.isLetter(sanitized.charAt(0))) {
            sanitized = "c_" + sanitized;
        }

        if (sanitized.length() > 120) {
            sanitized = sanitized.substring(0, 120);
        }
        return sanitized;
    }

    private static String quoteIdentifier(String identifier) {
        String escaped = identifier.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private static void applyPragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA journal_mode = WAL");
            statement.executeUpdate("PRAGMA synchronous = NORMAL");
            statement.executeUpdate("PRAGMA foreign_keys = ON");
            statement.executeUpdate("PRAGMA busy_timeout = 5000");
        }
    }

    private void createTableIfMissing() throws SQLException {
        String ddl =
                "CREATE TABLE IF NOT EXISTS "
                        + quotedPhysicalTable
                        + """
                         (
                             id TEXT PRIMARY KEY NOT NULL,
                             vector BLOB NOT NULL,
                             content TEXT NOT NULL,
                             metadata TEXT NOT NULL
                         )""";
        try (Statement ddlStatement = connection.createStatement()) {
            ddlStatement.executeUpdate(ddl);
        }
    }

    /**
     * Decodes embeddings stored via {@link #vectorBlob(float[])}.
     */
    static float[] floatsFromLittleEndian(byte[] blob) {
        Objects.requireNonNull(blob, "blob");
        if (blob.length == 0 || blob.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("Vector blob must be divisible by Float.BYTES.");
        }

        ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[blob.length / Float.BYTES];
        buffer.asFloatBuffer().get(values);
        return values;
    }

    private static byte[] vectorBlob(float[] vector) {
        ByteBuffer byteBuffer =
                ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.asFloatBuffer().put(vector);
        return byteBuffer.array();
    }

    @Override
    public synchronized String add(Embedding embedding) {
        return add(embedding, TextSegment.from(""));
    }

    @Override
    public synchronized void add(String id, Embedding embedding) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(embedding, "embedding");
        upsertEmbeddingRow(id, embedding, TextSegment.from(""));
    }

    @Override
    public synchronized String add(Embedding embedding, TextSegment embedded) {
        Objects.requireNonNull(embedding, "embedding");
        Objects.requireNonNull(embedded, "embedded");
        String id = randomUUID();
        upsertEmbeddingRow(id, embedding, embedded);
        return id;
    }

    @Override
    public synchronized List<String> addAll(List<Embedding> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(embeddings.size());
        for (Embedding embedding : embeddings) {
            ids.add(add(embedding, TextSegment.from("")));
        }
        return ids;
    }

    @Override
    public synchronized void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(embeddings, "embeddings");
        Objects.requireNonNull(embedded, "embedded");

        if (ids.isEmpty()) {
            return;
        }
        if (ids.size() != embeddings.size() || embeddings.size() != embedded.size()) {
            throw new IllegalArgumentException("ids, embeddings, and embedded must have equal sizes.");
        }

        for (int i = 0; i < ids.size(); i++) {
            upsertEmbeddingRow(ids.get(i), embeddings.get(i), embedded.get(i));
        }
    }

    private void upsertEmbeddingRow(String id, Embedding embedding, TextSegment segment) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(embedding, "embedding");
        Objects.requireNonNull(segment, "segment");

        try {
            String metadataJson = serializeMetadataJson(segment);
            byte[] vectorBytes = vectorBlob(embedding.vector());

            try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT OR REPLACE INTO "
                                    + quotedPhysicalTable
                                    + " (id, vector, content, metadata) VALUES (?,?,?,?)")) {
                statement.setString(1, id);
                statement.setBytes(2, vectorBytes);
                statement.setString(3, Objects.requireNonNullElse(segment.text(), ""));
                statement.setString(4, metadataJson);
                statement.executeUpdate();
            }
        } catch (SQLException | IOException failure) {
            throw new IllegalStateException("Failed upserting embedding row", failure);
        }
    }

    private static String serializeMetadataJson(TextSegment segment) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        if (segment.metadata() != null) {
            map.putAll(segment.metadata().toMap());
        }
        return OBJECT_MAPPER.writeValueAsString(map);
    }

    @Override
    public synchronized void removeAll(Collection<String> ids) {
        Objects.requireNonNull(ids, "ids");
        List<String> idList = ids instanceof List<String> list ? list : new ArrayList<>(ids);
        if (idList.isEmpty()) {
            return;
        }

        String placeholders = String.join(",", Collections.nCopies(idList.size(), "?"));
        String sql = "DELETE FROM " + quotedPhysicalTable + " WHERE id IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < idList.size(); i++) {
                statement.setString(i + 1, idList.get(i));
            }
            statement.executeUpdate();
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Failed deleting embeddings by id", sqlException);
        }
    }

    @Override
    public synchronized void removeAll(Filter filter) {
        if (filter == null) {
            removeAll();
            return;
        }
        if (!(filter instanceof IsEqualTo equality)) {
            throw new UnsupportedFeatureException("SQLite store only supports IsEqualTo filters: " + filter);
        }

        String sql =
                "DELETE FROM "
                        + quotedPhysicalTable
                        + " WHERE CAST(json_extract(metadata, ?) AS TEXT) = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, jsonPointerForKey(equality.key()));
            statement.setString(2, Objects.toString(equality.comparisonValue(), ""));
            statement.executeUpdate();
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Failed filtered delete in SQLite", sqlException);
        }
    }

    @Override
    public synchronized void removeAll() {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + quotedPhysicalTable);
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Failed clearing SQLite embedding table", sqlException);
        }
    }

    @Override
    public synchronized EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        Objects.requireNonNull(request, "request");
        Embedding queryEmbedding = request.queryEmbedding();
        Filter filter = request.filter();

        StringBuilder sql = new StringBuilder("SELECT id, vector, content, metadata FROM ").append(quotedPhysicalTable);
        List<Object> binds = new ArrayList<>();
        appendSearchFilter(filter, sql, binds);

        List<StoredRow> rows = loadRows(sql.toString(), binds);

        int maxResults = Math.max(1, request.maxResults());
        double minScore = request.minScore();

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        for (StoredRow row : rows) {
            Embedding stored = row.embedding();
            if (stored.vector().length != queryEmbedding.vector().length) {
                throw new IllegalStateException(
                        "Embedding dimension mismatch: query="
                                + queryEmbedding.vector().length
                                + " stored="
                                + stored.vector().length);
            }

            double cosineSimilarity = CosineSimilarity.between(queryEmbedding, stored);
            double relevanceScore = cosineSimilarityToRelevanceScore(cosineSimilarity);
            if (relevanceScore < minScore) {
                continue;
            }

            matches.add(new EmbeddingMatch<>(relevanceScore, row.id(), stored, row.segment()));
        }

        matches.sort((left, right) -> Double.compare(right.score(), left.score()));
        if (matches.size() > maxResults) {
            matches = new ArrayList<>(matches.subList(0, maxResults));
        }
        return new EmbeddingSearchResult<>(matches);
    }

    private void appendSearchFilter(Filter filter, StringBuilder sql, List<Object> binds) {
        if (filter == null) {
            return;
        }
        if (!(filter instanceof IsEqualTo equality)) {
            throw new UnsupportedFeatureException("SQLite store only supports IsEqualTo search filters: " + filter);
        }
        sql.append(" WHERE CAST(json_extract(metadata, ?) AS TEXT) = ?");
        binds.add(jsonPointerForKey(equality.key()));
        binds.add(Objects.toString(equality.comparisonValue(), ""));
    }

    private List<StoredRow> loadRows(String sql, List<Object> binds) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < binds.size(); i++) {
                statement.setObject(i + 1, binds.get(i));
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                List<StoredRow> rows = new ArrayList<>();
                while (resultSet.next()) {
                    String id = resultSet.getString(1);
                    byte[] vectorBlob = resultSet.getBytes(2);
                    String content = resultSet.getString(3);
                    String metadataJson = resultSet.getString(4);

                    float[] vector = floatsFromLittleEndian(vectorBlob);
                    Embedding embedding = Embedding.from(vector);

                    Map<String, Object> metadataMap = OBJECT_MAPPER.readValue(metadataJson, MAP_TYPE);
                    metadataMap = coerceMetadataForRead(metadataMap);
                    TextSegment segment = TextSegment.from(content, new Metadata(metadataMap));

                    rows.add(new StoredRow(id, embedding, segment));
                }
                return rows;
            }
        } catch (SQLException | IOException failure) {
            throw new IllegalStateException("Failed loading embeddings from SQLite", failure);
        }
    }

    private static Map<String, Object> coerceMetadataForRead(Map<String, Object> metadataMap) {
        Map<String, Object> coerced = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadataMap.entrySet()) {
            coerced.put(entry.getKey(), coerceJsonValue(entry.getValue()));
        }
        return coerced;
    }

    private static Object coerceJsonValue(Object value) {
        if (value instanceof Double doubleValue) {
            long rounded = Math.round(doubleValue);
            if (Math.abs(doubleValue - rounded) < 1e-9) {
                return (int) rounded == rounded ? (int) rounded : rounded;
            }
            return doubleValue.floatValue();
        }
        return value;
    }

    private static String jsonPointerForKey(String key) {
        if (Objects.requireNonNull(key, "key").startsWith("$")) {
            return key;
        }
        return "$." + key;
    }

    private static double cosineSimilarityToRelevanceScore(double cosineSimilarity) {
        return (cosineSimilarity + 1.0) / 2.0;
    }

    private static final class StoredRow {
        private final String id;
        private final Embedding embedding;
        private final TextSegment segment;

        private StoredRow(String id, Embedding embedding, TextSegment segment) {
            this.id = id;
            this.embedding = embedding;
            this.segment = segment;
        }

        private String id() {
            return id;
        }

        private Embedding embedding() {
            return embedding;
        }

        private TextSegment segment() {
            return segment;
        }
    }
}
