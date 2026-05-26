package com.chatgpt2api.filter;

import com.chatgpt2api.common.ApiException;
import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.http.RemoteJsonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ContentFilterService {
    private static final Pattern DATA_URI = Pattern.compile("data:[\\w/.+;-]+;base64,[A-Za-z0-9+/=]+");
    private static final int MAX_TEXT_LENGTH = 100000;
    private final AppConfigService config;
    private final RemoteJsonClient client;

    public ContentFilterService(AppConfigService config, RemoteJsonClient client) {
        this.config = config;
        this.client = client;
    }

    public void check(String text) {
        String value = text == null ? "" : text;
        if (value.trim().isEmpty()) {
            return;
        }
        for (String word : config.getSensitiveWords()) {
            if (value.contains(word)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "检测到敏感词，拒绝本次任务");
            }
        }
        Object reviewObject = config.getPublicSettings().get("ai_review");
        if (!(reviewObject instanceof Map)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> review = (Map<String, Object>) reviewObject;
        if (!AppConfigService.booleanValue(review.get("enabled"), false)) {
            return;
        }
        String baseUrl = trimSlash(AppConfigService.clean(review.get("base_url")));
        String key = AppConfigService.clean(review.get("api_key"));
        String model = AppConfigService.clean(review.get("model"));
        if (baseUrl.isEmpty() || key.isEmpty() || model.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ai review config is incomplete");
        }
        boolean failOpen = AppConfigService.booleanValue(review.get("fail_open"), true);
        String sanitized = DATA_URI.matcher(value).replaceAll("[image]");
        if (sanitized.length() > MAX_TEXT_LENGTH) {
            int half = (MAX_TEXT_LENGTH - 17) / 2;
            sanitized = sanitized.substring(0, half) + "\n...[truncated]...\n" + sanitized.substring(sanitized.length() - half);
        }
        String prompt = AppConfigService.clean(review.get("prompt"));
        if (prompt.isEmpty()) {
            prompt = "判断用户请求是否允许。只回答 ALLOW 或 REJECT。";
        }
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", prompt + "\n\n用户请求:\n" + sanitized + "\n\n只回答 ALLOW 或 REJECT。");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", model);
        payload.put("messages", Arrays.asList(message));
        payload.put("temperature", 0);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + key);
        try {
            Map<String, Object> response = client.post(baseUrl + "/v1/chat/completions", headers, payload);
            String decision = decision(response).toLowerCase();
            if (starts(decision, "allow", "pass", "true", "yes", "通过", "允许", "安全")) {
                return;
            }
            if (starts(decision, "reject", "deny", "block", "false", "no", "拒绝", "不允许", "违规", "禁止")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "AI 审核未通过，拒绝本次任务");
            }
            if (!failOpen) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 审核服务暂时不可用，请稍后重试");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (!failOpen) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 审核服务暂时不可用，请稍后重试");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String decision(Map<String, Object> response) {
        Object choices = response.get("choices");
        if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) {
            return "";
        }
        Object first = ((List<?>) choices).get(0);
        if (!(first instanceof Map) || !(((Map<?, ?>) first).get("message") instanceof Map)) {
            return "";
        }
        return AppConfigService.clean(((Map<String, Object>) ((Map<?, ?>) first).get("message")).get("content"));
    }

    private boolean starts(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String trimSlash(String value) {
        return value.replaceAll("/+$", "");
    }
}
