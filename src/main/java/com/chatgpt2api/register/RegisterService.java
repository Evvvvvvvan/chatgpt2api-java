package com.chatgpt2api.register;

import com.chatgpt2api.account.AccountService;
import com.chatgpt2api.config.AppConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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
import java.util.concurrent.Future;

@Service
public class RegisterService {
    private final ObjectMapper mapper;
    private final AccountService accounts;
    private final OpenAiRegisterClient client;
    private final Path path;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private Map<String, Object> config;
    private final List<Map<String, Object>> logs = new ArrayList<Map<String, Object>>();
    private volatile boolean enabled;

    public RegisterService(ObjectMapper mapper, AppConfigService appConfig, AccountService accounts, OpenAiRegisterClient client) {
        this.mapper = mapper;
        this.accounts = accounts;
        this.client = client;
        this.path = appConfig.getDataDir().resolve("register.json");
    }

    @PostConstruct
    public synchronized void initialize() {
        config = normalize(read());
        boolean restart = AppConfigService.booleanValue(config.get("enabled"), false);
        config.put("enabled", false);
        enabled = false;
        save();
        if (restart) {
            start();
        }
    }

    public synchronized Map<String, Object> get() {
        Map<String, Object> result = copy(config);
        result.put("logs", new ArrayList<Map<String, Object>>(logs));
        return result;
    }

    public synchronized Map<String, Object> update(Map<String, Object> changes) {
        Map<String, Object> next = copy(config);
        if (changes != null) {
            next.putAll(changes);
        }
        config = normalize(next);
        config.put("enabled", enabled);
        save();
        return get();
    }

    public synchronized Map<String, Object> start() {
        if (enabled) {
            return get();
        }
        enabled = true;
        config.put("enabled", true);
        logs.clear();
        Map<String, Object> stats = baseStats();
        stats.put("job_id", UUID.randomUUID().toString().replace("-", ""));
        stats.put("started_at", Instant.now().toString());
        config.put("stats", stats);
        addLog("registration job started", "info");
        save();
        executor.submit(this::run);
        return get();
    }

    public synchronized Map<String, Object> stop() {
        enabled = false;
        config.put("enabled", false);
        addLog("registration stop requested", "info");
        save();
        return get();
    }

    public synchronized Map<String, Object> reset() {
        logs.clear();
        config.put("stats", baseStats());
        save();
        return get();
    }

    private void run() {
        int submitted = 0;
        List<Future<Map<String, Object>>> futures = new ArrayList<Future<Map<String, Object>>>();
        while (enabled) {
            Map<String, Object> snapshot = get();
            int threads = AppConfigService.intValue(snapshot.get("threads"), 3, 1);
            while (enabled && futures.size() < threads && !targetReached(snapshot, submitted)) {
                submitted++;
                final Map<String, Object> jobConfig = snapshot;
                futures.add(executor.submit(() -> client.register(jobConfig, this::addLog)));
            }
            if (futures.isEmpty()) {
                if ("total".equals(snapshot.get("mode")) || targetReached(snapshot, submitted)) {
                    break;
                }
                sleep(AppConfigService.intValue(snapshot.get("check_interval"), 5, 1) * 1000L);
                continue;
            }
            for (int index = futures.size() - 1; index >= 0; index--) {
                Future<Map<String, Object>> future = futures.get(index);
                if (!future.isDone()) {
                    continue;
                }
                try {
                    future.get();
                    bump(true, futures.size() - 1);
                } catch (Exception exception) {
                    addLog("registration failed: " + rootMessage(exception), "error");
                    bump(false, futures.size() - 1);
                }
                futures.remove(index);
            }
            if (targetReached(snapshot, submitted) && futures.isEmpty()) {
                break;
            }
            sleep(200L);
        }
        while (!futures.isEmpty()) {
            Future<Map<String, Object>> future = futures.remove(0);
            try {
                future.get();
                bump(true, futures.size());
            } catch (Exception exception) {
                addLog("registration failed: " + rootMessage(exception), "error");
                bump(false, futures.size());
            }
        }
        synchronized (this) {
            enabled = false;
            config.put("enabled", false);
            Map<String, Object> stats = stats();
            stats.put("finished_at", Instant.now().toString());
            save();
            addLog("registration job finished", "info");
        }
    }

