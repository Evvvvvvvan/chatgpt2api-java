package com.chatgpt2api.controller;

import com.chatgpt2api.account.AccountService;
import com.chatgpt2api.auth.AuthGuard;
import com.chatgpt2api.auth.AuthService;
import com.chatgpt2api.common.ApiException;
import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.integration.CpaService;
import com.chatgpt2api.integration.Sub2ApiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
public class AccountsController {
    private final AuthGuard authGuard;
    private final AuthService authService;
    private final AccountService accountService;
    private final ObjectMapper mapper;
    private final CpaService cpaService;
    private final Sub2ApiService sub2ApiService;

    public AccountsController(
            AuthGuard authGuard,
            AuthService authService,
            AccountService accountService,
            ObjectMapper mapper,
            CpaService cpaService,
            Sub2ApiService sub2ApiService) {
        this.authGuard = authGuard;
        this.authService = authService;
        this.accountService = accountService;
        this.mapper = mapper;
        this.cpaService = cpaService;
        this.sub2ApiService = sub2ApiService;
    }

    @GetMapping("/api/auth/users")
    public Map<String, Object> userKeys(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return withItems(authService.listKeys("user"));
    }

    @PostMapping("/api/auth/users")
    public Map<String, Object> createUserKey(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> created = authService.createKey("user", body == null ? "" : AppConfigService.clean(body.get("name")));
        created.put("items", authService.listKeys("user"));
        return created;
    }

