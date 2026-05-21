package io.graphus.cli.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.indexer.GraphIndexer;
import io.graphus.indexer.GraphSearchHit;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchToolTest {

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        SearchTool tool = new SearchTool(ctx);
        SyncToolSpecification spec = tool.spec();
        assertEquals("graphus_search", spec.tool().name());
    }

    @Test
    void callHandler_returnsHitsAsJson() throws Exception {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        GraphIndexer indexer = mock(GraphIndexer.class);
        when(ctx.graphIndexer()).thenReturn(indexer);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(indexer.query("find payment service", null, 10))
                .thenReturn(List.of(new GraphSearchHit(0.92, "PaymentService.process()", Map.of("kind", "METHOD"))));

        SearchTool tool = new SearchTool(ctx);
        CallToolRequest request = new CallToolRequest("graphus_search",
                Map.of("query", "find payment service"));
        CallToolResult result = tool.spec().call().apply(null, request.arguments());

        assertFalse(Boolean.TRUE.equals(result.isError()));
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("PaymentService.process()"));
        assertTrue(json.contains("0.92"));
    }
}
