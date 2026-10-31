package cloud.thesis.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Logger;

@Path("/api/download")
public class DownloadResource {

    private static final Logger LOG = Logger.getLogger(DownloadResource.class.getName());

    @Inject
    S3Client s3;

    @ConfigProperty(name = "bucket.name")
    String bucket;

    @GET
    @Produces("text/csv")
    public Response download(@QueryParam("key") String key) {

        if (key == null || key.isBlank()) {
            return Response.status(400)
                    .entity("Missing required query param: key")
                    .build();
        }

        // Strict guard: ONLY allow downloads from scored/
        if (!key.startsWith("scored/")) {
            return Response.status(400)
                    .entity("Invalid key. Only scored/ prefix is allowed.")
                    .build();
        }

        // Basic hardening
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("..") || lower.contains("\\") || lower.contains("%2e%2e")) {
            return Response.status(400)
                    .entity("Invalid key.")
                    .build();
        }

        try {
            // Read metadata (nice-to-have: length/type)
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());

            String filename = key.substring(key.lastIndexOf('/') + 1);
            String contentType = head.contentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = "text/csv";
            }

            StreamingOutput stream = (OutputStream out) -> {
                GetObjectRequest req = GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();

                try (ResponseInputStream<?> in = s3.getObject(req)) {
                    in.transferTo(out);
                    out.flush();
                }
            };

            return Response.ok(stream)
                    .header("Content-Type", contentType)
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Length", String.valueOf(head.contentLength()))
                    .build();

        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            return Response.status(404)
                    .entity(("S3 key not found: " + key).getBytes(StandardCharsets.UTF_8))
                    .build();

        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            LOG.severe("S3 error for key=" + key + ": " + e.awsErrorDetails().errorMessage());
            return Response.status(500)
                    .entity(("S3 error: " + e.awsErrorDetails().errorMessage()).getBytes(StandardCharsets.UTF_8))
                    .build();

        } catch (Exception e) {
            LOG.severe("Download failed for key=" + key + ": " + e.getMessage());
            return Response.status(500)
                    .entity(("Download failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8))
                    .build();
        }
    }
}