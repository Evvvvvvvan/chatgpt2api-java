package com.chatgpt2api.protocol;

import com.chatgpt2api.common.ApiException;
import com.chatgpt2api.config.AppConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiProtocolService {
    private static final Pattern TOOL_CALL = Pattern.compile("(?is)<tool_call\\b[^>]*>(.*?)</tool_call>");
    private final ChatgptWebService backend;
    private final ObjectMapper mapper;

    public AiProtocolService(ChatgptWebService backend, ObjectMapper mapper) {
        this.backend = backend;
        this.mapper = mapper;
    }

    public Map<String, Object> generateImages(Map<String, Object> body, String baseUrl) {
        return backend.images(required(body, "prompt"), value(body.get("model"), "gpt-image-2"),
                imageCount(body.get("n")), AppConfigService.clean(body.get("size")), value(body.get("quality"), "auto"),
                value(body.get("response_format"), "b64_json"), baseUrl, new ArrayList<ChatgptWebService.ImageInput>());
    }

    public Map<String, Object> editImages(Map<String, Object> body, String baseUrl, List<ChatgptWebService.ImageInput> images) {
        return backend.images(required(body, "prompt"), value(body.get("model"), "gpt-image-2"),
                imageCount(body.get("n")), AppConfigService.clean(body.get("size")), value(body.get("quality"), "auto"),
                value(body.get("response_format"), "b64_json"), baseUrl, images);
    }

    public ProtocolOutput chat(Map<String, Object> body, String baseUrl) {
        String model = value(body.get("model"), "auto");
        if (isImageRequest(body, model)) {
            String prompt = prompt(body);
            Map<String, Object> image = backend.images(prompt, model, imageCount(body.get("n")),
                    AppConfigService.clean(body.get("size")), value(body.get("quality"), "auto"),
                    "b64_json", baseUrl, embeddedImages(body.get("messages")));
            StringBuilder content = new StringBuilder();
            for (Object item : listValue(image.get("data"))) {
                String encoded = AppConfigService.clean(mapValue(item).get("b64_json"));
                if (!encoded.isEmpty()) {
                    if (content.length() > 0) {
                        content.append("\n\n");
                    }
                    content.append("![image](data:image/png;base64,").append(encoded).append(")");
                }
            }
            return chatOutput(model, content.toString(), booleanValue(body.get("stream")));
        }
        List<Map<String, Object>> messages = normalizedMessages(body);
        List<String> deltas = backend.textDeltas(messages, model);
        StringBuilder text = new StringBuilder();
        for (String delta : deltas) {
            text.append(delta);
        }
        return chatOutput(model, text.toString(), booleanValue(body.get("stream")), deltas, messages);
    }

    public ProtocolOutput responses(Map<String, Object> body, String baseUrl) {
        String model = value(body.get("model"), "auto");
        String responseId = "resp_" + id();
        long created = Instant.now().getEpochSecond();
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        events.add(map("type", "response.created", "response", responseEnvelope(responseId, model, created, "in_progress", new ArrayList<Object>())));
        List<Map<String, Object>> output = new ArrayList<Map<String, Object>>();
        if (hasImageTool(body)) {
            Map<String, Object> image = backend.images(responsePrompt(body), model, 1, "", "auto", "b64_json",
                    baseUrl, embeddedImages(body.get("input")));
            for (Object value : listValue(image.get("data"))) {
                String encoded = AppConfigService.clean(mapValue(value).get("b64_json"));
                if (!encoded.isEmpty()) {
                    Map<String, Object> item = map("id", "ig_" + id(), "type", "image_generation_call",
                            "status", "completed", "result", encoded, "revised_prompt", responsePrompt(body));
                    output.add(item);
                    events.add(map("type", "response.output_item.done", "output_index", 0, "item", item));
                }
            }
        } else {
            List<Map<String, Object>> messages = responseMessages(body);
            List<String> deltas = backend.textDeltas(messages, model);
            String itemId = "msg_" + id();
            StringBuilder text = new StringBuilder();
            events.add(map("type", "response.output_item.added", "output_index", 0, "item", textOutput(itemId, "", "in_progress")));
            for (String delta : deltas) {
                text.append(delta);
                events.add(map("type", "response.output_text.delta", "item_id", itemId, "output_index", 0, "content_index", 0, "delta", delta));
            }
            events.add(map("type", "response.output_text.done", "item_id", itemId, "output_index", 0, "content_index", 0, "text", text.toString()));
            Map<String, Object> item = textOutput(itemId, text.toString(), "completed");
            output.add(item);
            events.add(map("type", "response.output_item.done", "output_index", 0, "item", item));
        }
        Map<String, Object> completed = responseEnvelope(responseId, model, created, "completed", output);
        events.add(map("type", "response.completed", "response", completed));
        return new ProtocolOutput(completed, events, booleanValue(body.get("stream")), false);
    }

    public ProtocolOutput messages(Map<String, Object> body) {
        String model = value(body.get("model"), "auto");
        List<Map<String, Object>> messages = normalizedMessages(body);
        String system = text(body.get("system"));
        if (!system.isEmpty()) {
            messages.add(0, map("role", "system", "content", system + toolPrompt(body.get("tools"))));
        }
        List<String> deltas = backend.textDeltas(messages, model);
        StringBuilder text = new StringBuilder();
        for (String delta : deltas) {
            text.append(delta);
        }
        List<Map<String, Object>> content = anthropicContent(text.toString(), body.get("tools"));
        String stopReason = content.size() > 0 && "tool_use".equals(content.get(content.size() - 1).get("type")) ? "tool_use" : "end_turn";
        Map<String, Object> response = map("id", "msg_" + UUID.randomUUID().toString(), "type", "message",
                "role", "assistant", "model", model, "content", content, "stop_reason", stopReason,
                "stop_sequence", null, "usage", map("input_tokens", estimate(messages.toString()), "output_tokens", estimate(text.toString())));
        List<Map<String, Object>> events = anthropicEvents(response, deltas, content, stopReason);
        return new ProtocolOutput(response, events, booleanValue(body.get("stream")), true);
    }

    private ProtocolOutput chatOutput(String model, String text, boolean stream) {
        List<String> deltas = new ArrayList<String>();
        deltas.add(text);
        return chatOutput(model, text, stream, deltas, new ArrayList<Map<String, Object>>());
    }

    private ProtocolOutput chatOutput(String model, String text, boolean stream, List<String> deltas, List<Map<String, Object>> messages) {
        long created = Instant.now().getEpochSecond();
        String completionId = "chatcmpl-" + id();
        Map<String, Object> response = map("id", completionId, "object", "chat.completion", "created", created,
                "model", model, "choices", list(map("index", 0, "message", map("role", "assistant", "content", text), "finish_reason", "stop")),
                "usage", map("prompt_tokens", estimate(messages.toString()), "completion_tokens", estimate(text),
                        "total_tokens", estimate(messages.toString()) + estimate(text)));
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        boolean first = true;
        for (String delta : deltas) {
            events.add(map("id", completionId, "object", "chat.completion.chunk", "created", created, "model", model,
                    "choices", list(map("index", 0, "delta", first ? map("role", "assistant", "content", delta) : map("content", delta),
                            "finish_reason", null))));
            first = false;
        }
        if (first) {
            events.add(map("id", completionId, "object", "chat.completion.chunk", "created", created, "model", model,
                    "choices", list(map("index", 0, "delta", map("role", "assistant", "content", ""), "finish_reason", null))));
        }
        events.add(map("id", completionId, "object", "chat.completion.chunk", "created", created, "model", model,
                "choices", list(map("index", 0, "delta", new LinkedHashMap<String, Object>(), "finish_reason", "stop"))));
        return new ProtocolOutput(response, events, stream, false);
    }

    private Map<String, Object> responseEnvelope(String id, String model, long created, String status, Object output) {
        return map("id", id, "object", "response", "created_at", created, "status", status, "error", null,
                "incomplete_details", null, "model", model, "output", output, "parallel_tool_calls", false);
    }

    private Map<String, Object> textOutput(String id, String text, String status) {
        return map("id", id, "type", "message", "status", status, "role", "assistant",
                "content", list(map("type", "output_text", "text", text, "annotations", new ArrayList<Object>())));
    }

    private List<Map<String, Object>> anthropicContent(String text, Object tools) {
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        if (tools instanceof List) {
            Matcher matcher = TOOL_CALL.matcher(text);
            while (matcher.find()) {
                String block = matcher.group(1);
                String name = xml(block, "tool_name");
                String params = xml(block, "parameters");
                Map<String, Object> input;
                try {
                    input = mapper.readValue(params, new TypeReference<LinkedHashMap<String, Object>>() { });
                } catch (Exception ignored) {
                    input = new LinkedHashMap<String, Object>();
                }
                content.add(map("type", "tool_use", "id", "toolu_" + id(), "name", name, "input", input));
            }
        }
        String visible = TOOL_CALL.matcher(text).replaceAll("").trim();
        if (!visible.isEmpty() || content.isEmpty()) {
            content.add(0, map("type", "text", "text", visible));
        }
        return content;
    }

    private List<Map<String, Object>> anthropicEvents(Map<String, Object> response, List<String> deltas,
                                                       List<Map<String, Object>> content, String stopReason) {
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        Map<String, Object> start = new LinkedHashMap<String, Object>(response);
        start.put("content", new ArrayList<Object>());
        start.put("stop_reason", null);
        events.add(map("type", "message_start", "message", start));
        int index = 0;
        for (Map<String, Object> block : content) {
            if ("text".equals(block.get("type"))) {
                events.add(map("type", "content_block_start", "index", index, "content_block", map("type", "text", "text", "")));
                for (String delta : deltas) {
                    events.add(map("type", "content_block_delta", "index", index, "delta", map("type", "text_delta", "text", delta)));
                }
                events.add(map("type", "content_block_stop", "index", index));
            } else {
                events.add(map("type", "content_block_start", "index", index, "content_block",
                        map("type", "tool_use", "id", block.get("id"), "name", block.get("name"), "input", new LinkedHashMap<String, Object>())));
                events.add(map("type", "content_block_delta", "index", index, "delta",
                        map("type", "input_json_delta", "partial_json", json(block.get("input")))));
                events.add(map("type", "content_block_stop", "index", index));
            }
            index++;
        }
        events.add(map("type", "message_delta", "delta", map("stop_reason", stopReason, "stop_sequence", null),
                "usage", map("output_tokens", mapValue(response.get("usage")).get("output_tokens"))));
        events.add(map("type", "message_stop"));
        return events;
    }

    private List<Map<String, Object>> normalizedMessages(Map<String, Object> body) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Object raw = body.get("messages");
        if (raw instanceof List) {
            for (Object value : (List<?>) raw) {
                Map<String, Object> item = mapValue(value);
                if (!item.isEmpty()) {
                    result.add(map("role", value(item.get("role"), "user"), "content", text(item.get("content"))));
                }
            }
        }
        if (result.isEmpty() && !AppConfigService.clean(body.get("prompt")).isEmpty()) {
            result.add(map("role", "user", "content", AppConfigService.clean(body.get("prompt"))));
        }
        if (result.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "messages or prompt is required");
        }
        return result;
    }

    private List<Map<String, Object>> responseMessages(Map<String, Object> body) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        String instructions = AppConfigService.clean(body.get("instructions"));
        if (!instructions.isEmpty()) {
            result.add(map("role", "system", "content", instructions));
        }
        result.add(map("role", "user", "content", responsePrompt(body)));
        return result;
    }

    private String responsePrompt(Map<String, Object> body) {
        String result = text(body.get("input"));
        if (result.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "input text is required");
        }
        return result;
    }

    private String prompt(Map<String, Object> body) {
        String value = AppConfigService.clean(body.get("prompt"));
        if (!value.isEmpty()) {
            return value;
        }
        return required(map("prompt", text(body.get("messages"))), "prompt");
    }

    private boolean hasImageTool(Map<String, Object> body) {
        for (Object tool : listValue(body.get("tools"))) {
            if ("image_generation".equals(mapValue(tool).get("type"))) {
                return true;
            }
        }
        return "image_generation".equals(mapValue(body.get("tool_choice")).get("type"));
    }

    private boolean isImageRequest(Map<String, Object> body, String model) {
        return "gpt-image-2".equals(model) || "codex-gpt-image-2".equals(model)
                || listValue(body.get("modalities")).contains("image");
    }

    private String toolPrompt(Object tools) {
        if (!(tools instanceof List) || ((List<?>) tools).isEmpty()) {
            return "";
        }
        return "\n\nWhen calling tools output XML only: <tool_calls><tool_call><tool_name>NAME</tool_name><parameters>{}</parameters></tool_call></tool_calls>";
    }

    private List<ChatgptWebService.ImageInput> embeddedImages(Object source) {
        List<ChatgptWebService.ImageInput> images = new ArrayList<ChatgptWebService.ImageInput>();
        collectImages(source, images);
        return images;
    }

    private void collectImages(Object source, List<ChatgptWebService.ImageInput> images) {
        if (source instanceof List) {
            for (Object item : (List<?>) source) {
                collectImages(item, images);
            }
            return;
        }
        if (!(source instanceof Map)) {
            return;
        }
        Map<String, Object> item = mapValue(source);
        String type = AppConfigService.clean(item.get("type"));
        Object rawUrl = item.get("image_url");
        if (rawUrl instanceof Map) {
            rawUrl = mapValue(rawUrl).get("url");
        }
        if (("image_url".equals(type) || "input_image".equals(type)) && rawUrl instanceof String) {
            String value = (String) rawUrl;
            if (value.startsWith("data:") && value.contains(",")) {
                String header = value.substring(5, value.indexOf(','));
                String mime = header.contains(";") ? header.substring(0, header.indexOf(';')) : header;
                try {
                    images.add(new ChatgptWebService.ImageInput(Base64.getDecoder().decode(value.substring(value.indexOf(',') + 1)),
                            "image.png", mime.isEmpty() ? "image/png" : mime));
                } catch (IllegalArgumentException exception) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "invalid base64 image data");
                }
            }
        }
        for (Object value : item.values()) {
            collectImages(value, images);
        }
    }

    private String xml(String value, String tag) {
        Matcher matcher = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(value);
        return matcher.find() ? matcher.group(1).replace("<![CDATA[", "").replace("]]>", "").trim() : "";
    }

    private String text(Object value) {
        if (value instanceof String) {
            return ((String) value).trim();
        }
        StringBuilder result = new StringBuilder();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                Map<String, Object> part = mapValue(item);
                String type = AppConfigService.clean(part.get("type"));
                if ("text".equals(type) || "input_text".equals(type) || "output_text".equals(type) || part.containsKey("content")) {
                    String text = part.containsKey("text") ? AppConfigService.clean(part.get("text")) : text(part.get("content"));
                    if (!text.isEmpty()) {
                        if (result.length() > 0) {
                            result.append('\n');
                        }
                        result.append(text);
                    }
                }
            }
        } else if (value instanceof Map) {
            result.append(text(mapValue(value).get("content")));
        }
        return result.toString();
    }

    private String required(Map<String, Object> body, String field) {
        String value = AppConfigService.clean(body.get(field));
        if (value.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }

    private int imageCount(Object raw) {
        int count = AppConfigService.intValue(raw, 1, 1);
        if (count > 4) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "n must be between 1 and 4");
        }
        return count;
    }

    private boolean booleanValue(Object value) {
        return AppConfigService.booleanValue(value, false);
    }

    private String value(Object value, String fallback) {
        String text = AppConfigService.clean(value);
        return text.isEmpty() ? fallback : text;
    }

    private int estimate(String value) {
        return Math.max(0, (value == null ? 0 : value.length() + 3) / 4);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private List<Object> listValue(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
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

    public static final class ProtocolOutput {
        private final Map<String, Object> body;
        private final List<Map<String, Object>> events;
        private final boolean stream;
        private final boolean anthropic;

        private ProtocolOutput(Map<String, Object> body, List<Map<String, Object>> events, boolean stream, boolean anthropic) {
            this.body = body;
            this.events = events;
            this.stream = stream;
            this.anthropic = anthropic;
        }

        public Map<String, Object> getBody() {
            return body;
        }

        public List<Map<String, Object>> getEvents() {
            return events;
        }

        public boolean isStream() {
            return stream;
        }

        public boolean isAnthropic() {
            return anthropic;
        }
    }
}
