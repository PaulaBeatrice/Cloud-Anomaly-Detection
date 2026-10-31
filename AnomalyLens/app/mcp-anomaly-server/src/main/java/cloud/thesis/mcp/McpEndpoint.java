package cloud.thesis.mcp;

import cloud.thesis.llm.OllamaLlmService;
import cloud.thesis.s3.S3Storage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

@Path("/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpEndpoint {

    private static final Logger LOG = Logger.getLogger(McpEndpoint.class.getName());
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    S3Storage s3;

    @Inject
    OllamaLlmService llm;

    @ConfigProperty(name = "scorer.python")
    String scorerPython;

    @ConfigProperty(name = "scorer.script")
    String scorerScript;

    @ConfigProperty(name = "scorer.model")
    String scorerModel;

    private volatile Map<String, Object> lastRunMeta = new LinkedHashMap<>();

    private static final Map<String, String> QA_PROMPTS = Map.of(
            "MOST_CRITICAL_INTERVAL", "What is the most critical interval? Provide interval_start and anomaly_score with evidence.",
            "COUNT_WARN_CRIT", "How many warnings and criticals are present? Provide counts.",
            "TIME_RANGE", "What is the time range of the run (min/max interval_start)?",
            "TOP5_SUMMARY", "Summarize the top 5 anomalies (timestamp, score, severity).",
            "DID_WE_HAVE_CRITICALS", "Did we have critical anomalies? If yes, how many and when is the most severe one?",
            "IS_CLUSTERED", "Are anomalies clustered in a short window or spread out? Answer using the evidence."
    );

    @POST
    public Response handle(String body) {
        String cleaned = sanitizeBody(body);

        JsonNode req;
        try {
            req = mapper.readTree(cleaned);
        } catch (Exception e) {
            return Response.status(400).entity(jsonRpcError(null, -32700, "Parse error")).build();
        }

        if (!req.has("jsonrpc") || !req.has("method") || !"2.0".equals(req.get("jsonrpc").asText())) {
            return Response.status(400).entity(jsonRpcError(null, -32600, "Invalid Request")).build();
        }

        JsonNode id = req.has("id") ? req.get("id") : NullNode.getInstance();        String method = req.get("method").asText();
        JsonNode params = req.get("params");

        LOG.info("[MCP] method=" + method + " id=" + id);

        JsonNode result = switch (method) {
            case "initialize" -> initialize();
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            case "resources/list" -> resourcesList();
            case "resources/read" -> resourcesRead(params);
            default -> null;
        };

        if (result == null) {
            return Response.ok(jsonRpcError(id, -32601, "Method not found")).build();
        }

        if (result.has("error")) {
            return Response.ok(jsonRpcError(
                    id,
                    result.get("error").get("code").asInt(),
                    result.get("error").get("message").asText()
            )).build();
        }

        return Response.ok(jsonRpcResult(id, result)).build();
    }

    private String sanitizeBody(String body) {
        String cleaned = body == null ? "" : body.strip();

        if (!cleaned.isEmpty() && cleaned.charAt(0) == '\uFEFF') {
            cleaned = cleaned.substring(1);
        }

        return cleaned;
    }

    private JsonNode initialize() {
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("protocolVersion", "2025-06-18");

        payload.put("serverInfo", Map.of(
                "name", "mcp-anomaly-server",
                "version", "0.2.0"
        ));

        payload.put("capabilities", Map.of(
                "tools", Map.of(),
                "resources", Map.of()
        ));

        return mapper.valueToTree(payload);
    }

    private JsonNode toolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(Map.of(
                "name", "upload_file",
                "description", "Upload a CSV file to S3 under incoming/ using base64 content.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "key", Map.of(
                                        "type", "string",
                                        "description", "S3 key for upload, must start with incoming/, e.g. incoming/input.csv"
                                ),
                                "contentBase64", Map.of(
                                        "type", "string",
                                        "description", "Base64-encoded CSV file content"
                                )
                        ),
                        "required", List.of("key", "contentBase64")
                )
        ));

        tools.add(Map.of(
                "name", "score_file",
                "description", "Run anomaly scoring for an input CSV stored in S3 under incoming/ and write scored output to S3 under scored/.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "inputKey", Map.of(
                                        "type", "string",
                                        "description", "S3 key for input CSV, e.g. incoming/sample.csv"
                                ),
                                "outputKey", Map.of(
                                        "type", "string",
                                        "description", "Optional S3 key for scored CSV, e.g. scored/sample-scored.csv"
                                ),
                                "warnPercentile", Map.of(
                                        "type", "number",
                                        "description", "Warning percentile threshold, default 92.5"
                                ),
                                "critPercentile", Map.of(
                                        "type", "number",
                                        "description", "Critical percentile threshold, default 99.9"
                                )
                        ),
                        "required", List.of("inputKey")
                )
        ));

        tools.add(Map.of(
                "name", "explain_top",
                "description", "Return top-K most anomalous rows from a scored CSV stored in S3.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "scoredKey", Map.of(
                                        "type", "string",
                                        "description", "S3 key under scored/ prefix"
                                ),
                                "k", Map.of(
                                        "type", "integer",
                                        "description", "Number of rows to return, default 5, max 50",
                                        "default", 5,
                                        "maximum", 50
                                )
                        ),
                        "required", List.of("scoredKey")
                )
        ));

        tools.add(Map.of(
                "name", "download_scored",
                "description", "Download a scored CSV from S3 as base64 content.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "scoredKey", Map.of(
                                        "type", "string",
                                        "description", "S3 key under scored/ prefix"
                                )
                        ),
                        "required", List.of("scoredKey")
                )
        ));

        tools.add(Map.of(
                "name", "summarize_scored",
                "description", "Cloud LLM (Gemini): Generate an executive incident summary for a scored CSV in English.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "scoredKey", Map.of(
                                        "type", "string",
                                        "description", "S3 key under scored/"
                                ),
                                "k", Map.of(
                                        "type", "integer",
                                        "description", "Top-K evidence rows to include, default 10, max 50",
                                        "default", 10,
                                        "maximum", 50
                                )
                        ),
                        "required", List.of("scoredKey")
                )
        ));

        tools.add(Map.of(
                "name", "qa_last_run",
                "description", "Cloud LLM (Gemini): Answer predefined questions about the last scoring run in English.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "questionId", Map.of(
                                        "type", "string",
                                        "description", "Predefined question identifier"
                                ),
                                "k", Map.of(
                                        "type", "integer",
                                        "description", "Top-K evidence rows to include, default 5, max 50",
                                        "default", 5,
                                        "maximum", 50
                                )
                        ),
                        "required", List.of("questionId")
                )
        ));

        tools.add(Map.of(
                "name", "remediation_checklist",
                "description", "Cloud LLM (Gemini): Generate a remediation checklist based on scored anomalies in English.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "scoredKey", Map.of(
                                        "type", "string",
                                        "description", "S3 key under scored/"
                                ),
                                "k", Map.of(
                                        "type", "integer",
                                        "description", "Top-K evidence rows to include, default 10, max 50",
                                        "default", 10,
                                        "maximum", 50
                                )
                        ),
                        "required", List.of("scoredKey")
                )
        ));

        return mapper.valueToTree(Map.of("tools", tools));
    }

    private JsonNode toolsCall(JsonNode params) {
        String name = params != null && params.has("name") ? params.get("name").asText() : null;
        JsonNode args = params != null ? params.get("arguments") : null;

        if ("upload_file".equals(name)) return uploadFile(args);
        if ("score_file".equals(name)) return scoreFile(args);
        if ("explain_top".equals(name)) return explainTop(args);
        if ("download_scored".equals(name)) return downloadScored(args);

        if ("summarize_scored".equals(name)) return summarizeScored(args);
        if ("qa_last_run".equals(name)) return qaLastRun(args);
        if ("remediation_checklist".equals(name)) return remediationChecklist(args);

        return errorOnly(-32602, "Invalid params: unknown tool name '" + name + "'");
    }

    private JsonNode uploadFile(JsonNode args) {
        if (args == null || !args.has("key") || !args.has("contentBase64")) {
            return errorOnly(-32602, "Missing required arguments: key, contentBase64");
        }

        String key = args.get("key").asText();
        String contentBase64 = args.get("contentBase64").asText();

        if (key == null || key.isBlank()) return errorOnly(-32602, "key must not be blank");
        if (!key.startsWith("incoming/")) return errorOnly(-32602, "key must start with incoming/");
        if (!key.toLowerCase(Locale.ROOT).endsWith(".csv")) return errorOnly(-32602, "Only .csv uploads are allowed");
        if (contentBase64 == null || contentBase64.isBlank()) return errorOnly(-32602, "contentBase64 must not be blank");

        try {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(contentBase64);
            } catch (IllegalArgumentException iae) {
                return errorOnly(-32602, "contentBase64 is not valid base64");
            }

            final int MAX_BYTES = 25 * 1024 * 1024;

            if (bytes.length == 0) {
                return errorOnly(-32602, "Decoded file is empty");
            }

            if (bytes.length > MAX_BYTES) {
                return errorOnly(-32602, "Decoded file too large: " + bytes.length + " bytes, max " + MAX_BYTES);
            }

            java.nio.file.Path tmp = Files.createTempFile("mcp-upload-", ".csv");
            Files.write(tmp, bytes);

            s3.uploadCsvFromFile(key, tmp);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("bucket", s3.bucket());
            meta.put("key", key);
            meta.put("bytes", bytes.length);
            meta.put("uploadedAt", Instant.now().toString());

            return mapper.valueToTree(Map.of(
                    "content", List.of(Map.of(
                            "type", "text",
                            "text", "OK - uploaded CSV to s3://" + s3.bucket() + "/" + key + " (" + bytes.length + " bytes)"
                    )),
                    "meta", meta
            ));
        } catch (Exception e) {
            LOG.severe("[upload_file] Exception: " + e);
            e.printStackTrace();
            return errorOnly(-32000, "Exception during upload_file: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private JsonNode scoreFile(JsonNode args) {
        if (args == null || !args.has("inputKey")) {
            return errorOnly(-32602, "Missing required argument: inputKey");
        }

        String inputKey = args.get("inputKey").asText();

        if (!inputKey.startsWith("incoming/")) {
            return errorOnly(-32602, "inputKey must start with incoming/");
        }

        String baseName = inputKey.substring("incoming/".length());
        String derivedOutput = "scored/" + baseName.replace(".csv", "-scored.csv");

        String outputKey = args.has("outputKey") && !args.get("outputKey").asText().isBlank()
                ? args.get("outputKey").asText()
                : derivedOutput;

        double warn = args.has("warnPercentile") ? args.get("warnPercentile").asDouble() : 92.5;
        double crit = args.has("critPercentile") ? args.get("critPercentile").asDouble() : 99.9;

        Instant start = Instant.now();

        try {
            java.nio.file.Path localIn = s3.downloadCsvToTemp(inputKey);
            java.nio.file.Path localOut = Files.createTempFile("mcp-scored-", ".csv");

            ProcessBuilder pb = new ProcessBuilder(
                    scorerPython, scorerScript,
                    "--model", scorerModel,
                    "--input", localIn.toString(),
                    "--output", localOut.toString(),
                    "--warn", String.valueOf(warn),
                    "--crit", String.valueOf(crit),
                    "--timestamp-col", "interval_start"
            );

            pb.redirectErrorStream(true);

            Process p = pb.start();
            String logs = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();

            if (exit != 0) {
                LOG.severe("[score_file] Python exit=" + exit + " logs=" + logs);
                return errorOnly(-32000, "Scoring failed, python exit=" + exit + ": " + logs);
            }

            long outSize = Files.size(localOut);
            if (outSize == 0) {
                return errorOnly(-32000, "Scoring succeeded but produced empty output");
            }

            s3.uploadCsvFromFile(outputKey, localOut);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("timestamp", Instant.now().toString());
            meta.put("bucket", s3.bucket());
            meta.put("inputKey", inputKey);
            meta.put("outputKey", outputKey);
            meta.put("warnPercentile", warn);
            meta.put("critPercentile", crit);
            meta.put("durationMs", java.time.Duration.between(start, Instant.now()).toMillis());
            meta.put("model", scorerModel);

            lastRunMeta = meta;

            return mapper.valueToTree(Map.of(
                    "content", List.of(
                            Map.of("type", "text", "text", "OK - wrote scored output to s3://" + s3.bucket() + "/" + outputKey),
                            Map.of("type", "text", "text", "Logs: " + logs)
                    ),
                    "meta", meta
            ));
        } catch (Exception e) {
            LOG.severe("[score_file] Exception: " + e);
            e.printStackTrace();
            return errorOnly(-32000, "Exception during score_file: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private JsonNode explainTop(JsonNode args) {
        if (args == null || !args.has("scoredKey")) {
            return errorOnly(-32602, "Missing required argument: scoredKey");
        }

        String scoredKey = args.get("scoredKey").asText();
        int k = args.has("k") ? args.get("k").asInt() : 5;

        if (k <= 0) k = 5;
        if (k > 50) k = 50;

        if (!scoredKey.startsWith("scored/")) {
            return errorOnly(-32602, "scoredKey must be under scored/ prefix");
        }

        try {
            java.nio.file.Path localCsv = s3.downloadCsvToTemp(scoredKey);
            List<ScoredRow> rows = loadScoredRows(localCsv);

            if (rows.isEmpty()) {
                return mapper.valueToTree(Map.of(
                        "content", List.of(Map.of(
                                "type", "text",
                                "text", "No rows found in scored CSV: " + scoredKey
                        ))
                ));
            }

            rows.sort((a, b) -> Double.compare(b.anomalyScore, a.anomalyScore));
            List<ScoredRow> top = rows.stream().limit(k).toList();

            String markdown = renderTopAsMarkdown(scoredKey, top);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("bucket", s3.bucket());
            meta.put("scoredKey", scoredKey);
            meta.put("k", top.size());
            meta.put("generatedAt", Instant.now().toString());

            return mapper.valueToTree(Map.of(
                    "content", List.of(Map.of("type", "text", "text", markdown)),
                    "meta", meta
            ));
        } catch (Exception e) {
            LOG.severe("[explain_top] Exception: " + e);
            e.printStackTrace();
            return errorOnly(-32000, "Exception during explain_top: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private JsonNode downloadScored(JsonNode args) {
        if (args == null || !args.has("scoredKey")) {
            return errorOnly(-32602, "Missing required argument: scoredKey");
        }

        String scoredKey = args.get("scoredKey").asText();

        if (scoredKey == null || scoredKey.isBlank()) {
            return errorOnly(-32602, "scoredKey must not be blank");
        }

        if (!scoredKey.startsWith("scored/")) {
            return errorOnly(-32602, "scoredKey must be under scored/ prefix");
        }

        if (!scoredKey.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return errorOnly(-32602, "Only .csv downloads are allowed");
        }

        try {
            java.nio.file.Path localCsv = s3.downloadCsvToTemp(scoredKey);
            byte[] bytes = Files.readAllBytes(localCsv);

            String base64 = Base64.getEncoder().encodeToString(bytes);
            String filename = scoredKey.substring(scoredKey.lastIndexOf('/') + 1);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("bucket", s3.bucket());
            meta.put("scoredKey", scoredKey);
            meta.put("filename", filename);
            meta.put("bytes", bytes.length);
            meta.put("downloadedAt", Instant.now().toString());

            return mapper.valueToTree(Map.of(
                    "contentBase64", base64,
                    "filename", filename,
                    "mimeType", "text/csv",
                    "meta", meta
            ));
        } catch (Exception e) {
            LOG.severe("[download_scored] Exception: " + e);
            e.printStackTrace();
            return errorOnly(-32000, "Exception during download_scored: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private JsonNode summarizeScored(JsonNode args) {
        if (args == null || !args.has("scoredKey")) {
            return errorOnly(-32602, "Missing scoredKey");
        }

        String scoredKey = args.get("scoredKey").asText();
        int k = args.has("k") ? args.get("k").asInt() : 10;

        if (k < 1) k = 10;
        if (k > 50) k = 50;

        if (!scoredKey.startsWith("scored/")) {
            return errorOnly(-32602, "scoredKey must be under scored/");
        }

        try {
            java.nio.file.Path local = s3.downloadCsvToTemp(scoredKey);
            Map<String, Object> stats = scoredStats(local, k);

            String system = "You are an SRE incident analyst. Use ONLY the provided evidence. Do NOT invent facts. "
                    + "If evidence is insufficient, say \"Insufficient data\" and explain what is missing. Write in clear English.";

            String prompt = "Generate an executive incident summary in English.\n\n"
                    + "Output format:\n"
                    + "1) Summary (1–2 paragraphs)\n"
                    + "2) Severity (warnings/criticals counts, overall severity)\n"
                    + "3) Timeline (min/max interval_start and whether anomalies cluster)\n"
                    + "4) Evidence (topK: timestamps + scores + flags)\n"
                    + "5) Recommended Next Steps (3–8 actionable steps)\n\n"
                    + "EVIDENCE (JSON):\n" + toJson(stats);

            String text = llm.generate(system, prompt);

            return mapper.valueToTree(Map.of(
                    "content", List.of(Map.of("type", "text", "text", text)),
                    "meta", Map.of("scoredKey", scoredKey, "k", k, "provider", "gemini")
            ));
        } catch (Exception e) {
            LOG.severe("[summarize_scored] Exception: " + e);
            e.printStackTrace();
            return errorOnly(-32000, "summarize_scored failed: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private JsonNode qaLastRun(JsonNode args) {
        if (args == null || !args.has("questionId")) {
            return errorOnly(-32602, "Missing questionId");
        }

        String qid = args.get("questionId").asText();
        int k = args.has("k") ? args.get("k").asInt() : 5;

        if (k < 1) k = 5;
        if (k > 50) k = 50;

        String scoredKey = lastRunMeta != null ? (String) lastRunMeta.get("outputKey") : null;

        if (scoredKey == null || scoredKey.isBlank()) {
            return errorOnly(-32000, "No last run available. Call score_file first.");
        }

        String question = QA_PROMPTS.get(qid);

        if (question == null) {
            return errorOnly(-32602, "Unknown questionId: " + qid);
        }

        try {
            java.nio.file.Path local = s3.downloadCsvToTemp(scoredKey);
            Map<String, Object> stats = scoredStats(local, k);

            String system = "You are an SRE analyst. Answer ONLY using the provided evidence. Do NOT invent. Write in English.";
            String prompt = "Question: " + question + "\n\nEVIDENCE (JSON):\n" + toJson(stats);

            String text = llm.generate(system, prompt);

            return mapper.valueToTree(Map.of(
                    "content", List.of(Map.of("type", "text", "text", text)),
                    "meta", Map.of("questionId", qid, "scoredKey", scoredKey, "k", k, "provider", "gemini")
            ));
        } catch (Exception e) {
            LOG.severe("[qa_last_run] Exception: " + e);
            e.printStackTrace();
            return errorOnly(-32000, "qa_last_run failed: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private JsonNode remediationChecklist(JsonNode args) {
        if (args == null || !args.has("scoredKey")) {
            return errorOnly(-32602, "Missing scoredKey");
        }

        String scoredKey = args.get("scoredKey").asText();
        int k = args.has("k") ? args.get("k").asInt() : 10;

        if (k < 1) k = 10;
        if (k > 50) k = 50;

        if (!scoredKey.startsWith("scored/")) {
            return errorOnly(-32602, "scoredKey must be under scored/");
        }

        try {
            java.nio.file.Path local = s3.downloadCsvToTemp(scoredKey);
            Map<String, Object> stats = scoredStats(local, k);

            String system = "You are an on-call SRE. Use ONLY the evidence provided. Write in English. Do NOT invent root causes.";

            String prompt = "Create a remediation checklist, max 10 steps, tailored to the evidence.\n"
                    + "Requirements:\n"
                    + "- Steps must be actionable.\n"
                    + "- Reference severity, warnings vs criticals.\n"
                    + "- Include 2–3 timeline-based checks around the top anomalies.\n\n"
                    + "EVIDENCE (JSON):\n" + toJson(stats);

            String text = llm.generate(system, prompt);

            return mapper.valueToTree(Map.of(
                    "content", List.of(Map.of("type", "text", "text", text)),
                    "meta", Map.of("scoredKey", scoredKey, "k", k, "provider", "gemini")
            ));
        } catch (Exception e) {
            LOG.severe("[remediation_checklist] Exception: " + e);
            e.printStackTrace();
            return errorOnly(-32000, "remediation_checklist failed: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private JsonNode resourcesList() {
        return mapper.valueToTree(Map.of("resources", List.of(
                Map.of("uri", "runs://last", "name", "Last run metadata", "mimeType", "application/json")
        )));
    }

    private JsonNode resourcesRead(JsonNode params) {
        String uri = params != null && params.has("uri") ? params.get("uri").asText() : "";

        if (!"runs://last".equals(uri)) {
            return errorOnly(-32602, "Unknown resource uri");
        }

        if (lastRunMeta == null || lastRunMeta.isEmpty()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("timestamp", Instant.now().toString());
            meta.put("warnPercentile", 92.5);
            meta.put("critPercentile", 99.9);
            meta.put("message", "No scoring run has been executed yet.");

            return mapper.valueToTree(Map.of(
                    "contents", List.of(Map.of(
                            "uri", "runs://last",
                            "mimeType", "application/json",
                            "text", toJson(meta)
                    ))
            ));
        }

        return mapper.valueToTree(Map.of(
                "contents", List.of(Map.of(
                        "uri", "runs://last",
                        "mimeType", "application/json",
                        "text", toJson(lastRunMeta)
                ))
        ));
    }

    private static class ScoredRow {
        final String intervalStart;
        final double anomalyScore;
        final boolean isWarning;
        final boolean isCritical;

        private ScoredRow(String intervalStart, double anomalyScore, boolean isWarning, boolean isCritical) {
            this.intervalStart = intervalStart;
            this.anomalyScore = anomalyScore;
            this.isWarning = isWarning;
            this.isCritical = isCritical;
        }
    }

    private List<ScoredRow> loadScoredRows(java.nio.file.Path csvPath) throws Exception {
        List<String> lines = Files.readAllLines(csvPath);

        if (lines.size() < 2) {
            return List.of();
        }

        String[] cols = lines.get(0).split(",");

        int idxTs = -1;
        int idxScore = -1;
        int idxWarn = -1;
        int idxCrit = -1;

        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim();

            if ("interval_start".equals(c)) {
                idxTs = i;
            } else if ("anomaly_score".equals(c)) {
                idxScore = i;
            } else if ("is_warning".equals(c)) {
                idxWarn = i;
            } else if ("is_critical".equals(c)) {
                idxCrit = i;
            }
        }

        if (idxTs < 0 || idxScore < 0) {
            throw new IllegalStateException("Missing required columns: interval_start and/or anomaly_score");
        }

        List<ScoredRow> out = new ArrayList<>();

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split(",", -1);

            if (parts.length < cols.length) {
                continue;
            }

            String ts = parts[idxTs].trim();

            double score;
            try {
                score = Double.parseDouble(parts[idxScore].trim());
            } catch (NumberFormatException nfe) {
                continue;
            }

            String w = idxWarn >= 0 ? parts[idxWarn].trim() : "";
            String c = idxCrit >= 0 ? parts[idxCrit].trim() : "";

            boolean warn = idxWarn >= 0 && ("true".equalsIgnoreCase(w) || "1".equals(w));
            boolean crit = idxCrit >= 0 && ("true".equalsIgnoreCase(c) || "1".equals(c));

            out.add(new ScoredRow(ts, score, warn, crit));
        }

        return out;
    }

    private Map<String, Object> scoredStats(java.nio.file.Path csvPath, int k) throws Exception {
        List<ScoredRow> rows = new ArrayList<>(loadScoredRows(csvPath));

        int total = rows.size();
        long warn = rows.stream().filter(r -> r.isWarning).count();
        long crit = rows.stream().filter(r -> r.isCritical).count();

        long minTs = 0;
        long maxTs = 0;
        boolean hasTs = false;

        for (ScoredRow r : rows) {
            try {
                long t = Long.parseLong(r.intervalStart);

                if (!hasTs) {
                    minTs = t;
                    maxTs = t;
                    hasTs = true;
                } else {
                    minTs = Math.min(minTs, t);
                    maxTs = Math.max(maxTs, t);
                }
            } catch (Exception ignored) {
            }
        }

        rows.sort((a, b) -> Double.compare(b.anomalyScore, a.anomalyScore));
        List<ScoredRow> top = rows.stream().limit(k).toList();

        List<Map<String, Object>> topList = new ArrayList<>();

        for (ScoredRow r : top) {
            topList.add(Map.of(
                    "interval_start", r.intervalStart,
                    "anomaly_score", r.anomalyScore,
                    "is_warning", r.isWarning,
                    "is_critical", r.isCritical
            ));
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRows", total);
        m.put("warningCount", warn);
        m.put("criticalCount", crit);
        m.put("minIntervalStart", hasTs ? minTs : null);
        m.put("maxIntervalStart", hasTs ? maxTs : null);
        m.put("topK", topList);

        return m;
    }

    private String renderTopAsMarkdown(String scoredKey, List<ScoredRow> top) {
        StringBuilder sb = new StringBuilder();

        sb.append("Top ").append(top.size()).append(" anomalies from `").append(scoredKey).append("`\n\n");
        sb.append("| # | interval_start | anomaly_score | warning | critical |\n");
        sb.append("|---:|---|---:|:---:|:---:|\n");

        int i = 1;
        long warnCount = 0;
        long critCount = 0;

        for (ScoredRow r : top) {
            if (r.isWarning) warnCount++;
            if (r.isCritical) critCount++;

            sb.append("| ").append(i++).append(" | ")
                    .append(r.intervalStart).append(" | ")
                    .append(String.format(Locale.US, "%.6f", r.anomalyScore)).append(" | ")
                    .append(r.isWarning ? "✅" : "—").append(" | ")
                    .append(r.isCritical ? "🟥" : "—").append(" |\n");
        }

        sb.append("\nSummary: ")
                .append(critCount).append(" critical, ")
                .append(warnCount).append(" warning within top-").append(top.size()).append(".");

        return sb.toString();
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode jsonRpcResult(JsonNode id, JsonNode result) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.set("id", id == null || id.isNull() ? NullNode.getInstance() : id);
        root.set("result", result);
        return root;
    }

    private JsonNode jsonRpcError(JsonNode id, int code, String message) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.set("id", id == null || id.isNull() ? NullNode.getInstance() : id);

        ObjectNode err = mapper.createObjectNode();
        err.put("code", code);
        err.put("message", message == null ? "" : message);

        root.set("error", err);
        return root;
    }

    private JsonNode errorOnly(int code, String message) {
        return mapper.valueToTree(Map.of(
                "error",
                Map.of(
                        "code", code,
                        "message", message == null ? "" : message
                )
        ));
    }
}