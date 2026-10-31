package cloud.thesis.api;

import cloud.thesis.mcpclient.McpClientService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/scoring")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScoringApiResource {

    @Inject
    McpClientService mcpClient;

    public record ScoreFileRequest(
            String inputKey,
            String outputKey,
            Double warnPercentile,
            Double critPercentile
    ) {}

    @POST
    @Path("/run")
    public JsonNode run(ScoreFileRequest request) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("inputKey", request.inputKey());

        if (request.outputKey() != null && !request.outputKey().isBlank()) {
            args.put("outputKey", request.outputKey());
        }

        if (request.warnPercentile() != null) {
            args.put("warnPercentile", request.warnPercentile());
        }

        if (request.critPercentile() != null) {
            args.put("critPercentile", request.critPercentile());
        }

        return mcpClient.callTool("score_file", args);
    }
}