package org.alexmond.jvmlens;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/**
 * Runs jvmlens as an MCP server over stdio, exposing the {@link ProfileTools} surface as
 * scoped, navigable tools (overview → drill into hot paths → pull allocation sites) so an
 * agent fetches only the slice it needs rather than one big blob. The server only serves
 * structured profile data — it never calls an LLM, so recordings never leave the host.
 */
@Component
@Command(name = "mcp", mixinStandardHelpOptions = true,
		description = "Run an MCP server (stdio) exposing scoped, navigable profile tools.")
public class McpServerCommand implements Callable<Integer> {

	/** Shared input schema: every tool takes a recording path plus optional scoping. */
	private static final String INPUT_SCHEMA = """
			{"type":"object","properties":{\
			"file":{"type":"string","description":"Path to a .jfr recording"},\
			"appPackages":{"type":"array","items":{"type":"string"},"description":"Application package prefixes (include-only)"},\
			"exclude":{"type":"array","items":{"type":"string"},"description":"Extra non-application package prefixes"}\
			},"required":["file"]}""";

	@Override
	public Integer call() throws Exception {
		StdioServerTransportProvider transport = new StdioServerTransportProvider(new ObjectMapper());
		McpSyncServer server = McpServer.sync(transport)
			.serverInfo("jvmlens", "0.1.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool("overview", "Event counts, the heuristic cause, and which drill-down tools to use next.",
					ProfileTools::overview),
					tool("hot_paths", "Application-attributed hot call paths, by sample share.",
							ProfileTools::hotPaths),
					tool("hot_leaves", "Leaf (self-time) hot methods, runtime included.", ProfileTools::hotLeaves),
					tool("allocations", "Top allocation sites and allocated types, by estimated bytes.",
							ProfileTools::allocations),
					tool("lock_contention", "Lock contention by application method and contended monitors.",
							ProfileTools::lockContention))
			.build();
		Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
		new CountDownLatch(1).await(); // serve until the host closes the process
		return 0;
	}

	private static SyncToolSpecification tool(String name, String description, Function<ProfileSummary, String> view) {
		BiFunction<McpSyncServerExchange, Map<String, Object>, CallToolResult> handler = (exchange, args) -> {
			try {
				Path file = Path.of((String) args.get("file"));
				Scope scope = Scope.of(strings(args.get("appPackages")), strings(args.get("exclude")));
				return new CallToolResult(view.apply(Summarizer.analyze(file, scope)), false);
			}
			catch (Exception ex) {
				return new CallToolResult("jvmlens: " + ex.getMessage(), true);
			}
		};
		return new SyncToolSpecification(new Tool(name, description, INPUT_SCHEMA), handler);
	}

	private static List<String> strings(Object value) {
		return (value instanceof List<?> list) ? list.stream().map(String::valueOf).toList() : null;
	}

}
