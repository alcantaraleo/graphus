package io.graphus.cli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.cli.mcp.tools.BlastRadiusTool;
import io.graphus.cli.mcp.tools.CalleesTool;
import io.graphus.cli.mcp.tools.CallersTool;
import io.graphus.cli.mcp.tools.HotspotsTool;
import io.graphus.cli.mcp.tools.ModuleDepsTool;
import io.graphus.cli.mcp.tools.OwnershipTool;
import io.graphus.cli.mcp.tools.SearchTool;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

public final class GraphusMcpServer {

    private final GraphusMcpContext ctx;

    public GraphusMcpServer(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public McpSyncServer build(String version) {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(new ObjectMapper());

        ServerCapabilities capabilities = new ServerCapabilities.Builder()
                .tools(Boolean.TRUE)
                .build();

        return McpServer.sync(transport)
                .serverInfo("graphus", version)
                .capabilities(capabilities)
                .tools(
                        new SearchTool(ctx).spec(),
                        new BlastRadiusTool(ctx).spec(),
                        new CallersTool(ctx).spec(),
                        new CalleesTool(ctx).spec(),
                        new ModuleDepsTool(ctx).spec(),
                        new HotspotsTool(ctx).spec(),
                        new OwnershipTool(ctx).spec())
                .build();
    }
}
