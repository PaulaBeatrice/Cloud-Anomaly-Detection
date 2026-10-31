package cloud.thesis.api;

import cloud.thesis.mcpclient.McpClientService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/api/anomalies")
@Produces(MediaType.APPLICATION_JSON)
public class AnomaliesApiResource {

    @Inject
    McpClientService mcpClient;

    @GET
    @Path("/top")
    public JsonNode top(
            @QueryParam("scoredKey") String scoredKey,
            @QueryParam("k") @DefaultValue("5") int k
    ) {
        return mcpClient.callTool("explain_top", Map.of(
                "scoredKey", scoredKey,
                "k", k
        ));
    }
}