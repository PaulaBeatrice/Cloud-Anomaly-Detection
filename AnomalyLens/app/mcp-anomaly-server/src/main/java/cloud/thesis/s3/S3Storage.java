package cloud.thesis.s3;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@ApplicationScoped
public class S3Storage {

    @Inject
    S3Client s3;

    @ConfigProperty(name = "bucket.name")
    String bucket;

    public String bucket() { return bucket; }

    public Path downloadCsvToTemp(String key) throws Exception {
        Path dir = Files.createTempDirectory("mcp-input-");
        Path tmp = dir.resolve(UUID.randomUUID() + ".csv");

        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        s3.getObject(req, tmp);

        long size = Files.size(tmp);
        System.out.println("S3 DOWNLOAD OK s3://" + bucket + "/" + key + " -> " + tmp + " bytes=" + size);

        return tmp;
    }

    public void uploadCsvFromFile(String key, Path file) throws Exception {
        long size = Files.size(file);
        System.out.println("S3 UPLOAD START s3://" + bucket + "/" + key + " from=" + file + " bytes=" + size);

        if (size == 0) {
            throw new IllegalStateException("Refusing to upload empty file (0 bytes): " + file);
        }

        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("text/csv")
                .build();

        PutObjectResponse resp = s3.putObject(req, RequestBody.fromFile(file));
        System.out.println("S3 UPLOAD OK ETag=" + resp.eTag());
    }
}