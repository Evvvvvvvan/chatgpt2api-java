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
public class CpaService {
    private final ObjectMapper mapper;
    private final RemoteJsonClient client;
    private final AccountService accounts;
    private final Path file;
    private List<Map<String, Object>> pools;

    public CpaService(ObjectMapper mapper, RemoteJsonClient client, AccountService accounts, AppConfigService config) {
        this.mapper = mapper;
        this.client = client;
        this.accounts = accounts;
        this.file = config.getDataDir().resolve("cpa_config.json");
        this.pools = load();
    }

    public synchronized List<Map<String, Object>> listPools() {
        return sanitizeList(pools);
    }

    public synchronized Map<String, Object> getPool(String id) {
        for (Map<String, Object> pool : pools) {
            if (id.equals(pool.get("id"))) {
                return new LinkedHashMap<String, Object>(pool);
            }
        }
        return null;
    }

    public synchronized Map<String, Object> addPool(String name, String baseUrl, String secretKey) {
        Map<String, Object> pool = normalize(map("id", compactId(), "name", name, "base_url", baseUrl, "secret_key", secretKey));
        pools.add(pool);
        save();
        return sanitize(pool);
    }

    public synchronized Map<String, Object> updatePool(String id, Map<String, Object> updates) {
        for (int index = 0; index < pools.size(); index++) {
            if (!id.equals(pools.get(index).get("id"))) {
                continue;
            }
            Map<String, Object> merged = new LinkedHashMap<String, Object>(pools.get(index));
            merged.putAll(updates);
            merged.put("id", id);
            pools.set(index, normalize(merged));
            save();
            return sanitize(pools.get(index));
        }
        return null;
    }

    public synchronized boolean deletePool(String id) {
        for (int index = 0; index < pools.size(); index++) {
            if (id.equals(pools.get(index).get("id"))) {
                pools.remove(index);
                save();
                return true;
            }
        }
        return false;
    }

    public List<Map<String, Object>> remoteFiles(String id) {
        Map<String, Object> pool = getPool(id);
        if (pool == null) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + pool.get("secret_key"));
        Map<String, Object> response = client.get(trimSlash(String.valueOf(pool.get("base_url"))) + "/v0/management/auth-files", headers, null);
        Object items = response.get("files");
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (items instanceof List) {
            for (Object value : (List<?>) items) {
                if (!(value instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) value;
                String name = AppConfigService.clean(item.get("name"));
                if (!name.isEmpty()) {
                    result.add(map("name", name, "email", first(item.get("email"), item.get("account"))));
                }
            }
        }
        return result;
    }

    public synchronized Map<String, Object> startImport(String id, List<String> names) {
        final Map<String, Object> pool = getPool(id);
        if (pool == null) {
            return null;
        }
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("selected files is required");
        }
        Map<String, Object> job = newJob(names.size());
        setJob(id, job);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                runImport(id, pool, names);
                executor.shutdown();
            }
        });
        return job;
    }

    public synchronized Map<String, Object> importJob(String id) {
        Map<String, Object> pool = getPool(id);
        return pool == null ? null : copyMap(pool.get("import_job"));
    }

    private void runImport(String id, Map<String, Object> pool, List<String> names) {
        updateJob(id, map("status", "running"));
        List<String> tokens = new ArrayList<String>();
        List<Map<String, Object>> errors = new ArrayList<Map<String, Object>>();
        for (String name : names) {
            try {
                Map<String, String> headers = new LinkedHashMap<String, String>();
                headers.put("Authorization", "Bearer " + pool.get("secret_key"));
                Map<String, Object> query = map("name", name);
                Map<String, Object> response = client.get(trimSlash(String.valueOf(pool.get("base_url"))) + "/v0/management/auth-files/download", headers, query);
                String token = AppConfigService.clean(response.get("access_token"));
                if (token.isEmpty()) {
                    throw new IllegalStateException("missing access_token");
                }
                tokens.add(token);
            } catch (RuntimeException exception) {
                errors.add(map("name", name, "error", exception.getMessage()));
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
                "completed", names.size(),
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
        for (Map<String, Object> pool : pools) {
            if (id.equals(pool.get("id"))) {
                pool.put("import_job", job);
                save();
                return;
            }
        }
    }

    private List<Map<String, Object>> load() {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<Map<String, Object>>();
        }
        try {
            Object value = mapper.readValue(file.toFile(), Object.class);
            if (value instanceof Map && ((Map<?, ?>) value).containsKey("base_url")) {
                List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
                @SuppressWarnings("unchecked")
                Map<String, Object> pool = normalize((Map<String, Object>) value);
                if (!AppConfigService.clean(pool.get("base_url")).isEmpty()) {
                    result.add(pool);
                }
                return result;
            }
            List<Map<String, Object>> source = mapper.convertValue(value, new TypeReference<ArrayList<Map<String, Object>>>() { });
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> pool : source) {
                result.add(normalize(pool));
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
                "secret_key", AppConfigService.clean(source.get("secret_key")),
                "import_job", source.get("import_job")
        );
    }

    private Map<String, Object> newJob(int total) {
        return map(
                "job_id", UUID.randomUUID().toString().replace("-", ""),
                "status", "pending",
                "created_at", Instant.now().toString(),
                "updated_at", Instant.now().toString(),
                "total", total,
                "completed", 0,
                "added", 0,
                "skipped", 0,
                "refreshed", 0,
                "failed", 0,
                "errors", new ArrayList<Object>()
        );
    }

    private List<Map<String, Object>> sanitizeList(List<Map<String, Object>> source) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : source) {
            result.add(sanitize(item));
        }
        return result;
    }

    private Map<String, Object> sanitize(Map<String, Object> pool) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(pool);
        result.remove("secret_key");
        return result;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pools) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to persist CPA config", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyMap(Object value) {
        return value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value) : null;
    }

    private String compactId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String trimSlash(String value) {
        return value.replaceAll("/+$", "");
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

    private Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }
}
