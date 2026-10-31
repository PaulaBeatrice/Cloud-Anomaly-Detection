package cloud.thesis.mcpclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class McpClientService {

    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @ConfigProperty(name = "mcp.server.url")
    String mcpServerUrl;

    public JsonNode initialize() {
        ObjectNode request = baseRequest("initialize");
        return send(request);
    }

    public JsonNode listTools() {
        ObjectNode request = baseRequest("tools/list");
        return send(request);
    }

    public JsonNode callTool(String toolName, Object arguments) {
        ObjectNode request = baseRequest("tools/call");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", mapper.valueToTree(arguments == null ? Map.of() : arguments));

        request.set("params", params);

        return send(request);
    }

    public JsonNode listResources() {
        ObjectNode request = baseRequest("resources/list");
        return send(request);
    }

    public JsonNode readResource(String uri) {
        ObjectNode request = baseRequest("resources/read");

        ObjectNode params = mapper.createObjectNode();
        params.put("uri", uri);

        request.set("params", params);

        return send(request);
    }

    private ObjectNode baseRequest(String method) {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", UUID.randomUUID().toString());
        request.put("method", method);
        return request;
    }

    private JsonNode send(ObjectNode request) {
        try {
            String payload = mapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(mcpServerUrl))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WebApplicationException(
                        Response.status(502)
                                .entity(Map.of(
                                        "message", "MCP server returned non-2xx response",
                                        "status", response.statusCode(),
                                        "body", response.body()
                                ))
                                .build()
                );
            }

            JsonNode root = mapper.readTree(response.body());

            if (root.has("error")) {
                throw new WebApplicationException(
                        Response.status(502)
                                .entity(Map.of(
                                        "message", "MCP JSON-RPC error",
                                        "error", root.get("error")
                                ))
                                .build()
                );
            }

            if (!root.has("result")) {
                throw new WebApplicationException(
                        Response.status(502)
                                .entity(Map.of(
                                        "message", "MCP response does not contain result",
                                        "response", root
                                ))
                                .build()
                );
            }

            return root.get("result");
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new WebApplicationException(
                    Response.status(502)
                            .entity(Map.of(
                                    "message", "Failed to call MCP server",
                                    "exception", e.getClass().getName(),
                                    "details", e.getMessage() == null ? "" : e.getMessage()
                            ))
                            .build()
            );
        }
    }
}