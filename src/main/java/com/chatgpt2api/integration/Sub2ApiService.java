package com.chatgpt2api.integration;

import com.chatgpt2api.account.AccountService;
import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.http.RemoteJsonClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class Sub2ApiService {
    private final ObjectMapper mapper;
    private final RemoteJsonClient client;
    private final AccountService accounts;
    private final Path file;
    private List<Map<String, Object>> servers;
    private final Map<String, Map<String, Object>> tokenCache = new LinkedHashMap<String, Map<String, Object>>();

    public Sub2ApiService(ObjectMapper mapper, RemoteJsonClient client, AccountService accounts, AppConfigService config) {
        this.mapper = mapper;
        this.client = client;
        this.accounts = accounts;
        this.file = config.getDataDir().resolve("sub2api_config.json");
        this.servers = load();
    }

    public synchronized List<Map<String, Object>> listServers() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> server : servers) {
            result.add(sanitize(server));
        }
        return result;
    }

    public synchronized Map<String, Object> getServer(String id) {
        for (Map<String, Object> server : servers) {
            if (id.equals(server.get("id"))) {
                return new LinkedHashMap<String, Object>(server);
            }
        }
        return null;
    }

    public synchronized Map<String, Object> addServer(Map<String, Object> payload) {
        Map<String, Object> server = normalize(payload);
        servers.add(server);
        save();
        return sanitize(server);
    }

    public synchronized Map<String, Object> updateServer(String id, Map<String, Object> updates) {
        for (int index = 0; index < servers.size(); index++) {
            if (!id.equals(servers.get(index).get("id"))) {
                continue;
            }
            Map<String, Object> merged = new LinkedHashMap<String, Object>(servers.get(index));
            merged.putAll(updates);
            merged.put("id", id);
            servers.set(index, normalize(merged));
            tokenCache.remove(id);
            save();
            return sanitize(servers.get(index));
        }
        return null;
    }

    public synchronized boolean deleteServer(String id) {
        for (int index = 0; index < servers.size(); index++) {
            if (id.equals(servers.get(index).get("id"))) {
                servers.remove(index);
                tokenCache.remove(id);
                save();
                return true;
            }
        }
        return false;
    }

    public List<Map<String, Object>> groups(String id) {
        Map<String, Object> server = requiredServer(id);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int page = 1;
        while (true) {
            Map<String, Object> payload = client.get(
                    trimSlash(String.valueOf(server.get("base_url"))) + "/api/v1/admin/groups",
                    authHeaders(server),
                    map("page", page, "page_size", 200)
            );
            Page items = page(payload);
            for (Map<String, Object> group : items.items) {
                if (group.get("id") != null) {
                    result.add(map(
                            "id", String.valueOf(group.get("id")),
                            "name", AppConfigService.clean(group.get("name")),
                            "description", AppConfigService.clean(group.get("description")),
                            "platform", AppConfigService.clean(group.get("platform")),
                            "status", AppConfigService.clean(group.get("status")),
                            "account_count", intValue(group.get("account_count")),
                            "active_account_count", intValue(group.get("active_account_count"))
                    ));
                }
            }
            if (page * 200 >= items.total || items.items.size() < 200) {
                break;
            }
            page++;
        }
        return result;
    }

    public List<Map<String, Object>> remoteAccounts(String id) {
        Map<String, Object> server = requiredServer(id);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int page = 1;
        while (true) {
            Map<String, Object> query = map("platform", "openai", "type", "oauth", "page", page, "page_size", 200);
            String groupId = AppConfigService.clean(server.get("group_id"));
            if (!groupId.isEmpty()) {
                query.put("group", groupId);
            }
            Page items = page(client.get(trimSlash(String.valueOf(server.get("base_url"))) + "/api/v1/admin/accounts", authHeaders(server), query));
            for (Map<String, Object> account : items.items) {
                Map<String, Object> credentials = object(account.get("credentials"));
                String token = token(credentials);
                if (!token.isEmpty()) {
                    result.add(map(
                            "id", first(account.get("id"), credentials.get("chatgpt_account_id")),
                            "name", AppConfigService.clean(account.get("name")),
                            "email", first(credentials.get("email"), account.get("name")),
                            "plan_type", AppConfigService.clean(credentials.get("plan_type")),
                            "status", AppConfigService.clean(account.get("status")),
                            "expires_at", AppConfigService.clean(credentials.get("expires_at")),
                            "has_refresh_token", !AppConfigService.clean(credentials.get("refresh_token")).isEmpty()
                    ));
                }
            }
            if (page * 200 >= items.total || items.items.size() < 200) {
                break;
            }
            page++;
        }
        return result;
    }

    public synchronized Map<String, Object> startImport(String id, List<String> ids) {
        final Map<String, Object> server = requiredServer(id);
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("account ids is required");
        }
        Map<String, Object> job = newJob(ids.size());
        setJob(id, job);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                runImport(id, server, ids);
                executor.shutdown();
            }
        });
        return job;
    }

    public synchronized Map<String, Object> importJob(String id) {
        Map<String, Object> server = getServer(id);
        return server == null ? null : object(server.get("import_job"));
    }

    private void runImport(String id, Map<String, Object> server, List<String> ids) {
        updateJob(id, map("status", "running"));
        List<String> tokens = new ArrayList<String>();
        List<Map<String, Object>> errors = new ArrayList<Map<String, Object>>();
        for (String accountId : ids) {
            try {
                Map<String, Object> raw = client.get(
                        trimSlash(String.valueOf(server.get("base_url"))) + "/api/v1/admin/accounts/" + accountId,
                        authHeaders(server),
                        null
                );
                Map<String, Object> account = unwrap(raw);
                String accessToken = token(object(account.get("credentials")));
                if (accessToken.isEmpty()) {
                    throw new IllegalStateException("missing access_token");
                }
                tokens.add(accessToken);
            } catch (RuntimeException exception) {
                errors.add(map("name", accountId, "error", exception.getMessage()));
            }
            updateJob(id, map("completed", tokens.size() + errors.size(), "failed", errors.size(), "errors", errors));
        }
        if (tokens.isEmpty()) {
            updateJob(id, map("status", "failed", "failed", errors.size(), "errors", errors));
            return;
        }
        Map<String, Object> imported = accounts.addAccounts(tokens);
        Map<String, Object> refreshed = accounts.refreshAccounts(tokens);
        updateJob(id, map(
                "status", "completed",
                "completed", ids.size(),
                "added", imported.get("added"),
                "skipped", imported.get("skipped"),
                "refreshed", refreshed.get("refreshed"),
                "failed", errors.size() + ((List<?>) refreshed.get("errors")).size(),
                "errors", mergeErrors(errors, refreshed.get("errors"))
        ));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mergeErrors(List<Map<String, Object>> first, Object second) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>(first);
        if (second instanceof List) {
            for (Object value : (List<?>) second) {
                if (value instanceof Map) {
                    result.add(new LinkedHashMap<String, Object>((Map<String, Object>) value));
                }
            }
        }
        return result;
    }

    private synchronized void updateJob(String id, Map<String, Object> updates) {
        Map<String, Object> job = importJob(id);
        if (job == null) {
            return;
        }
        job.putAll(updates);
        job.put("updated_at", Instant.now().toString());
        setJob(id, job);
    }

    private synchronized void setJob(String id, Map<String, Object> job) {
        for (Map<String, Object> server : servers) {
            if (id.equals(server.get("id"))) {
                server.put("import_job", job);
                save();
            }
        }
    }

    private Map<String, String> authHeaders(Map<String, Object> server) {
        String apiKey = AppConfigService.clean(server.get("api_key"));
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (!apiKey.isEmpty()) {
            result.put("x-api-key", apiKey);
            return result;
        }
        String id = String.valueOf(server.get("id"));
        Map<String, Object> cached = tokenCache.get(id);
        if (cached != null && ((Long) cached.get("expires_at")) > System.currentTimeMillis()) {
            result.put("Authorization", "Bearer " + cached.get("token"));
            return result;
        }
        Map<String, Object> login = client.post(
                trimSlash(String.valueOf(server.get("base_url"))) + "/api/v1/auth/login",
                null,
                map("email", server.get("email"), "password", server.get("password"))
        );
        Map<String, Object> body = unwrap(login);
        String token = AppConfigService.clean(body.get("access_token"));
        if (token.isEmpty()) {
            throw new IllegalStateException("sub2api login did not return access_token");
        }
        long expires = System.currentTimeMillis() + Math.max(60, intValue(body.get("expires_in"))) * 1000L - 300000;
        tokenCache.put(id, map("token", token, "expires_at", expires));
        result.put("Authorization", "Bearer " + token);
        return result;
    }

    private Map<String, Object> requiredServer(String id) {
        Map<String, Object> result = getServer(id);
        if (result == null) {
            throw new IllegalArgumentException("server not found");
        }
        return result;
    }

    private List<Map<String, Object>> load() {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<Map<String, Object>>();
        }
        try {
            List<Map<String, Object>> values = mapper.readValue(file.toFile(), new TypeReference<ArrayList<Map<String, Object>>>() { });
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> value : values) {
                result.add(normalize(value));
            }
            return result;
        } catch (Exception exception) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    private Map<String, Object> normalize(Map<String, Object> source) {
        return map(
                "id", first(source.get("id"), compactId()),
                "name", AppConfigService.clean(source.get("name")),
                "base_url", AppConfigService.clean(source.get("base_url")),
                "email", AppConfigService.clean(source.get("email")),
                "password", AppConfigService.clean(source.get("password")),
                "api_key", AppConfigService.clean(source.get("api_key")),
                "group_id", AppConfigService.clean(source.get("group_id")),
                "import_job", source.get("import_job")
        );
    }

    private Map<String, Object> sanitize(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(source);
        result.remove("password");
        result.remove("api_key");
        result.put("has_api_key", !AppConfigService.clean(source.get("api_key")).isEmpty());
        return result;
    }

    private Page page(Map<String, Object> raw) {
        Object inner = unwrap(raw);
        if (inner instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) inner;
            return new Page(list, list.size());
        }
        Map<String, Object> value = object(inner);
        for (String key : new String[] {"items", "data", "list"}) {
            if (value.get(key) instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) value.get(key);
                return new Page(list, value.get("total") == null ? list.size() : intValue(value.get("total")));
            }
        }
        return new Page(new ArrayList<Map<String, Object>>(), 0);
    }

    private Map<String, Object> unwrap(Map<String, Object> value) {
        return value.containsKey("code") && value.get("data") instanceof Map ? object(value.get("data")) : value;
    }

    private String token(Map<String, Object> credentials) {
        return first(credentials.get("access_token"), credentials.get("accessToken"), credentials.get("token"));
    }

    private Map<String, Object> newJob(int total) {
        return map("job_id", UUID.randomUUID().toString().replace("-", ""), "status", "pending",
                "created_at", Instant.now().toString(), "updated_at", Instant.now().toString(),
                "total", total, "completed", 0, "added", 0, "skipped", 0, "refreshed", 0,
                "failed", 0, "errors", new ArrayList<Object>());
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(servers) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to persist sub2api config", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        return value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value) : new LinkedHashMap<String, Object>();
    }

    private String compactId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private int intValue(Object value) {
        return AppConfigService.intValue(value, 0, 0);
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

    private String trimSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }

    private static class Page {
        private final List<Map<String, Object>> items;
        private final int total;

        private Page(List<Map<String, Object>> items, int total) {
            this.items = items;
            this.total = total;
        }
    }
}
