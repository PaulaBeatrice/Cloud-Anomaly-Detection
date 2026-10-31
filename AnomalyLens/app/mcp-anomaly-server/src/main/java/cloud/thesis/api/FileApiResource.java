package cloud.thesis.api;

import cloud.thesis.mcpclient.McpClientService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/api/files")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class FileApiResource {

    @Inject
    McpClientService mcpClient;

    public record UploadFileRequest(
            String key,
            String contentBase64
    ) {}

    @POST
    @Path("/upload")
    public JsonNode upload(UploadFileRequest request) {
        return mcpClient.callTool("upload_file", Map.of(
                "key", request.key(),
                "contentBase64", request.contentBase64()
        ));
    }
}