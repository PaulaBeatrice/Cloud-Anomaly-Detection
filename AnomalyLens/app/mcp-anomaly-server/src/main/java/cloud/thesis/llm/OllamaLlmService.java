package cloud.thesis.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@ApplicationScoped
public class OllamaLlmService {

    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @ConfigProperty(name = "gemini.baseUrl", defaultValue = "https://generativelanguage.googleapis.com/v1beta")
    String baseUrl;

    @ConfigProperty(name = "gemini.model", defaultValue = "gemini-1.5-flash")
    String model;

    @ConfigProperty(name = "gemini.temperature", defaultValue = "0.2")
    double temperature;

    @ConfigProperty(name = "gemini.maxOutputTokens", defaultValue = "700")
    int maxOutputTokens;

    public String generate(String system, String prompt) throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY environment variable is not set");
        }

        String url = baseUrl + "/models/" + model + ":generateContent?key=" + apiKey;

        ObjectNode req = mapper.createObjectNode();

        ObjectNode systemInstruction = mapper.createObjectNode();
        ArrayNode systemParts = mapper.createArrayNode();
        ObjectNode systemText = mapper.createObjectNode();
        systemText.put("text", system);
        systemParts.add(systemText);
        systemInstruction.set("parts", systemParts);
        req.set("systemInstruction", systemInstruction);

        ArrayNode contents = mapper.createArrayNode();
        ObjectNode userContent = mapper.createObjectNode();
        userContent.put("role", "user");

        ArrayNode userParts = mapper.createArrayNode();
        ObjectNode userText = mapper.createObjectNode();
        userText.put("text", prompt);
        userParts.add(userText);

        userContent.set("parts", userParts);
        contents.add(userContent);
        req.set("contents", contents);

        ObjectNode generationConfig = mapper.createObjectNode();
        generationConfig.put("temperature", temperature);
        generationConfig.put("maxOutputTokens", maxOutputTokens);
        req.set("generationConfig", generationConfig);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req)))
                .build();

        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 300) {
            throw new RuntimeException("Gemini error " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode json = mapper.readTree(resp.body());

        StringBuilder sb = new StringBuilder();

        JsonNode candidates = json.path("candidates");
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                JsonNode parts = candidate.path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        String text = part.path("text").asText(null);
                        if (text != null && !text.isBlank()) {
                            sb.append(text);
                        }
                    }
                }
            }
        }

        String text = sb.toString().trim();

        if (text.isBlank()) {
            throw new RuntimeException("Gemini returned no text: " + resp.body());
        }

        return text;
    }
}