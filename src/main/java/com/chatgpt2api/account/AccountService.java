package com.chatgpt2api.account;

import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.http.UpstreamHttpClient;
import com.chatgpt2api.log.LogService;
import com.chatgpt2api.storage.StorageBackend;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class AccountService {
    private final StorageBackend storage;
    private final ObjectMapper mapper;
    private final UpstreamHttpClient upstream;
    private final AppConfigService config;
    private final LogService logService;
    private Map<String, Map<String, Object>> accounts;
    private final Map<String, Integer> imageInflight = new LinkedHashMap<String, Integer>();
    private int accountIndex;

    public AccountService(StorageBackend storage, ObjectMapper mapper) {
        this(storage, mapper, null, null, null);
    }

    @Autowired
    public AccountService(StorageBackend storage, ObjectMapper mapper, UpstreamHttpClient upstream, AppConfigService config, LogService logService) {
        this.storage = storage;
        this.mapper = mapper;
        this.upstream = upstream;
        this.config = config;
        this.logService = logService;
        this.accounts = loadAccounts();
    }

    public synchronized List<Map<String, Object>> listAccounts() {
        return copyItems(accounts.values());
    }

    public synchronized List<String> listTokens() {
        return new ArrayList<String>(accounts.keySet());
    }

    public synchronized String getTextAccessToken(Set<String> excludedTokens) {
        Set<String> excluded = excludedTokens == null ? new LinkedHashSet<String>() : excludedTokens;
        List<String> candidates = new ArrayList<String>();
        for (Map<String, Object> item : accounts.values()) {
            String status = AppConfigService.clean(item.get("status"));
            String token = AppConfigService.clean(item.get("access_token"));
            if (!token.isEmpty() && !isUnavailableStatus(status)
                    && !excluded.contains(token)) {
                candidates.add(token);
            }
        }
        if (candidates.isEmpty()) {
            return "";
        }
        String token = candidates.get(accountIndex++ % candidates.size());
        return refreshAccessToken(token, false, "get_text_access_token");
    }

    public synchronized void markTextUsed(String accessToken) {
        String token = AppConfigService.clean(accessToken);
        if (!token.isEmpty() && accounts.containsKey(token)) {
            updateAccount(token, map("last_used_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        }
    }

    public synchronized String acquireImageToken(Set<String> excludedTokens) {
        Set<String> excluded = excludedTokens == null ? new LinkedHashSet<String>() : excludedTokens;
        int maxConcurrency = config == null ? 1 : config.getImageAccountConcurrency();
        List<String> candidates = new ArrayList<String>();
        for (Map<String, Object> item : accounts.values()) {
            String status = AppConfigService.clean(item.get("status"));
            String token = AppConfigService.clean(item.get("access_token"));
            boolean available = AppConfigService.booleanValue(item.get("image_quota_unknown"), false)
                    || intValue(item.get("quota")) > 0;
            if (!token.isEmpty() && available && !isUnavailableStatus(status)
                    && !excluded.contains(token) && (!imageInflight.containsKey(token) || imageInflight.get(token) < maxConcurrency)) {
                candidates.add(token);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("no available image quota");
        }
        String token = candidates.get(accountIndex++ % candidates.size());
        imageInflight.put(token, imageInflight.containsKey(token) ? imageInflight.get(token) + 1 : 1);
        return refreshAccessToken(token, false, "get_available_access_token");
    }

    public synchronized void releaseImageToken(String accessToken) {
        String token = AppConfigService.clean(accessToken);
        int value = imageInflight.containsKey(token) ? imageInflight.get(token) : 0;
        if (value <= 1) {
            imageInflight.remove(token);
        } else {
            imageInflight.put(token, value - 1);
        }
    }

    public synchronized void markImageResult(String accessToken, boolean success) {
        String token = AppConfigService.clean(accessToken);
        Map<String, Object> current = accounts.get(token);
        if (current == null) {
            releaseImageToken(token);
            return;
        }
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("last_used_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        updates.put(success ? "success" : "fail", intValue(current.get(success ? "success" : "fail")) + 1);
        if (success && !AppConfigService.booleanValue(current.get("image_quota_unknown"), false)) {
            int quota = Math.max(0, intValue(current.get("quota")) - 1);
            updates.put("quota", quota);
            if (quota == 0) {
                updates.put("status", "limited");
            }
        }
        updateAccount(token, updates);
        releaseImageToken(token);
    }

    public synchronized Map<String, Object> addAccounts(List<String> tokens) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (String token : tokens) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("access_token", token);
            items.add(item);
        }
        return addAccountItems(items);
    }

    public synchronized Map<String, Object> addAccountItems(List<Map<String, Object>> items) {
        int added = 0;
        int skipped = 0;
        for (Map<String, Object> source : items) {
            Map<String, Object> prepared = prepareAccountPayload(source);
            if (prepared == null) {
                skipped++;
                continue;
            }
            String token = AppConfigService.clean(prepared.get("access_token"));
            if (accounts.containsKey(token)) {
                Map<String, Object> merged = new LinkedHashMap<String, Object>(accounts.get(token));
                merged.putAll(prepared);
                accounts.put(token, normalize(merged));
                skipped++;
            } else {
                accounts.put(token, normalize(prepared));
                added++;
            }
        }
        save();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("added", added);
        result.put("skipped", skipped);
        result.put("items", listAccounts());
        return result;
    }

    public synchronized Map<String, Object> deleteAccounts(List<String> tokens) {
        int removed = 0;
        for (String token : tokens) {
            if (accounts.remove(AppConfigService.clean(token)) != null) {
                removed++;
            }
        }
        save();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("removed", removed);
        result.put("items", listAccounts());
        return result;
    }

    public synchronized Map<String, Object> updateAccount(String accessToken, Map<String, Object> updates) {
        String token = AppConfigService.clean(accessToken);
        Map<String, Object> current = accounts.get(token);
        if (current == null) {
            return null;
        }
        Map<String, Object> merged = new LinkedHashMap<String, Object>(current);
        merged.putAll(updates);
        Map<String, Object> normalized = normalize(merged);
        accounts.put(token, normalized);
        save();
        return new LinkedHashMap<String, Object>(normalized);
    }

    public synchronized Map<String, Object> getAccount(String accessToken) {
        Map<String, Object> item = accounts.get(AppConfigService.clean(accessToken));
        return item == null ? null : new LinkedHashMap<String, Object>(item);
    }

    public synchronized List<String> listLimitedTokens() {
        List<String> result = new ArrayList<String>();
        for (Map<String, Object> account : accounts.values()) {
            if (isLimitedStatus(AppConfigService.clean(account.get("status")))) {
                String token = AppConfigService.clean(account.get("access_token"));
                if (!token.isEmpty()) {
                    result.add(token);
                }
            }
        }
        return result;
    }

    public synchronized List<String> listExpiringAccessTokens() {
        List<String> result = new ArrayList<String>();
        for (Map<String, Object> account : accounts.values()) {
            String token = AppConfigService.clean(account.get("access_token"));
            if (!token.isEmpty() && !AppConfigService.clean(account.get("refresh_token")).isEmpty() && needsTokenRefresh(token)) {
                result.add(token);
            }
        }
        return result;
    }

    public synchronized List<String> listRefreshTokenKeepaliveTokens() {
        List<String> result = new ArrayList<String>();
        Instant threshold = Instant.now().minus(3, ChronoUnit.DAYS);
        for (Map<String, Object> account : accounts.values()) {
            String token = AppConfigService.clean(account.get("access_token"));
            String refreshToken = AppConfigService.clean(account.get("refresh_token"));
            Instant lastRefresh = parseTime(first(account.get("last_token_refresh_at"), account.get("created_at")));
            if (!token.isEmpty() && !refreshToken.isEmpty() && lastRefresh != null && lastRefresh.isBefore(threshold)
                    && !hasRecentTokenRefreshError(account, 6 * 60 * 60L)) {
                result.add(token);
                if (result.size() >= 3) {
                    break;
                }
            }
        }
        return result;
    }

    public Map<String, Object> keepaliveRefreshTokens(List<String> accessTokens) {
        int refreshed = 0;
        List<Map<String, Object>> errors = new ArrayList<Map<String, Object>>();
        for (String token : uniqueTokens(accessTokens)) {
            String activeToken = refreshAccessToken(token, true, "refresh_token_keepalive");
            Map<String, Object> account = getAccount(activeToken);
            String error = account == null ? "" : AppConfigService.clean(account.get("last_token_refresh_error"));
            if (!error.isEmpty()) {
                errors.add(map("token", anonymizeToken(token), "error", error));
            } else if (account != null) {
                refreshed++;
            }
        }
        return map("refreshed", refreshed, "errors", errors, "items", listAccounts());
    }

    public Map<String, Object> refreshAccounts(List<String> accessTokens) {
        List<String> targets = uniqueTokens(accessTokens);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> errors = new ArrayList<Map<String, Object>>();
        int refreshed = 0;
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(10, Math.max(1, targets.size())));
        Map<Future<Map<String, Object>>, String> futures = new LinkedHashMap<Future<Map<String, Object>>, String>();
        try {
            for (String token : targets) {
                futures.put(executor.submit(() -> fetchRemoteInfo(token, "refresh_accounts")), token);
            }
            for (Map.Entry<Future<Map<String, Object>>, String> entry : futures.entrySet()) {
                try {
                    if (entry.getKey().get() != null) {
                        refreshed++;
                    }
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    Map<String, Object> error = new LinkedHashMap<String, Object>();
                    error.put("token", anonymizeToken(entry.getValue()));
                    error.put("error", cause.getMessage());
                    errors.add(error);
                }
            }
        } finally {
            executor.shutdownNow();
        }
        result.put("refreshed", refreshed);
        result.put("errors", errors);
        result.put("items", listAccounts());
        return result;
    }

    public Map<String, Object> fetchRemoteInfo(String accessToken, String event) {
        requireRemoteClient();
        String activeToken = refreshAccessToken(accessToken, false, event + ":preflight");
        Map<String, Object> remote;
        try {
            remote = remoteUserInfo(activeToken);
        } catch (InvalidAccessTokenException exception) {
            String refreshedToken = refreshAccessToken(activeToken, true, event + ":invalid_access_token");
            if (!refreshedToken.equals(activeToken)) {
                try {
                    activeToken = refreshedToken;
                    remote = remoteUserInfo(activeToken);
                } catch (InvalidAccessTokenException retryException) {
                    recordInvalidToken(activeToken, event, retryException.getMessage());
                    throw retryException;
                }
            } else {
                recordInvalidToken(activeToken, event, exception.getMessage());
                throw exception;
            }
        } catch (RuntimeException exception) {
            recordRefreshError(activeToken, exception.getMessage());
            throw exception;
        }
        Map<String, Object> me = mapValue(remote.get("me"));
        Map<String, Object> init = mapValue(remote.get("init"));
        Map<String, Object> accountPayload = mapValue(remote.get("account"));
        Map<String, Object> defaultAccount = mapValue(mapValue(mapValue(accountPayload.get("accounts")).get("default")).get("account"));
        List<?> progress = init.get("limits_progress") instanceof List ? (List<?>) init.get("limits_progress") : new ArrayList<Object>();
        int quota = 0;
        String restoreAt = "";
        boolean quotaUnknown = true;
        for (Object value : progress) {
            Map<String, Object> item = mapValue(value);
            if ("image_gen".equals(item.get("feature_name"))) {
                quota = intValue(item.get("remaining"));
                restoreAt = AppConfigService.clean(item.get("reset_after"));
                quotaUnknown = false;
                break;
            }
        }
        String planType = valueOr(defaultAccount.get("plan_type"), "free");
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("email", me.get("email"));
        updates.put("user_id", me.get("id"));
        updates.put("type", planType);
        updates.put("quota", quota);
        updates.put("image_quota_unknown", quotaUnknown);
        updates.put("limits_progress", progress);
        updates.put("default_model_slug", init.get("default_model_slug"));
        updates.put("restore_at", restoreAt.isEmpty() ? null : restoreAt);
        updates.put("status", quotaUnknown && !"free".equalsIgnoreCase(planType) ? "正常" : quota == 0 ? "限流" : "正常");
        updates.put("invalid_count", 0);
        updates.put("last_refresh_error", null);
        updates.put("last_refresh_error_at", null);
        return updateAccount(activeToken, updates);
    }

    public String refreshAccessToken(String accessToken, boolean force, String event) {
        requireRemoteClient();
        String token = AppConfigService.clean(accessToken);
        Map<String, Object> account = getAccount(token);
        if (account == null || (!force && !needsTokenRefresh(token))) {
            return token;
        }
        String refreshToken = AppConfigService.clean(account.get("refresh_token"));
        if (refreshToken.isEmpty()) {
            return token;
        }
        if (!force && hasRecentTokenRefreshError(account, 5 * 60L)) {
            return token;
        }
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", "app_2SKx67EdpoN0G6j64rFvigXD");
        try {
            UpstreamHttpClient.Response response = upstream.session().postForm("https://auth.openai.com/oauth/token",
                    java.util.Collections.singletonMap("User-Agent", oauthUserAgent()), encodedForm(form), 30);
            response.requireSuccess("oauth refresh");
            Map<String, Object> refreshed = response.jsonObject();
            String nextToken = AppConfigService.clean(refreshed.get("access_token"));
            if (nextToken.isEmpty()) {
                throw new IllegalStateException("oauth refresh did not return access_token");
            }
            synchronized (this) {
                Map<String, Object> current = accounts.remove(token);
                if (current == null) {
                    return token;
                }
                current.put("access_token", nextToken);
                current.put("refresh_token", valueOr(refreshed.get("refresh_token"), refreshToken));
                String idToken = AppConfigService.clean(refreshed.get("id_token"));
                if (!idToken.isEmpty()) {
                    current.put("id_token", idToken);
                }
                current.put("last_token_refresh_at", Instant.now().toString());
                current.put("last_token_refresh_error", null);
                current.put("last_token_refresh_error_at", null);
                accounts.put(nextToken, normalize(current));
                save();
            }
            addAccountLog("refresh_token 已刷新 access_token", map("source", event, "token", anonymizeToken(nextToken)));
            return nextToken;
        } catch (RuntimeException exception) {
            updateAccount(token, map("last_token_refresh_error", exception.getMessage(), "last_token_refresh_error_at", Instant.now().toString()));
            addAccountLog("refresh_token 刷新 access_token 失败", map("source", event, "token", anonymizeToken(token), "error", exception.getMessage()));
            return token;
        }
    }

    public synchronized List<Map<String, String>> buildExportItems(List<String> accessTokens) {
        Set<String> targets = new LinkedHashSet<String>(accessTokens == null ? new ArrayList<String>() : accessTokens);
        List<Map<String, String>> result = new ArrayList<Map<String, String>>();
        for (Map<String, Object> account : accounts.values()) {
            String accessToken = AppConfigService.clean(account.get("access_token"));
            if (!targets.isEmpty() && !targets.contains(accessToken)) {
                continue;
            }
            String refreshToken = AppConfigService.clean(account.get("refresh_token"));
            String idToken = AppConfigService.clean(account.get("id_token"));
            if (accessToken.isEmpty() || refreshToken.isEmpty() || idToken.isEmpty()) {
                continue;
            }
            Map<String, Object> accessPayload = decodeJwt(accessToken);
            Map<String, Object> idPayload = decodeJwt(idToken);
            Map<String, Object> authClaim = mapValue(accessPayload.get("https://api.openai.com/auth"));
            Map<String, Object> profileClaim = mapValue(accessPayload.get("https://api.openai.com/profile"));
            Map<String, String> item = new LinkedHashMap<String, String>();
            item.put("type", valueOr(account.get("export_type"), "codex"));
            item.put("email", first(account.get("email"), profileClaim.get("email"), idPayload.get("email")));
            item.put("account_id", first(account.get("account_id"), authClaim.get("chatgpt_account_id"), account.get("user_id")));
            item.put("access_token", accessToken);
            item.put("refresh_token", refreshToken);
            item.put("id_token", idToken);
            item.put("expired", timestampToIso(accessPayload.get("exp")));
            item.put("last_refresh", timestampToIso(accessPayload.get("iat")));
            String password = AppConfigService.clean(account.get("password"));
            if (!password.isEmpty()) {
                item.put("password", password);
            }
            result.add(item);
        }
        return result;
    }

    public synchronized Map<String, Object> getStats() {
        int active = 0;
        int limited = 0;
        int abnormal = 0;
        int disabled = 0;
        int totalQuota = 0;
        int unlimited = 0;
        int success = 0;
        int fail = 0;
        Map<String, Integer> byType = new LinkedHashMap<String, Integer>();
        for (Map<String, Object> account : accounts.values()) {
            String status = AppConfigService.clean(account.get("status"));
            if ("正常".equals(status)) {
                active++;
                totalQuota += intValue(account.get("quota"));
                if (AppConfigService.booleanValue(account.get("image_quota_unknown"), false)) {
                    unlimited++;
                }
            } else if ("限流".equals(status)) {
                limited++;
            } else if ("异常".equals(status)) {
                abnormal++;
            } else if ("禁用".equals(status)) {
                disabled++;
            }
            success += intValue(account.get("success"));
            fail += intValue(account.get("fail"));
            String type = valueOr(account.get("type"), "unknown");
            byType.put(type, byType.containsKey(type) ? byType.get(type) + 1 : 1);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("total", accounts.size());
        result.put("cumulative_total", accounts.size());
        result.put("active", active);
        result.put("limited", limited);
        result.put("abnormal", abnormal);
        result.put("disabled", disabled);
        result.put("total_quota", totalQuota);
        result.put("unlimited_quota_count", unlimited);
        result.put("total_success", success);
        result.put("total_fail", fail);
        result.put("by_type", byType);
        return result;
    }

    private Map<String, Map<String, Object>> loadAccounts() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> item : storage.loadAccounts()) {
            Map<String, Object> normalized = normalize(item);
            String token = AppConfigService.clean(normalized.get("access_token"));
            if (!token.isEmpty()) {
                result.put(token, normalized);
            }
        }
        return result;
    }

    private void save() {
        storage.saveAccounts(copyItems(accounts.values()));
    }

    private Map<String, Object> prepareAccountPayload(Map<String, Object> source) {
        String token = first(source.get("access_token"), source.get("accessToken"));
        if (token.isEmpty()) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<String, Object>(source);
        item.remove("accessToken");
        item.put("access_token", token);
        if ("codex".equalsIgnoreCase(AppConfigService.clean(item.get("type")))) {
            item.put("export_type", "codex");
            item.put("type", "free");
        }
        return item;
    }

    private Map<String, Object> normalize(Map<String, Object> source) {
        Map<String, Object> item = new LinkedHashMap<String, Object>(source);
        item.put("access_token", first(item.get("access_token"), item.get("accessToken")));
        item.remove("accessToken");
        item.put("type", valueOr(item.get("type"), "free"));
        item.put("status", valueOr(item.get("status"), "正常"));
        item.put("quota", Math.max(0, intValue(item.get("quota"))));
        item.put("image_quota_unknown", AppConfigService.booleanValue(item.get("image_quota_unknown"), false));
        item.put("email", emptyToNull(item.get("email")));
        item.put("user_id", emptyToNull(item.get("user_id")));
        item.put("limits_progress", item.get("limits_progress") instanceof List ? item.get("limits_progress") : new ArrayList<Object>());
        item.put("default_model_slug", emptyToNull(item.get("default_model_slug")));
        item.put("restore_at", emptyToNull(item.get("restore_at")));
        item.put("success", intValue(item.get("success")));
        item.put("fail", intValue(item.get("fail")));
        item.put("invalid_count", intValue(item.get("invalid_count")));
        item.put("last_used_at", emptyToNull(item.get("last_used_at")));
        item.put("last_invalid_at", emptyToNull(item.get("last_invalid_at")));
        item.put("last_refresh_error", emptyToNull(item.get("last_refresh_error")));
        item.put("last_refresh_error_at", emptyToNull(item.get("last_refresh_error_at")));
        item.put("last_token_refresh_at", emptyToNull(item.get("last_token_refresh_at")));
        item.put("last_token_refresh_error", emptyToNull(item.get("last_token_refresh_error")));
        item.put("last_token_refresh_error_at", emptyToNull(item.get("last_token_refresh_error_at")));
        item.put("created_at", valueOr(item.get("created_at"), java.time.LocalDateTime.now().toString()));
        return item;
    }

    private boolean needsTokenRefresh(String token) {
        Map<String, Object> payload = decodeJwt(token);
        try {
            long expires = Long.parseLong(AppConfigService.clean(payload.get("exp")));
            return expires > 0 && expires - Instant.now().getEpochSecond() <= 24 * 60 * 60;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private Map<String, Object> remoteUserInfo(String token) {
        UpstreamHttpClient.Session session = upstream.session();
        Map<String, String> base = upstreamBaseHeaders(token);
        UpstreamHttpClient.Response meResponse = session.get("https://chatgpt.com/backend-api/me",
                upstreamHeaders(base, "/backend-api/me"), 20);
        UpstreamHttpClient.Response initResponse = session.postJson("https://chatgpt.com/backend-api/conversation/init",
                upstreamHeaders(base, "/backend-api/conversation/init"),
                map("gizmo_id", null, "requested_default_model", null, "conversation_id", null, "timezone_offset_min", -480), 20);
        UpstreamHttpClient.Response accountResponse = session.get(
                "https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27?timezone_offset_min=-480",
                upstreamHeaders(base, "/backend-api/accounts/check/v4-2023-04-27"), 20);
        requireAccountResponse(meResponse, "/backend-api/me");
        requireAccountResponse(initResponse, "/backend-api/conversation/init");
        requireAccountResponse(accountResponse, "/backend-api/accounts/check");
        return map("me", meResponse.jsonObject(), "init", initResponse.jsonObject(), "account", accountResponse.jsonObject());
    }

    private Map<String, String> upstreamBaseHeaders(String token) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0");
        headers.put("Origin", "https://chatgpt.com");
        headers.put("Referer", "https://chatgpt.com/");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-US;q=0.7");
        headers.put("Cache-Control", "no-cache");
        headers.put("Pragma", "no-cache");
        headers.put("Priority", "u=1, i");
        headers.put("Sec-Ch-Ua", "\"Microsoft Edge\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"");
        headers.put("Sec-Ch-Ua-Arch", "\"x86\"");
        headers.put("Sec-Ch-Ua-Bitness", "\"64\"");
        headers.put("Sec-Ch-Ua-Mobile", "?0");
        headers.put("Sec-Ch-Ua-Platform", "\"Windows\"");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("OAI-Device-Id", UUID.randomUUID().toString());
        headers.put("OAI-Session-Id", UUID.randomUUID().toString());
        headers.put("OAI-Language", "zh-CN");
        return headers;
    }

    private Map<String, String> upstreamHeaders(Map<String, String> base, String path) {
        Map<String, String> headers = new LinkedHashMap<String, String>(base);
        headers.put("X-OpenAI-Target-Path", path);
        headers.put("X-OpenAI-Target-Route", path);
        return headers;
    }

    private void requireAccountResponse(UpstreamHttpClient.Response response, String path) {
        if (response.getStatus() == 401) {
            throw new InvalidAccessTokenException(path + " failed: HTTP 401");
        }
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException(path + " failed: HTTP " + response.getStatus());
        }
    }

    private synchronized void recordRefreshError(String token, String error) {
        updateAccount(token, map("last_refresh_error", error, "last_refresh_error_at", Instant.now().toString()));
    }

    private synchronized void recordInvalidToken(String token, String event, String error) {
        Map<String, Object> account = accounts.get(token);
        if (account == null) {
            return;
        }
        int invalidCount = intValue(account.get("invalid_count")) + 1;
        updateAccount(token, map("invalid_count", invalidCount, "last_invalid_at", Instant.now().toString(),
                "last_refresh_error", error, "last_refresh_error_at", Instant.now().toString()));
        if (invalidCount > 1) {
            if (config != null && config.getAutoRemoveInvalidAccounts()) {
                deleteAccounts(java.util.Collections.singletonList(token));
            } else {
                updateAccount(token, map("status", "异常", "quota", 0));
            }
        } else {
            addAccountLog("暂缓标记异常账号", map("source", event, "token", anonymizeToken(token), "error", error));
        }
    }

    private String encodedForm(Map<String, String> form) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (result.length() > 0) {
                result.append('&');
            }
            try {
                result.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                        .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            } catch (Exception exception) {
                throw new IllegalStateException("unable to encode oauth form", exception);
            }
        }
        return result.toString();
    }

    private String oauthUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36";
    }

    private boolean isUnavailableStatus(String status) {
        return "disabled".equalsIgnoreCase(status) || "abnormal".equalsIgnoreCase(status)
                || "禁用".equals(status) || "异常".equals(status);
    }

    private boolean isLimitedStatus(String status) {
        return "limited".equalsIgnoreCase(status) || "限流".equals(status);
    }

    private boolean hasRecentTokenRefreshError(Map<String, Object> account, long seconds) {
        if (AppConfigService.clean(account.get("last_token_refresh_error")).isEmpty()) {
            return false;
        }
        Instant value = parseTime(AppConfigService.clean(account.get("last_token_refresh_error_at")));
        return value != null && value.isAfter(Instant.now().minus(seconds, ChronoUnit.SECONDS));
    }

    private Instant parseTime(String value) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void requireRemoteClient() {
        if (upstream == null) {
            throw new IllegalStateException("remote account client is unavailable");
        }
    }

    private static final class InvalidAccessTokenException extends IllegalStateException {
        private InvalidAccessTokenException(String message) {
            super(message);
        }
    }

    private void addAccountLog(String summary, Map<String, Object> detail) {
        if (logService != null) {
            logService.add("account", summary, detail);
        }
    }

    private String anonymizeToken(String token) {
        String value = AppConfigService.clean(token);
        return value.length() <= 12 ? value : value.substring(0, 6) + "..." + value.substring(value.length() - 6);
    }

    private List<String> uniqueTokens(List<String> source) {
        Set<String> seen = new LinkedHashSet<String>();
        if (source != null) {
            for (String token : source) {
                String value = AppConfigService.clean(token);
                if (!value.isEmpty()) {
                    seen.add(value);
                }
            }
        }
        return new ArrayList<String>(seen);
    }

    private Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }

    private Map<String, Object> decodeJwt(String token) {
        try {
            String[] parts = token.split("\\.");
            byte[] decoded = Base64.getUrlDecoder().decode(pad(parts[1]));
            return mapper.readValue(new String(decoded, StandardCharsets.UTF_8), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception exception) {
            return new LinkedHashMap<String, Object>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private String timestampToIso(Object value) {
        try {
            long seconds = Long.parseLong(AppConfigService.clean(value));
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.of("Asia/Shanghai")));
        } catch (Exception exception) {
            return "";
        }
    }

    private String pad(String value) {
        String result = value;
        while (result.length() % 4 != 0) {
            result += "=";
        }
        return result;
    }

    private List<Map<String, Object>> copyItems(Iterable<Map<String, Object>> source) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : source) {
            result.add(new LinkedHashMap<String, Object>(item));
        }
        return result;
    }

    private int intValue(Object value) {
        return AppConfigService.intValue(value, 0, 0);
    }

    private Object emptyToNull(Object value) {
        String text = AppConfigService.clean(value);
        return text.isEmpty() ? null : text;
    }

    private String valueOr(Object value, String fallback) {
        String text = AppConfigService.clean(value);
        return text.isEmpty() ? fallback : text;
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
}
