package com.chatgpt2api.log;

import com.chatgpt2api.config.AppConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class LogService {
    private final ObjectMapper mapper;
    private final Path path;

    public LogService(ObjectMapper mapper, AppConfigService config) {
        this.mapper = mapper;
        this.path = config.getDataDir().resolve("logs.jsonl");
    }

    public synchronized void add(String type, String summary, Map<String, Object> detail) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", UUID.randomUUID().toString().replace("-", ""));
        item.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        item.put("type", type);
        item.put("summary", summary);
        item.put("detail", detail == null ? new LinkedHashMap<String, Object>() : detail);
        try {
            Files.createDirectories(path.getParent());
            String line = mapper.writeValueAsString(item) + "\n";
            Files.write(path, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to write log", exception);
        }
    }

    public synchronized List<Map<String, Object>> list(String type, String startDate, String endDate) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        List<String> lines = readLines();
        Collections.reverse(lines);
        int lineNumber = lines.size();
        for (String line : lines) {
            Map<String, Object> item = parse(line, lineNumber--);
            if (item == null || !matches(item, type, startDate, endDate)) {
                continue;
            }
            result.add(item);
            if (result.size() >= 200) {
                break;
            }
        }
        return result;
    }

    public synchronized Map<String, Integer> delete(List<String> ids) {
        Set<String> targets = new LinkedHashSet<String>();
        if (ids != null) {
            targets.addAll(ids);
        }
        List<String> kept = new ArrayList<String>();
        int removed = 0;
        List<String> lines = readLines();
        for (int index = 0; index < lines.size(); index++) {
            Map<String, Object> item = parse(lines.get(index), index);
            if (item != null && targets.contains(String.valueOf(item.get("id")))) {
                removed++;
            } else {
                kept.add(item == null ? lines.get(index) : serialize(item));
            }
        }
        try {
            Files.createDirectories(path.getParent());
            String body = String.join("\n", kept);
            if (!body.isEmpty()) {
                body += "\n";
            }
            Files.write(path, body.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to delete log", exception);
        }
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        result.put("removed", removed);
        return result;
    }

    private boolean matches(Map<String, Object> item, String type, String startDate, String endDate) {
        String date = String.valueOf(item.get("time")).substring(0, Math.min(10, String.valueOf(item.get("time")).length()));
        return (type.isEmpty() || type.equals(item.get("type")))
                && (startDate.isEmpty() || date.compareTo(startDate) >= 0)
                && (endDate.isEmpty() || date.compareTo(endDate) <= 0);
    }

    private List<String> readLines() {
        if (!Files.isRegularFile(path)) {
            return new ArrayList<String>();
        }
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return new ArrayList<String>();
        }
    }

    private Map<String, Object> parse(String value, int lineNumber) {
        try {
            Map<String, Object> item = mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
            if (!item.containsKey("id") || AppConfigService.clean(item.get("id")).isEmpty()) {
                item.put("id", sha1(lineNumber + ":" + value).substring(0, 24));
            }
            return item;
        } catch (IOException exception) {
            return null;
        }
    }

    private String serialize(Map<String, Object> item) {
        try {
            return mapper.writeValueAsString(item);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to serialize log", exception);
        }
    }

    private String sha1(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
