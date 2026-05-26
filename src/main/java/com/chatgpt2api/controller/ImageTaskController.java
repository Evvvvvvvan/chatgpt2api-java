package com.chatgpt2api.controller;

import com.chatgpt2api.auth.AuthGuard;
import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.filter.ContentFilterService;
import com.chatgpt2api.image.ImageInputService;
import com.chatgpt2api.image.ImageTaskService;
import com.chatgpt2api.protocol.ChatgptWebService;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ImageTaskController {
    private final AuthGuard authGuard;
    private final ImageTaskService tasks;
    private final ImageInputService inputs;
    private final ContentFilterService filter;
    private final AppConfigService config;

    public ImageTaskController(AuthGuard authGuard, ImageTaskService tasks, ImageInputService inputs,
                               ContentFilterService filter, AppConfigService config) {
        this.authGuard = authGuard;
        this.tasks = tasks;
        this.inputs = inputs;
        this.filter = filter;
        this.config = config;
    }

    @GetMapping("/api/image-tasks")
    public Map<String, Object> list(@RequestParam(value = "ids", defaultValue = "") String ids,
                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        return tasks.list(authGuard.requireIdentity(authorization), ids);
    }

    @PostMapping("/api/image-tasks/generations")
    public Map<String, Object> generate(@RequestBody Map<String, Object> body, HttpServletRequest request,
                                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> identity = authGuard.requireIdentity(authorization);
        filter.check(AppConfigService.clean(body.get("prompt")));
        return tasks.submitGeneration(identity, body, baseUrl(request));
    }

    @PostMapping(value = "/api/image-tasks/edits", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> editJson(@RequestBody Map<String, Object> body, HttpServletRequest request,
                                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> identity = authGuard.requireIdentity(authorization);
        filter.check(AppConfigService.clean(body.get("prompt")));
        return tasks.submitEdit(identity, body, baseUrl(request), inputs.jsonInputs(body));
    }

    @PostMapping(value = "/api/image-tasks/edits", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> editMultipart(@RequestParam MultiValueMap<String, String> fields,
                                             @RequestPart(value = "image", required = false) List<MultipartFile> files,
                                             @RequestPart(value = "image[]", required = false) List<MultipartFile> arrayFiles,
                                             HttpServletRequest request,
                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> identity = authGuard.requireIdentity(authorization);
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
        List<ChatgptWebService.ImageInput> images = inputs.multipartInputs(all, fields);
        return tasks.submitEdit(identity, body, baseUrl(request), images);
    }

    private String baseUrl(HttpServletRequest request) {
        String configured = config.getBaseUrl();
        return configured.isEmpty() ? request.getScheme() + "://" + request.getHeader("Host") : configured;
    }
}
