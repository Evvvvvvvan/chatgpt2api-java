package com.chatgpt2api.image;

import com.chatgpt2api.config.AppConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class ImageTagService {
    private final ObjectMapper mapper;
    private final Path tagsFile;

    public ImageTagService(ObjectMapper mapper, AppConfigService config) {
        this.mapper = mapper;
        this.tagsFile = config.getDataDir().resolve("image_tags.json");
    }

    public synchronized Map<String, List<String>> loadTags() {
        if (!Files.isRegularFile(tagsFile)) {
            return new LinkedHashMap<String, List<String>>();
        }
        try {
            return mapper.readValue(tagsFile.toFile(), new TypeReference<LinkedHashMap<String, List<String>>>() { });
        } catch (IOException exception) {
            return new LinkedHashMap<String, List<String>>();
        }
    }

    public synchronized List<String> setTags(String imagePath, List<String> tags) {
        Map<String, List<String>> data = loadTags();
        List<String> cleaned = new ArrayList<String>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String tag : tags == null ? new ArrayList<String>() : tags) {
            String value = AppConfigService.clean(tag);
            if (!value.isEmpty() && seen.add(value)) {
                cleaned.add(value);
            }
        }
        if (cleaned.isEmpty()) {
            data.remove(imagePath);
        } else {
            data.put(imagePath, cleaned);
        }
        save(data);
        return cleaned;
    }

    public synchronized void removeTags(String imagePath) {
        Map<String, List<String>> data = loadTags();
        if (data.remove(imagePath) != null) {
            save(data);
        }
    }

    public synchronized int deleteTag(String tag) {
        Map<String, List<String>> data = loadTags();
        int removed = 0;
        for (String path : new ArrayList<String>(data.keySet())) {
            List<String> existing = data.get(path);
            if (existing != null && existing.remove(tag)) {
                removed++;
                if (existing.isEmpty()) {
                    data.remove(path);
                }
            }
        }
        if (removed > 0) {
            save(data);
        }
        return removed;
    }

    public synchronized List<String> getAllTags() {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        for (List<String> tags : loadTags().values()) {
            result.addAll(tags);
        }
        return new ArrayList<String>(result);
    }

    private void save(Map<String, List<String>> value) {
        try {
            Files.createDirectories(tagsFile.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
            Files.write(tagsFile, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to persist image tags", exception);
        }
    }
}