    @PostMapping("/api/auth/users/{keyId}")
    public Map<String, Object> updateUserKey(
            @PathVariable String keyId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> updates = fields(body, "name", "enabled", "key");
        if (updates.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "还没有检测到改动，请修改后再保存");
        }
        Map<String, Object> item = authService.updateKey(keyId, updates, "user");
        if (item == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "这条用户密钥不存在，可能已经被删除");
        }
        Map<String, Object> result = withItems(authService.listKeys("user"));
        result.put("item", item);
        return result;
    }

    @DeleteMapping("/api/auth/users/{keyId}")
    public Map<String, Object> deleteUserKey(
            @PathVariable String keyId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        if (!authService.deleteKey(keyId, "user")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "这条用户密钥不存在，可能已经被删除");
        }
        return withItems(authService.listKeys("user"));
    }

    @GetMapping("/api/accounts")
    public Map<String, Object> accounts(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return withItems(accountService.listAccounts());
    }

    @PostMapping("/api/accounts")
    public Map<String, Object> createAccounts(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        List<String> tokens = strings(body.get("tokens"));
        List<Map<String, Object>> accounts = objects(body.get("accounts"));
        for (Map<String, Object> account : accounts) {
            String token = first(account.get("access_token"), account.get("accessToken"));
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        tokens = accountService.usableTokens(unique(tokens));
        if (tokens.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "tokens is required");
        }
        Map<String, Object> result = accounts.isEmpty() ? accountService.addAccounts(tokens) : accountService.addAccountItems(accounts);
        if (!accounts.isEmpty()) {
            Set<String> payloadTokens = new LinkedHashSet<String>();
            for (Map<String, Object> account : accounts) {
                payloadTokens.add(first(account.get("access_token"), account.get("accessToken")));
            }
            List<String> extraTokens = new ArrayList<String>();
            for (String token : tokens) {
                if (!payloadTokens.contains(token)) {
                    extraTokens.add(token);
                }
            }
            if (!extraTokens.isEmpty()) {
                Map<String, Object> extra = accountService.addAccounts(extraTokens);
                result.put("added", ((Integer) result.get("added")) + ((Integer) extra.get("added")));
                result.put("skipped", ((Integer) result.get("skipped")) + ((Integer) extra.get("skipped")));
                result.put("items", extra.get("items"));
            }
        }
        Map<String, Object> refresh = accountService.refreshAccounts(tokens);
        result.put("refreshed", refresh.get("refreshed"));
        result.put("errors", refresh.get("errors"));
        result.put("items", refresh.get("items"));
        return result;
    }

    @DeleteMapping("/api/accounts")
    public Map<String, Object> deleteAccounts(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        List<String> tokens = strings(body.get("tokens"));
        if (tokens.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "tokens is required");
        }
        return accountService.deleteAccounts(tokens);
    }

    @PostMapping("/api/accounts/refresh")
    public Map<String, Object> refreshAccounts(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        List<String> submitted = body == null ? new ArrayList<String>() : strings(body.get("access_tokens"));
        List<String> tokens = accountService.usableTokens(submitted);
        if (tokens.isEmpty() && submitted.isEmpty()) {
            tokens = accountService.listTokens();
        }
        if (tokens.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "access_tokens is required");
        }
        return accountService.refreshAccounts(tokens);
    }

    @PostMapping("/api/accounts/update")
    public Map<String, Object> updateAccount(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        String token = AppConfigService.clean(body.get("access_token"));
        if (token.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "access_token is required");
        }
        Map<String, Object> updates = fields(body, "type", "status", "quota");
        if (updates.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "还没有检测到改动，请修改后再保存");
        }
        Map<String, Object> item = accountService.updateAccount(token, updates);
        if (item == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "account not found");
        }
        Map<String, Object> result = withItems(accountService.listAccounts());
        result.put("item", item);
        return result;
    }

    @PostMapping("/api/accounts/export")
    public ResponseEntity<byte[]> exportAccounts(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) throws IOException {
        authGuard.requireAdmin(authorization);
        body = body == null ? new LinkedHashMap<String, Object>() : body;
        List<Map<String, String>> items = accountService.buildExportItems(strings(body.get("access_tokens")));
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "没有可导出的完整账号，需要同时有 access_token、refresh_token 和 id_token");
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        if ("zip".equals(body.get("format"))) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"codex-accounts-" + timestamp + ".zip\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(zipItems(items));
        }
        Object payload = items.size() == 1 ? items.get(0) : items;
        byte[] bytes = (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload) + "\n").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"codex-accounts-" + timestamp + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(bytes);
    }

    @GetMapping("/api/cpa/pools")
    public Map<String, Object> cpaPools(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return named("pools", cpaService.listPools());
    }

    @PostMapping("/api/cpa/pools")
    public Map<String, Object> createCpaPool(@RequestBody Map<String, Object> body, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        String baseUrl = AppConfigService.clean(body.get("base_url"));
        String secretKey = AppConfigService.clean(body.get("secret_key"));
        if (baseUrl.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "base_url is required");
        }
        if (secretKey.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "secret_key is required");
        }
        Map<String, Object> result = named("pools", cpaService.listPools());
        result.put("pool", cpaService.addPool(AppConfigService.clean(body.get("name")), baseUrl, secretKey));
        result.put("pools", cpaService.listPools());
        return result;
    }

    @PostMapping("/api/cpa/pools/{poolId}")
    public Map<String, Object> updateCpaPool(@PathVariable String poolId, @RequestBody Map<String, Object> body, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> pool = cpaService.updatePool(poolId, fields(body, "name", "base_url", "secret_key"));
        if (pool == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "pool not found");
        }
        Map<String, Object> result = named("pools", cpaService.listPools());
        result.put("pool", pool);
        return result;
    }

    @DeleteMapping("/api/cpa/pools/{poolId}")
    public Map<String, Object> deleteCpaPool(@PathVariable String poolId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        if (!cpaService.deletePool(poolId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "pool not found");
        }
        return named("pools", cpaService.listPools());
    }

    @GetMapping("/api/cpa/pools/{poolId}/files")
    public Map<String, Object> cpaFiles(@PathVariable String poolId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        List<Map<String, Object>> files = cpaService.remoteFiles(poolId);
        if (files == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "pool not found");
        }
        Map<String, Object> result = named("files", files);
        result.put("pool_id", poolId);
        return result;
    }

    @PostMapping("/api/cpa/pools/{poolId}/import")
    public Map<String, Object> cpaImport(@PathVariable String poolId, @RequestBody Map<String, Object> body, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> job = cpaService.startImport(poolId, strings(body.get("names")));
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "pool not found");
        }
        return named("import_job", job);
    }

    @GetMapping("/api/cpa/pools/{poolId}/import")
    public Map<String, Object> cpaImportStatus(@PathVariable String poolId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        if (cpaService.getPool(poolId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "pool not found");
        }
        return named("import_job", cpaService.importJob(poolId));
    }

    @GetMapping("/api/sub2api/servers")
    public Map<String, Object> sub2ApiServers(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return named("servers", sub2ApiService.listServers());
    }

    @PostMapping("/api/sub2api/servers")
    public Map<String, Object> createSub2ApiServer(@RequestBody Map<String, Object> body, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        if (AppConfigService.clean(body.get("base_url")).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "base_url is required");
        }
        if (AppConfigService.clean(body.get("api_key")).isEmpty()
                && (AppConfigService.clean(body.get("email")).isEmpty() || AppConfigService.clean(body.get("password")).isEmpty())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "email+password or api_key is required");
        }
        Map<String, Object> server = sub2ApiService.addServer(body);
        Map<String, Object> result = named("servers", sub2ApiService.listServers());
        result.put("server", server);
        return result;
    }

    @PostMapping("/api/sub2api/servers/{serverId}")
    public Map<String, Object> updateSub2ApiServer(@PathVariable String serverId, @RequestBody Map<String, Object> body, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> server = sub2ApiService.updateServer(serverId, fields(body, "name", "base_url", "email", "password", "api_key", "group_id"));
        if (server == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "server not found");
        }
        Map<String, Object> result = named("servers", sub2ApiService.listServers());
        result.put("server", server);
        return result;
    }

    @DeleteMapping("/api/sub2api/servers/{serverId}")
    public Map<String, Object> deleteSub2ApiServer(@PathVariable String serverId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        if (!sub2ApiService.deleteServer(serverId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "server not found");
        }
        return named("servers", sub2ApiService.listServers());
    }

    @GetMapping("/api/sub2api/servers/{serverId}/groups")
    public Map<String, Object> sub2ApiGroups(@PathVariable String serverId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        if (sub2ApiService.getServer(serverId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "server not found");
        }
        Map<String, Object> result = named("groups", sub2ApiService.groups(serverId));
        result.put("server_id", serverId);
        return result;
    }

    @GetMapping("/api/sub2api/servers/{serverId}/accounts")
    public Map<String, Object> sub2ApiAccounts(@PathVariable String serverId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        if (sub2ApiService.getServer(serverId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "server not found");
        }
        Map<String, Object> result = named("accounts", sub2ApiService.remoteAccounts(serverId));
        result.put("server_id", serverId);
        return result;
    }

    @PostMapping("/api/sub2api/servers/{serverId}/import")
    public Map<String, Object> sub2ApiImport(@PathVariable String serverId, @RequestBody Map<String, Object> body, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        if (sub2ApiService.getServer(serverId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "server not found");
        }
        return named("import_job", sub2ApiService.startImport(serverId, strings(body.get("account_ids"))));
    }

    @GetMapping("/api/sub2api/servers/{serverId}/import")
    public Map<String, Object> sub2ApiImportStatus(@PathVariable String serverId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        if (sub2ApiService.getServer(serverId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "server not found");
        }
        return named("import_job", sub2ApiService.importJob(serverId));
    }

    private byte[] zipItems(List<Map<String, String>> items) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(output);
        Set<String> names = new LinkedHashSet<String>();
        int index = 1;
        for (Map<String, String> item : items) {
            String base = safeName(first(item.get("email"), item.get("account_id")), String.format("account-%03d", index++));
            String name = base;
            int suffix = 2;
            while (names.contains(name)) {
                name = base + "-" + suffix++;
            }
            names.add(name);
            zip.putNextEntry(new ZipEntry(name + ".json"));
            zip.write((mapper.writerWithDefaultPrettyPrinter().writeValueAsString(item) + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        zip.finish();
        zip.close();
        return output.toByteArray();
    }

    private String safeName(String value, String fallback) {
        String name = value.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[._-]+|[._-]+$", "");
        if (name.isEmpty()) {
            name = fallback;
        }
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    private Map<String, Object> withItems(Object items) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        return result;
    }

    private Map<String, Object> named(String name, Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(name, value);
        return result;
    }

    private Map<String, Object> fields(Map<String, Object> body, String... keys) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (String key : keys) {
            if (body.containsKey(key) && body.get(key) != null) {
                result.put(key, body.get(key));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objects(Object value) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof Map) {
                    result.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
                }
            }
        }
        return result;
    }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                String text = AppConfigService.clean(item);
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return unique(result);
    }

    private List<String> unique(List<String> source) {
        return new ArrayList<String>(new LinkedHashSet<String>(source));
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
