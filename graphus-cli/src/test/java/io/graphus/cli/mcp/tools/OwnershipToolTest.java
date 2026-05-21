package io.graphus.cli.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OwnershipToolTest {

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.stateDir()).thenReturn(Path.of("/tmp/no-such-state-" + System.nanoTime()));

        OwnershipTool tool = new OwnershipTool(ctx);
        SyncToolSpecification spec = tool.spec();
        assertEquals("graphus_ownership", spec.tool().name());
    }

    @Test
    void callHandler_returnsIsError_whenOwnershipJsonMissing(@TempDir Path tempDir) {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.stateDir()).thenReturn(tempDir);

        OwnershipTool tool = new OwnershipTool(ctx);
        CallToolResult result = tool.spec().call().apply(null, Map.of());

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("git-analyze"), "Expected error to mention git-analyze but got: " + text);
    }

    @Test
    void callHandler_returnsOwnershipMap_whenOwnershipJsonExists(@TempDir Path tempDir) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> ownership = Map.of(
                "src/main/java/io/graphus/cli/GraphusCli.java", "alice@example.com",
                "src/main/java/io/graphus/parser/JavaParser.java", "bob@example.com"
        );
        Files.writeString(tempDir.resolve("ownership.json"), mapper.writeValueAsString(ownership));

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(mapper);
        when(ctx.stateDir()).thenReturn(tempDir);

        OwnershipTool tool = new OwnershipTool(ctx);
        CallToolResult result = tool.spec().call().apply(null, Map.of());

        assertTrue(Boolean.FALSE.equals(result.isError()) || result.isError() == null);
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("alice@example.com"));
        assertTrue(json.contains("bob@example.com"));
    }

    @Test
    void callHandler_filtersResults_whenPathArgumentProvided(@TempDir Path tempDir) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> ownership = Map.of(
                "src/main/java/io/graphus/cli/GraphusCli.java", "alice@example.com",
                "src/main/java/io/graphus/parser/JavaParser.java", "bob@example.com"
        );
        Files.writeString(tempDir.resolve("ownership.json"), mapper.writeValueAsString(ownership));

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(mapper);
        when(ctx.stateDir()).thenReturn(tempDir);

        OwnershipTool tool = new OwnershipTool(ctx);
        CallToolResult result = tool.spec().call().apply(null, Map.of("path", "cli"));

        assertTrue(Boolean.FALSE.equals(result.isError()) || result.isError() == null);
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("alice@example.com"), "Expected cli entry with alice");
        assertTrue(!json.contains("bob@example.com"), "Expected parser entry to be filtered out");
    }
}
