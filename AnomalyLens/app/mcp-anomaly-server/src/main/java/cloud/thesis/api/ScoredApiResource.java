package cloud.thesis.api;

import cloud.thesis.mcpclient.McpClientService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/api/scored")
@Produces(MediaType.APPLICATION_JSON)
public class ScoredApiResource {

    @Inject
    McpClientService mcpClient;

    @GET
    @Path("/download")
    public JsonNode download(@QueryParam("scoredKey") String scoredKey) {
        return mcpClient.callTool("download_scored", Map.of(
                "scoredKey", scoredKey
        ));
    }

    @GET
    @Path("/summary")
    public JsonNode summary(
            @QueryParam("scoredKey") String scoredKey,
            @QueryParam("k") @DefaultValue("10") int k
    ) {
        return mcpClient.callTool("summarize_scored", Map.of(
                "scoredKey", scoredKey,
                "k", k
        ));
    }
}