    private synchronized boolean targetReached(Map<String, Object> snapshot, int submitted) {
        String mode = AppConfigService.clean(snapshot.get("mode"));
        Map<String, Object> pool = poolMetrics();
        stats().putAll(pool);
        if ("quota".equals(mode)) {
            return ((Integer) pool.get("current_quota")) >= AppConfigService.intValue(snapshot.get("target_quota"), 100, 1);
        }
        if ("available".equals(mode)) {
            return ((Integer) pool.get("current_available")) >= AppConfigService.intValue(snapshot.get("target_available"), 10, 1);
        }
        return submitted >= AppConfigService.intValue(snapshot.get("total"), 10, 1);
    }

    private synchronized void bump(boolean success, int running) {
        Map<String, Object> stats = stats();
        stats.put("done", ((Integer) stats.get("done")) + 1);
        stats.put(success ? "success" : "fail", ((Integer) stats.get(success ? "success" : "fail")) + 1);
        stats.put("running", running);
        stats.putAll(poolMetrics());
        stats.put("updated_at", Instant.now().toString());
        save();
    }

    private synchronized void addLog(String text, String level) {
        logs.add(map("time", Instant.now().toString(), "text", text, "level", level));
        while (logs.size() > 300) {
            logs.remove(0);
        }
    }

    private Map<String, Object> normalize(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(source);
        result.put("mail", source.get("mail") instanceof Map ? source.get("mail") : map("request_timeout", 30, "wait_timeout", 30, "wait_interval", 2, "providers", new ArrayList<Object>()));
        result.put("proxy", AppConfigService.clean(source.get("proxy")));
        result.put("total", AppConfigService.intValue(source.get("total"), 10, 1));
        result.put("threads", AppConfigService.intValue(source.get("threads"), 3, 1));
        String mode = AppConfigService.clean(source.get("mode"));
        result.put("mode", "quota".equals(mode) || "available".equals(mode) ? mode : "total");
        result.put("target_quota", AppConfigService.intValue(source.get("target_quota"), 100, 1));
        result.put("target_available", AppConfigService.intValue(source.get("target_available"), 10, 1));
        result.put("check_interval", AppConfigService.intValue(source.get("check_interval"), 5, 1));
        result.put("stats", source.get("stats") instanceof Map ? source.get("stats") : baseStats());
        return result;
    }

    private Map<String, Object> baseStats() {
        return map("success", 0, "fail", 0, "done", 0, "running", 0,
                "threads", config == null ? 3 : AppConfigService.intValue(config.get("threads"), 3, 1),
                "elapsed_seconds", 0, "avg_seconds", 0, "success_rate", 0,
                "current_quota", poolMetrics().get("current_quota"), "current_available", poolMetrics().get("current_available"),
                "updated_at", Instant.now().toString());
    }

    private Map<String, Object> poolMetrics() {
        int available = 0;
        int quota = 0;
        for (Map<String, Object> account : accounts.listAccounts()) {
            String status = AppConfigService.clean(account.get("status")).toLowerCase();
            if (!"disabled".equals(status) && !"abnormal".equals(status)) {
                available++;
                if (!AppConfigService.booleanValue(account.get("image_quota_unknown"), false)) {
                    quota += AppConfigService.intValue(account.get("quota"), 0, 0);
                }
            }
        }
        return map("current_quota", quota, "current_available", available);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats() {
        return (Map<String, Object>) config.get("stats");
    }

    private Map<String, Object> read() {
        if (!Files.isRegularFile(path)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return mapper.readValue(path.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException exception) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private void save() {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to save registration config", exception);
        }
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        return mapper.convertValue(source, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            enabled = false;
        }
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
