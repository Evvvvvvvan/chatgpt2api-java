package com.chatgpt2api.image;

import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.log.LogService;
import com.chatgpt2api.protocol.AiProtocolService;
import com.chatgpt2api.protocol.ChatgptWebService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ImageTaskService {
    private static final String QUEUED = "queued";
    private static final String RUNNING = "running";
    private static final String SUCCESS = "success";
    private static final String ERROR = "error";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ObjectMapper mapper;
    private final AppConfigService config;
    private final AiProtocolService protocols;
    private final LogService logs;
    private final Path path;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private Map<String, Map<String, Object>> tasks = new LinkedHashMap<String, Map<String, Object>>();

    public ImageTaskService(ObjectMapper mapper, AppConfigService config, AiProtocolService protocols, LogService logs) {
        this.mapper = mapper;
        this.config = config;
        this.protocols = protocols;
        this.logs = logs;
        this.path = config.getDataDir().resolve("image_tasks.json");
    }

    @PostConstruct
    public synchronized void initialize() {
        tasks = load();
        boolean changed = false;
        for (Map<String, Object> task : tasks.values()) {
            if (QUEUED.equals(task.get("status")) || RUNNING.equals(task.get("status"))) {
                task.put("status", ERROR);
                task.put("error", "service restarted before image task completed");
                task.put("updated_at", now());
                changed = true;
            }
        }
        if (cleanup() || changed) {
            save();
        }
    }

    public Map<String, Object> submitGeneration(Map<String, Object> identity, Map<String, Object> body, String baseUrl) {
        return submit(identity, body, baseUrl, "generate", null);
    }

    public Map<String, Object> submitEdit(Map<String, Object> identity, Map<String, Object> body, String baseUrl,
                                          List<ChatgptWebService.ImageInput> images) {
        return submit(identity, body, baseUrl, "edit", images);
    }

    public synchronized Map<String, Object> list(Map<String, Object> identity, String ids) {
        cleanupAndSave();
        String owner = owner(identity);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        List<String> missing = new ArrayList<String>();
        if (ids != null && !ids.trim().isEmpty()) {
            for (String raw : ids.split(",")) {
                String taskId = raw.trim();
                if (taskId.isEmpty()) {
                    continue;
                }
                Map<String, Object> task = tasks.get(key(owner, taskId));
                if (task == null) {
                    missing.add(taskId);
                } else {
                    items.add(publicTask(task));
                }
            }
        } else {
            for (Map<String, Object> task : tasks.values()) {
                if (owner.equals(task.get("owner_id"))) {
                    items.add(publicTask(task));
                }
            }
            Collections.sort(items, Comparator.comparing(value -> AppConfigService.clean(value.get("updated_at")), Comparator.reverseOrder()));
        }
        return map("items", items, "missing_ids", missing);
    }

    private Map<String, Object> submit(Map<String, Object> identity, Map<String, Object> body, String baseUrl,
                                       String mode, List<ChatgptWebService.ImageInput> images) {
        String taskId = AppConfigService.clean(body.get("client_task_id"));
        if (taskId.isEmpty()) {
            throw new IllegalArgumentException("client_task_id is required");
        }
        String owner = owner(identity);
        String taskKey = key(owner, taskId);
        Map<String, Object> task;
        synchronized (this) {
            cleanupAndSave();
            if (tasks.containsKey(taskKey)) {
                return publicTask(tasks.get(taskKey));
            }
            task = map("id", taskId, "owner_id", owner, "status", QUEUED, "mode", mode,
                    "model", value(body.get("model"), "gpt-image-2"), "size", AppConfigService.clean(body.get("size")),
                    "quality", value(body.get("quality"), "auto"), "created_at", now(), "updated_at", now());
            tasks.put(taskKey, task);
            save();
        }
        final List<ChatgptWebService.ImageInput> taskImages = images;
        executor.submit(() -> run(taskKey, identity, body, baseUrl, mode, taskImages));
        return publicTask(task);
    }

    private void run(String taskKey, Map<String, Object> identity, Map<String, Object> body, String baseUrl,
                     String mode, List<ChatgptWebService.ImageInput> images) {
        update(taskKey, map("status", RUNNING, "error", null));
        try {
            Map<String, Object> request = new LinkedHashMap<String, Object>(body);
            request.put("n", 1);
            request.put("response_format", "url");
            Map<String, Object> response = "edit".equals(mode) ? protocols.editImages(request, baseUrl, images)
                    : protocols.generateImages(request, baseUrl);
            List<?> data = response.get("data") instanceof List ? (List<?>) response.get("data") : new ArrayList<Object>();
            if (data.isEmpty()) {
                throw new IllegalStateException(value(response.get("message"), "image generation returned no data"));
            }
            update(taskKey, map("status", SUCCESS, "data", data, "error", null));
            logs.add("call", "image task completed", map("endpoint", "edit".equals(mode) ? "/v1/images/edits" : "/v1/images/generations",
                    "model", body.get("model"), "key_id", identity.get("id"), "status", SUCCESS));
        } catch (RuntimeException exception) {
            update(taskKey, map("status", ERROR, "data", new ArrayList<Object>(), "error", exception.getMessage()));
            logs.add("call", "image task failed", map("model", body.get("model"), "key_id", identity.get("id"),
                    "status", ERROR, "error", exception.getMessage()));
        }
    }

    private synchronized void update(String key, Map<String, Object> updates) {
        Map<String, Object> task = tasks.get(key);
        if (task == null) {
            return;
        }
        task.putAll(updates);
        task.put("updated_at", now());
        save();
    }

    private Map<String, Map<String, Object>> load() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        if (!Files.isRegularFile(path)) {
            return result;
        }
        try {
            Map<String, Object> stored = mapper.readValue(path.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
            Object raw = stored.get("tasks");
            if (raw instanceof List) {
                for (Object value : (List<?>) raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value) : null;
                    if (item != null && !AppConfigService.clean(item.get("id")).isEmpty() && !AppConfigService.clean(item.get("owner_id")).isEmpty()) {
                        result.put(key(AppConfigService.clean(item.get("owner_id")), AppConfigService.clean(item.get("id"))), item);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    private synchronized void save() {
        try {
            Files.createDirectories(path.getParent());
            String content = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(map("tasks", new ArrayList<Map<String, Object>>(tasks.values()))) + "\n";
            Files.write(path, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to save image tasks", exception);
        }
    }

    private boolean cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(config.getImageRetentionDays());
        List<String> removed = new ArrayList<String>();
        for (Map.Entry<String, Map<String, Object>> entry : tasks.entrySet()) {
            String status = AppConfigService.clean(entry.getValue().get("status"));
            if ((SUCCESS.equals(status) || ERROR.equals(status)) && timestamp(entry.getValue().get("updated_at")).isBefore(cutoff)) {
                removed.add(entry.getKey());
            }
        }
        for (String key : removed) {
            tasks.remove(key);
        }
        return !removed.isEmpty();
    }

    private void cleanupAndSave() {
        if (cleanup()) {
            save();
        }
    }

    private Map<String, Object> publicTask(Map<String, Object> task) {
        Map<String, Object> result = map("id", task.get("id"), "status", task.get("status"), "mode", task.get("mode"),
                "model", task.get("model"), "size", task.get("size"), "quality", task.get("quality"),
                "created_at", task.get("created_at"), "updated_at", task.get("updated_at"));
        if (task.containsKey("data")) {
            result.put("data", task.get("data"));
        }
        if (!AppConfigService.clean(task.get("error")).isEmpty()) {
            result.put("error", task.get("error"));
        }
        return result;
    }

    private LocalDateTime timestamp(Object value) {
        try {
            return LocalDateTime.parse(AppConfigService.clean(value), TIME);
        } catch (Exception exception) {
            return LocalDateTime.now();
        }
    }

    private String owner(Map<String, Object> identity) {
        return value(identity.get("id"), "anonymous");
    }

    private String key(String owner, String taskId) {
        return owner + ":" + taskId;
    }

    private String now() {
        return LocalDateTime.now().format(TIME);
    }

    private String value(Object source, String fallback) {
        String result = AppConfigService.clean(source);
        return result.isEmpty() ? fallback : result;
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
