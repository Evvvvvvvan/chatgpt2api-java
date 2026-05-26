package com.chatgpt2api.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonStorageBackend implements StorageBackend {
    private final ObjectMapper mapper;
    private final Path accountsFile;
    private final Path authKeysFile;

    public JsonStorageBackend(ObjectMapper mapper, Path accountsFile, Path authKeysFile) {
        this.mapper = mapper;
        this.accountsFile = accountsFile;
        this.authKeysFile = authKeysFile;
    }

    @Override
    public synchronized List<Map<String, Object>> loadAccounts() {
        return readList(accountsFile, false);
    }

    @Override
    public synchronized void saveAccounts(List<Map<String, Object>> accounts) {
        write(accountsFile, accounts);
    }

    @Override
    public synchronized List<Map<String, Object>> loadAuthKeys() {
        return readList(authKeysFile, true);
    }

    @Override
    public synchronized void saveAuthKeys(List<Map<String, Object>> authKeys) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("items", authKeys);
        write(authKeysFile, body);
    }

    @Override
    public Map<String, Object> healthCheck() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "healthy");
        result.put("backend", "json");
        result.put("file_exists", Files.exists(accountsFile));
        result.put("file_path", accountsFile.toString());
        result.put("auth_keys_file_exists", Files.exists(authKeysFile));
        result.put("auth_keys_file_path", authKeysFile.toString());
        return result;
    }

    @Override
    public Map<String, Object> getBackendInfo() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", "json");
        result.put("description", "本地 JSON 文件存储");
        result.put("file_path", accountsFile.toString());
        result.put("file_exists", Files.exists(accountsFile));
        result.put("auth_keys_file_path", authKeysFile.toString());
        result.put("auth_keys_file_exists", Files.exists(authKeysFile));
        return result;
    }

    private List<Map<String, Object>> readList(Path path, boolean wrapped) {
        if (!Files.isRegularFile(path)) {
            return new ArrayList<Map<String, Object>>();
        }
        try {
            Object value = mapper.readValue(path.toFile(), Object.class);
            if (wrapped && value instanceof Map) {
                value = ((Map<?, ?>) value).get("items");
            }
            if (!(value instanceof List)) {
                return new ArrayList<Map<String, Object>>();
            }
            return mapper.convertValue(value, new TypeReference<ArrayList<Map<String, Object>>>() { });
        } catch (IOException exception) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    private void write(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            String text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
            Files.write(path, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to persist JSON storage", exception);
        }
    }
}
