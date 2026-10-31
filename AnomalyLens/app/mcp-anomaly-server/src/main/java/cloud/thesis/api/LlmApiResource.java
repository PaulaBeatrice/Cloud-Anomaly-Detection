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

@Path("/api/llm")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class LlmApiResource {

    @Inject
    McpClientService mcpClient;

    public record SummaryRequest(
            String scoredKey,
            Integer k
    ) {}

    public record RemediationRequest(
            String scoredKey,
            Integer k
    ) {}

    public record QaLastRunRequest(
            String questionId,
            Integer k
    ) {}

    @POST
    @Path("/summary")
    public JsonNode summary(SummaryRequest request) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scoredKey", request.scoredKey());
        args.put("k", request.k() == null ? 10 : request.k());

        return mcpClient.callTool("summarize_scored", args);
    }

    @POST
    @Path("/remediation")
    public JsonNode remediation(RemediationRequest request) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scoredKey", request.scoredKey());
        args.put("k", request.k() == null ? 10 : request.k());

        return mcpClient.callTool("remediation_checklist", args);
    }

    @POST
    @Path("/qa-last-run")
    public JsonNode qaLastRun(QaLastRunRequest request) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("questionId", request.questionId());
        args.put("k", request.k() == null ? 5 : request.k());

        return mcpClient.callTool("qa_last_run", args);
    }
}