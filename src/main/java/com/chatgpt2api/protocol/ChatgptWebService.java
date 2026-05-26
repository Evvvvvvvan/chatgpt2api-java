package com.chatgpt2api.protocol;

import com.chatgpt2api.account.AccountService;
import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.http.UpstreamHttpClient;
import com.chatgpt2api.image.ImageStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatgptWebService {
    private static final String BASE_URL = "https://chatgpt.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    private static final Pattern FILE_ID = Pattern.compile("file[-_](?!service\\b)[A-Za-z0-9_-]+");
    private static final Pattern SEDIMENT_ID = Pattern.compile("sediment://([A-Za-z0-9_-]+)");
    private final ObjectMapper mapper;
    private final UpstreamHttpClient http;
    private final PowService pow;
    private final TurnstileService turnstile;
    private final AccountService accounts;
    private final ImageStorageService imageStorage;
    private final String deviceId = uuid();
    private final String sessionId = uuid();

    public ChatgptWebService(ObjectMapper mapper, UpstreamHttpClient http, PowService pow, TurnstileService turnstile,
                             AccountService accounts, ImageStorageService imageStorage) {
        this.mapper = mapper;
        this.http = http;
        this.pow = pow;
        this.turnstile = turnstile;
        this.accounts = accounts;
        this.imageStorage = imageStorage;
    }

    public List<String> textDeltas(List<Map<String, Object>> messages, String model) {
        Set<String> attempted = new LinkedHashSet<String>();
        String token = accounts.getTextAccessToken(attempted);
        try {
            List<String> result = streamConversation(token, messages, model);
            accounts.markTextUsed(token);
            return result;
        } catch (RuntimeException exception) {
            if (!token.isEmpty() && isInvalidToken(exception.getMessage())) {
                attempted.add(token);
                String next = accounts.getTextAccessToken(attempted);
                if (!next.isEmpty()) {
                    List<String> result = streamConversation(next, messages, model);
                    accounts.markTextUsed(next);
                    return result;
                }
            }
            throw exception;
        }
    }

    public Map<String, Object> images(String prompt, String model, int count, String size, String quality,
                                      String responseFormat, String baseUrl, List<ImageInput> sourceImages) {
        List<Map<String, Object>> data = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < count; index++) {
            String token = accounts.acquireImageToken(new LinkedHashSet<String>());
            try {
                byte[] payload = generateImage(token, promptWithOptions(prompt, size, quality), model, sourceImages);
                Map<String, Object> stored = imageStorage.save(payload, baseUrl);
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                if ("b64_json".equals(responseFormat)) {
                    item.put("b64_json", Base64.getEncoder().encodeToString(payload));
                }
                item.put("url", stored.get("url"));
                item.put("revised_prompt", prompt);
                data.add(item);
                accounts.markImageResult(token, true);
            } catch (RuntimeException exception) {
                accounts.markImageResult(token, false);
                throw exception;
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("created", Instant.now().getEpochSecond());
        result.put("data", data);
        return result;
    }

    public Map<String, Object> listRemoteModels() {
        String token = accounts.getTextAccessToken(new LinkedHashSet<String>());
        UpstreamHttpClient.Session session = http.session();
        bootstrap(session, token);
        String path = token.isEmpty() ? "/backend-anon/models?iim=false&is_gizmo=false"
                : "/backend-api/models?history_and_training_disabled=false";
        UpstreamHttpClient.Response response = session.get(BASE_URL + path, headers(token, token.isEmpty() ? "/backend-anon/models" : "/backend-api/models"), 30);
        response.requireSuccess("models");
        List<Map<String, Object>> data = new ArrayList<Map<String, Object>>();
        Object models = response.jsonObject().get("models");
        if (models instanceof List) {
            for (Object value : (List<?>) models) {
                Map<String, Object> source = mapValue(value);
                String slug = AppConfigService.clean(source.get("slug"));
                if (!slug.isEmpty()) {
                    data.add(modelItem(slug, source.get("created"), source.get("owned_by")));
                }
            }
        }
        addImageModels(data);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("object", "list");
        result.put("data", data);
        return result;
    }

    private List<String> streamConversation(String token, List<Map<String, Object>> messages, String model) {
        UpstreamHttpClient.Session session = http.session();
        PowService.Resources resources = bootstrap(session, token);
        Requirements requirements = requirements(session, token, resources);
        String path = token.isEmpty() ? "/backend-anon/conversation" : "/backend-api/conversation";
        Map<String, String> requestHeaders = headers(token, path);
        requestHeaders.put("Accept", "text/event-stream");
        requestHeaders.put("OpenAI-Sentinel-Chat-Requirements-Token", requirements.token);
        if (!requirements.proofToken.isEmpty()) {
            requestHeaders.put("OpenAI-Sentinel-Proof-Token", requirements.proofToken);
        }
        if (!requirements.turnstileToken.isEmpty()) {
            requestHeaders.put("OpenAI-Sentinel-Turnstile-Token", requirements.turnstileToken);
        }
        Map<String, Object> payload = conversationPayload(messages, model, token.isEmpty() ? "America/Los_Angeles" : "Asia/Shanghai");
        UpstreamHttpClient.Response response = session.postJson(BASE_URL + path, requestHeaders, payload, 300);
        response.requireSuccess("conversation");
        return textDeltasFromSse(response.text());
    }

    private byte[] generateImage(String token, String prompt, String model, List<ImageInput> images) {
        UpstreamHttpClient.Session session = http.session();
        List<Map<String, Object>> references = new ArrayList<Map<String, Object>>();
        for (ImageInput image : images) {
            references.add(uploadImage(session, token, image));
        }
        PowService.Resources resources = bootstrap(session, token);
        Requirements requirements = requirements(session, token, resources);
        String conduit = prepareImage(session, token, requirements, prompt, model);
        String eventPayload = startImage(session, token, requirements, conduit, prompt, model, references);
        String conversationId = match(eventPayload, Pattern.compile("\"conversation_id\"\\s*:\\s*\"([^\"]+)\""));
        Set<String> fileIds = matches(eventPayload, FILE_ID, false);
        Set<String> sedimentIds = matches(eventPayload, SEDIMENT_ID, true);
        if (fileIds.isEmpty() && sedimentIds.isEmpty() && !conversationId.isEmpty()) {
            pollImageResult(session, token, conversationId, fileIds, sedimentIds);
        }
        String downloadUrl = resolveDownloadUrl(session, token, conversationId, fileIds, sedimentIds);
        if (downloadUrl.isEmpty()) {
            throw new IllegalStateException("image generation completed without a downloadable result");
        }
        UpstreamHttpClient.Response image = session.get(downloadUrl, new LinkedHashMap<String, String>(), 120);
        image.requireSuccess("image_download");
        return image.getBody();
    }

    private PowService.Resources bootstrap(UpstreamHttpClient.Session session, String token) {
        Map<String, String> requestHeaders = headers(token, "/");
        requestHeaders.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        UpstreamHttpClient.Response response = session.get(BASE_URL + "/", requestHeaders, 30);
        response.requireSuccess("bootstrap");
        return pow.parseResources(response.text());
    }

    private Requirements requirements(UpstreamHttpClient.Session session, String token, PowService.Resources resources) {
        String path = token.isEmpty() ? "/backend-anon/sentinel/chat-requirements" : "/backend-api/sentinel/chat-requirements";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("p", pow.legacyToken(USER_AGENT, resources));
        UpstreamHttpClient.Response response = session.postJson(BASE_URL + path, headers(token, path), body, 30);
        response.requireSuccess("chat_requirements");
        Map<String, Object> json = response.jsonObject();
        if (AppConfigService.booleanValue(mapValue(json.get("arkose")).get("required"), false)) {
            throw new IllegalStateException("chat requirements requires an arkose token");
        }
        Map<String, Object> turnstileInfo = mapValue(json.get("turnstile"));
        String turnstileToken = "";
        if (AppConfigService.booleanValue(turnstileInfo.get("required"), false)) {
            turnstileToken = turnstile.solve(AppConfigService.clean(turnstileInfo.get("dx")), token.isEmpty() ? AppConfigService.clean(body.get("p")) : "");
            if (turnstileToken.isEmpty()) {
                throw new IllegalStateException("unable to solve required turnstile token");
            }
        }
        String proofToken = "";
        Map<String, Object> proof = mapValue(json.get("proofofwork"));
        if (AppConfigService.booleanValue(proof.get("required"), false)) {
            proofToken = pow.proofToken(AppConfigService.clean(proof.get("seed")), AppConfigService.clean(proof.get("difficulty")), USER_AGENT, resources);
        }
        String requirementToken = AppConfigService.clean(json.get("token"));
        if (requirementToken.isEmpty()) {
            throw new IllegalStateException("missing chat requirements token");
        }
        return new Requirements(requirementToken, proofToken, turnstileToken);
    }

    private Map<String, Object> conversationPayload(List<Map<String, Object>> messages, String model, String timezone) {
        List<Map<String, Object>> upstream = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> message : messages) {
            Map<String, Object> content = map("content_type", "text", "parts", list(AppConfigService.clean(message.get("content"))));
            upstream.add(map("id", uuid(), "author", map("role", AppConfigService.clean(message.get("role"))), "content", content));
        }
        return map("action", "next", "messages", upstream, "model", model, "parent_message_id", uuid(),
                "conversation_mode", map("kind", "primary_assistant"), "force_use_sse", true,
                "history_and_training_disabled", true, "timezone", timezone, "timezone_offset_min", -480,
                "websocket_request_id", uuid());
    }

    private List<String> textDeltasFromSse(String sse) {
        List<String> result = new ArrayList<String>();
        String current = "";
        for (String line : sse.split("\\r?\\n")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            try {
                Map<String, Object> event = mapper.readValue(payload, new TypeReference<LinkedHashMap<String, Object>>() { });
                String next = assistantText(event, current);
                if (!next.equals(current)) {
                    result.add(next.startsWith(current) ? next.substring(current.length()) : next);
                    current = next;
                }
            } catch (IOException ignored) {
            }
        }
        return result;
    }

    private String assistantText(Map<String, Object> event, String current) {
        Map<String, Object> source = event;
        if (event.get("v") instanceof Map) {
            source = mapValue(event.get("v"));
        }
        Map<String, Object> message = mapValue(source.get("message"));
        if ("assistant".equals(AppConfigService.clean(mapValue(message.get("author")).get("role")))) {
            Object parts = mapValue(message.get("content")).get("parts");
            if (parts instanceof List) {
                StringBuilder result = new StringBuilder();
                for (Object part : (List<?>) parts) {
                    if (part instanceof String) {
                        result.append(part);
                    }
                }
                return result.toString();
            }
        }
        if ("/message/content/parts/0".equals(event.get("p"))) {
            String value = AppConfigService.clean(event.get("v"));
            if ("append".equals(event.get("o"))) {
                return current + value;
            }
            if ("replace".equals(event.get("o"))) {
                return value;
            }
        }
        return current;
    }

    private Map<String, Object> uploadImage(UpstreamHttpClient.Session session, String token, ImageInput input) {
        int width = 0;
        int height = 0;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(input.bytes));
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException ignored) {
        }
        String path = "/backend-api/files";
        Map<String, Object> body = map("file_name", input.filename, "file_size", input.bytes.length,
                "use_case", "multimodal", "width", width, "height", height);
        UpstreamHttpClient.Response create = session.postJson(BASE_URL + path, headers(token, path), body, 60);
        create.requireSuccess("image_file_create");
        Map<String, Object> metadata = create.jsonObject();
        Map<String, String> putHeaders = mapString("Content-Type", input.mimeType, "x-ms-blob-type", "BlockBlob", "x-ms-version", "2020-04-08");
        UpstreamHttpClient.Response upload = session.putBytes(AppConfigService.clean(metadata.get("upload_url")), putHeaders, input.bytes, 120);
        upload.requireSuccess("image_upload");
        String fileId = AppConfigService.clean(metadata.get("file_id"));
        UpstreamHttpClient.Response complete = session.postJson(BASE_URL + "/backend-api/files/" + fileId + "/uploaded",
                headers(token, "/backend-api/files/" + fileId + "/uploaded"), new LinkedHashMap<String, Object>(), 60);
        complete.requireSuccess("image_uploaded");
        return map("file_id", fileId, "file_name", input.filename, "file_size", input.bytes.length,
                "mime_type", input.mimeType, "width", width, "height", height);
    }

    private String prepareImage(UpstreamHttpClient.Session session, String token, Requirements requirement, String prompt, String model) {
        String path = "/backend-api/f/conversation/prepare";
        Map<String, String> requestHeaders = imageHeaders(token, path, requirement, "");
        Map<String, Object> payload = map("action", "next", "parent_message_id", uuid(), "model", imageModel(model),
                "client_prepare_state", "success", "timezone_offset_min", -480, "timezone", "Asia/Shanghai",
                "conversation_mode", map("kind", "primary_assistant"), "system_hints", list("picture_v2"),
                "partial_query", map("id", uuid(), "author", map("role", "user"), "content", map("content_type", "text", "parts", list(prompt))),
                "supports_buffering", true, "supported_encodings", list("v1"));
        UpstreamHttpClient.Response response = session.postJson(BASE_URL + path, requestHeaders, payload, 60);
        response.requireSuccess("image_prepare");
        return AppConfigService.clean(response.jsonObject().get("conduit_token"));
    }

    private String startImage(UpstreamHttpClient.Session session, String token, Requirements requirements, String conduit,
                              String prompt, String model, List<Map<String, Object>> references) {
        List<Object> parts = new ArrayList<Object>();
        List<Map<String, Object>> attachments = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : references) {
            parts.add(map("content_type", "image_asset_pointer", "asset_pointer", "file-service://" + item.get("file_id"),
                    "width", item.get("width"), "height", item.get("height"), "size_bytes", item.get("file_size")));
            attachments.add(map("id", item.get("file_id"), "mimeType", item.get("mime_type"), "name", item.get("file_name"),
                    "size", item.get("file_size"), "width", item.get("width"), "height", item.get("height")));
        }
        parts.add(prompt);
        Map<String, Object> content = references.isEmpty() ? map("content_type", "text", "parts", list(prompt))
                : map("content_type", "multimodal_text", "parts", parts);
        Map<String, Object> metadata = map("system_hints", list("picture_v2"), "attachments", attachments);
        Map<String, Object> payload = map("action", "next", "messages", list(map("id", uuid(), "author", map("role", "user"),
                        "create_time", System.currentTimeMillis() / 1000.0d, "content", content, "metadata", metadata)),
                "parent_message_id", uuid(), "model", imageModel(model), "client_prepare_state", "sent",
                "timezone_offset_min", -480, "timezone", "Asia/Shanghai", "conversation_mode", map("kind", "primary_assistant"),
                "system_hints", list("picture_v2"), "supports_buffering", true, "supported_encodings", list("v1"));
        String path = "/backend-api/f/conversation";
        Map<String, String> requestHeaders = imageHeaders(token, path, requirements, conduit);
        requestHeaders.put("Accept", "text/event-stream");
        UpstreamHttpClient.Response response = session.postJson(BASE_URL + path, requestHeaders, payload, 300);
        response.requireSuccess("image_conversation");
        return response.text();
    }

    private void pollImageResult(UpstreamHttpClient.Session session, String token, String conversationId,
                                 Set<String> fileIds, Set<String> sedimentIds) {
        long end = System.currentTimeMillis() + 120000L;
        while (System.currentTimeMillis() < end) {
            String path = "/backend-api/conversation/" + conversationId;
            UpstreamHttpClient.Response response = session.get(BASE_URL + path, headers(token, path), 60);
            response.requireSuccess("image_poll");
            fileIds.addAll(matches(response.text(), FILE_ID, false));
            sedimentIds.addAll(matches(response.text(), SEDIMENT_ID, true));
            if (!fileIds.isEmpty() || !sedimentIds.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("image polling interrupted", exception);
            }
        }
        throw new IllegalStateException("image generation timeout");
    }

    private String resolveDownloadUrl(UpstreamHttpClient.Session session, String token, String conversationId,
                                      Set<String> fileIds, Set<String> sedimentIds) {
        for (String fileId : fileIds) {
            String path = "/backend-api/files/" + fileId + "/download";
            UpstreamHttpClient.Response response = session.get(BASE_URL + path, headers(token, path), 60);
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                String url = first(response.jsonObject().get("download_url"), response.jsonObject().get("url"));
                if (!url.isEmpty()) {
                    return url;
                }
            }
        }
        for (String id : sedimentIds) {
            String path = "/backend-api/conversation/" + conversationId + "/attachment/" + id + "/download";
            UpstreamHttpClient.Response response = session.get(BASE_URL + path, headers(token, path), 60);
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                String url = first(response.jsonObject().get("download_url"), response.jsonObject().get("url"));
                if (!url.isEmpty()) {
                    return url;
                }
            }
        }
        return "";
    }

    private Map<String, String> headers(String token, String path) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put("User-Agent", USER_AGENT);
        result.put("Origin", BASE_URL);
        result.put("Referer", BASE_URL + "/");
        result.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-US;q=0.7");
        result.put("OAI-Device-Id", deviceId);
        result.put("OAI-Session-Id", sessionId);
        result.put("OAI-Language", "zh-CN");
        result.put("X-OpenAI-Target-Path", path);
        result.put("X-OpenAI-Target-Route", path);
        if (!token.isEmpty()) {
            result.put("Authorization", "Bearer " + token);
        }
        return result;
    }

    private Map<String, String> imageHeaders(String token, String path, Requirements requirements, String conduit) {
        Map<String, String> result = headers(token, path);
        result.put("OpenAI-Sentinel-Chat-Requirements-Token", requirements.token);
        if (!requirements.proofToken.isEmpty()) {
            result.put("OpenAI-Sentinel-Proof-Token", requirements.proofToken);
        }
        if (!requirements.turnstileToken.isEmpty()) {
            result.put("OpenAI-Sentinel-Turnstile-Token", requirements.turnstileToken);
        }
        if (!conduit.isEmpty()) {
            result.put("X-Conduit-Token", conduit);
        }
        return result;
    }

    private void addImageModels(List<Map<String, Object>> data) {
        Set<String> known = new LinkedHashSet<String>();
        for (Map<String, Object> item : data) {
            known.add(AppConfigService.clean(item.get("id")));
        }
        for (String model : new String[]{"codex-gpt-image-2", "gpt-image-2"}) {
            if (!known.contains(model)) {
                data.add(modelItem(model, 0, "chatgpt2api"));
            }
        }
    }

    private Map<String, Object> modelItem(String id, Object created, Object owner) {
        return map("id", id, "object", "model", "created", created == null ? 0 : created,
                "owned_by", AppConfigService.clean(owner).isEmpty() ? "chatgpt" : owner,
                "permission", new ArrayList<Object>(), "root", id, "parent", null);
    }

    private String promptWithOptions(String prompt, String size, String quality) {
        StringBuilder result = new StringBuilder(prompt == null ? "" : prompt.trim());
        if (size != null && !size.trim().isEmpty()) {
            result.append("\n\nOutput image size: ").append(size.trim()).append(".");
        }
        if (quality != null && !quality.trim().isEmpty()) {
            result.append(" Output image quality: ").append(quality.trim()).append(".");
        }
        return result.toString();
    }

    private String imageModel(String model) {
        return "gpt-image-2".equals(model) ? "gpt-5-3" : model;
    }

    private String match(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private Set<String> matches(String value, Pattern pattern, boolean group) {
        Set<String> result = new LinkedHashSet<String>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            result.add(group ? matcher.group(1) : matcher.group());
        }
        return result;
    }

    private boolean isInvalidToken(String message) {
        String value = message == null ? "" : message.toLowerCase();
        return value.contains("token_invalidated") || value.contains("token_revoked") || value.contains("invalidated oauth token");
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }

    private String first(Object... values) {
        for (Object value : values) {
            String text = AppConfigService.clean(value);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private List<Object> list(Object... values) {
        List<Object> result = new ArrayList<Object>();
        for (Object value : values) {
            result.add(value);
        }
        return result;
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private Map<String, String> mapString(String... values) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }

    private static final class Requirements {
        private final String token;
        private final String proofToken;
        private final String turnstileToken;

        private Requirements(String token, String proofToken, String turnstileToken) {
            this.token = token;
            this.proofToken = proofToken;
            this.turnstileToken = turnstileToken;
        }
    }

    public static final class ImageInput {
        private final byte[] bytes;
        private final String filename;
        private final String mimeType;

        public ImageInput(byte[] bytes, String filename, String mimeType) {
            this.bytes = bytes;
            this.filename = filename;
            this.mimeType = mimeType;
        }
    }
}
