package cloud.thesis.api;

import cloud.thesis.mcpclient.McpClientService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/runs")
@Produces(MediaType.APPLICATION_JSON)
public class RunsApiResource {

    @Inject
    McpClientService mcpClient;

    @GET
    @Path("/last")
    public JsonNode lastRun() {
        return mcpClient.readResource("runs://last");
    }
}