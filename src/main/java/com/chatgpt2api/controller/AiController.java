package com.chatgpt2api.controller;

import com.chatgpt2api.auth.AuthGuard;
import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.filter.ContentFilterService;
import com.chatgpt2api.image.ImageInputService;
import com.chatgpt2api.protocol.AiProtocolService;
import com.chatgpt2api.protocol.AiProtocolService.ProtocolOutput;
import com.chatgpt2api.protocol.ChatgptWebService;
import com.chatgpt2api.protocol.ModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class AiController {
    private final AuthGuard authGuard;
    private final ModelService modelService;
    private final AiProtocolService protocols;
    private final ImageInputService imageInputs;
    private final ContentFilterService filter;
    private final ObjectMapper mapper;
    private final AppConfigService config;

    public AiController(AuthGuard authGuard, ModelService modelService, AiProtocolService protocols,
                        ImageInputService imageInputs, ContentFilterService filter, ObjectMapper mapper, AppConfigService config) {
        this.authGuard = authGuard;
        this.modelService = modelService;
        this.protocols = protocols;
        this.imageInputs = imageInputs;
        this.filter = filter;
        this.mapper = mapper;
        this.config = config;
    }

    @GetMapping("/v1/models")
    public Map<String, Object> listModels(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireIdentity(authorization);
        return modelService.listModels();
    }

    @PostMapping("/v1/images/generations")
    public Object generateImages(@RequestBody Map<String, Object> body, HttpServletRequest request,
                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireIdentity(authorization);
        filter.check(AppConfigService.clean(body.get("prompt")));
        Map<String, Object> result = protocols.generateImages(body, baseUrl(request));
        return streamOrBody(body, result);
    }

    @PostMapping(value = "/v1/images/edits", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object editImagesJson(@RequestBody Map<String, Object> body, HttpServletRequest request,
                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireIdentity(authorization);
        filter.check(AppConfigService.clean(body.get("prompt")));
        Map<String, Object> result = protocols.editImages(body, baseUrl(request), imageInputs.jsonInputs(body));
        return streamOrBody(body, result);
    }

    @PostMapping(value = "/v1/images/edits", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object editImagesMultipart(@RequestParam MultiValueMap<String, String> fields,
                                      @RequestPart(value = "image", required = false) List<MultipartFile> files,
                                      @RequestPart(value = "image[]", required = false) List<MultipartFile> arrayFiles,
                                      HttpServletRequest request,
                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireIdentity(authorization);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            if (!field.getValue().isEmpty()) {
                body.put(field.getKey(), field.getValue().get(0));
            }
        }
        List<MultipartFile> all = new ArrayList<MultipartFile>();
        if (files != null) {
            all.addAll(files);
        }
        if (arrayFiles != null) {
            all.addAll(arrayFiles);
        }
        filter.check(AppConfigService.clean(body.get("prompt")));
        return streamOrBody(body, protocols.editImages(body, baseUrl(request), imageInputs.multipartInputs(all, fields)));
    }

    @PostMapping("/v1/chat/completions")
    public Object chat(@RequestBody Map<String, Object> body, HttpServletRequest request,
                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireIdentity(authorization);
        filter.check(mapperText(body));
        return output(protocols.chat(body, baseUrl(request)));
    }

    @PostMapping("/v1/responses")
    public Object responses(@RequestBody Map<String, Object> body, HttpServletRequest request,
                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireIdentity(authorization);
        filter.check(mapperText(body));
        return output(protocols.responses(body, baseUrl(request)));
    }

    @PostMapping("/v1/messages")
    public Object messages(@RequestBody Map<String, Object> body,
                           @RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestHeader(value = "x-api-key", required = false) String apiKey) {
        authGuard.requireIdentity(authorization == null || authorization.trim().isEmpty() ? "Bearer " + apiKey : authorization);
        filter.check(mapperText(body));
        return output(protocols.messages(body));
    }

    private Object streamOrBody(Map<String, Object> request, Map<String, Object> body) {
        if (!AppConfigService.booleanValue(request.get("stream"), false)) {
            return body;
        }
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("object", "image.generation.result");
        event.put("created", body.get("created"));
        event.put("model", request.get("model"));
        event.put("index", 1);
        event.put("total", request.get("n") == null ? 1 : request.get("n"));
        event.put("data", body.get("data"));
        events.add(event);
        return sse(events, false);
    }

    private Object output(ProtocolOutput result) {
        return result.isStream() ? sse(result.getEvents(), result.isAnthropic()) : result.getBody();
    }

    private ResponseEntity<StreamingResponseBody> sse(List<Map<String, Object>> events, boolean anthropic) {
        StreamingResponseBody stream = output -> {
            if (!anthropic) {
                output.write(": stream-open\n\n".getBytes(StandardCharsets.UTF_8));
            }
            for (Map<String, Object> event : events) {
                if (anthropic) {
                    output.write(("event: " + event.get("type") + "\n").getBytes(StandardCharsets.UTF_8));
                }
                output.write(("data: " + mapper.writeValueAsString(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            if (!anthropic) {
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream);
    }

    private String mapperText(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception exception) {
            return String.valueOf(body);
        }
    }

    private String baseUrl(HttpServletRequest request) {
        String configured = config.getBaseUrl();
        return configured.isEmpty() ? request.getScheme() + "://" + request.getHeader("Host") : configured;
    }
}
