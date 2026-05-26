package com.chatgpt2api.auth;

import com.chatgpt2api.common.ApiException;
import com.chatgpt2api.config.AppConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthGuard {
    private final AppConfigService config;
    private final AuthService authService;

    public AuthGuard(AppConfigService config, AuthService authService) {
        this.config = config;
        this.authService = authService;
    }

    public Map<String, Object> requireIdentity(String authorization) {
        String token = extractBearerToken(authorization);
        Map<String, Object> identity = legacyAdmin(token);
        if (identity == null) {
            identity = authService.authenticate(token);
        }
        if (identity == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "密钥无效或已失效，请重新登录");
        }
        return identity;
    }

    public Map<String, Object> requireAdmin(String authorization) {
        Map<String, Object> identity = requireIdentity(authorization);
        if (!"admin".equals(identity.get("role"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "需要管理员权限才能执行这个操作");
        }
        return identity;
    }

    public String extractBearerToken(String authorization) {
        String value = AppConfigService.clean(authorization);
        if (!value.toLowerCase().startsWith("bearer ")) {
            return "";
        }
        return value.substring(7).trim();
    }

    private Map<String, Object> legacyAdmin(String token) {
        if (token.isEmpty() || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                config.getAuthKey().getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        Map<String, Object> identity = new LinkedHashMap<String, Object>();
        identity.put("id", "admin");
        identity.put("name", "管理员");
        identity.put("role", "admin");
        return identity;
    }
}
