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

public class GitStorageBackend implements StorageBackend {
    private final ObjectMapper mapper;
    private final String repoUrl;
    private final String token;
    private final String branch;
    private final String accountsPath;
    private final String authKeysPath;
    private final Path cacheDir;

    public GitStorageBackend(
            ObjectMapper mapper,
            String repoUrl,
            String token,
            String branch,
            String accountsPath,
            String authKeysPath,
            Path cacheDir) {
        this.mapper = mapper;
        this.repoUrl = repoUrl;
        this.token = token;
        this.branch = branch;
        this.accountsPath = accountsPath;
        this.authKeysPath = authKeysPath;
        this.cacheDir = cacheDir.resolve("repo");
    }

    @Override
    public synchronized List<Map<String, Object>> loadAccounts() {
        return readList(accountsPath, false);
    }

    @Override
    public synchronized void saveAccounts(List<Map<String, Object>> accounts) {
        write(accountsPath, accounts, "Update accounts data");
    }

    @Override
    public synchronized List<Map<String, Object>> loadAuthKeys() {
        return readList(authKeysPath, true);
    }

    @Override
    public synchronized void saveAuthKeys(List<Map<String, Object>> authKeys) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("items", authKeys);
        write(authKeysPath, value, "Update auth keys data");
    }

    @Override
    public Map<String, Object> healthCheck() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try {
            synchronizeRepository();
            result.put("status", "healthy");
            result.put("backend", "git");
            result.put("repo_url", maskToken(repoUrl));
            result.put("branch", branch);
            result.put("file_path", accountsPath);
            result.put("auth_keys_file_path", authKeysPath);
            result.put("last_commit", output(cacheDir, "rev-parse", "--short=8", "HEAD").trim());
        } catch (RuntimeException exception) {
            result.put("status", "unhealthy");
            result.put("backend", "git");
            result.put("error", exception.getMessage());
        }
        return result;
    }

    @Override
    public Map<String, Object> getBackendInfo() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", "git");
        result.put("description", "Git 私有仓库存储");
        result.put("repo_url", maskToken(repoUrl));
        result.put("branch", branch);
        result.put("file_path", accountsPath);
        result.put("auth_keys_file_path", authKeysPath);
        return result;
    }

    private List<Map<String, Object>> readList(String file, boolean wrapped) {
        synchronizeRepository();
        Path path = cacheDir.resolve(file).normalize();
        if (!path.startsWith(cacheDir) || !Files.isRegularFile(path)) {
            return new ArrayList<Map<String, Object>>();
        }
        try {
            Object value = mapper.readValue(path.toFile(), Object.class);
            if (wrapped && value instanceof Map) {
                value = ((Map<?, ?>) value).get("items");
            }
            return value instanceof List
                    ? mapper.convertValue(value, new TypeReference<ArrayList<Map<String, Object>>>() { })
                    : new ArrayList<Map<String, Object>>();
        } catch (IOException exception) {
            throw new IllegalStateException("unable to read git storage", exception);
        }
    }

    private void write(String file, Object value, String message) {
        synchronizeRepository();
        Path path = cacheDir.resolve(file).normalize();
        if (!path.startsWith(cacheDir)) {
            throw new IllegalArgumentException("invalid git storage path");
        }
        try {
            Files.createDirectories(path.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
            Files.write(path, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to write git storage", exception);
        }
        run(cacheDir, "add", file);
        if (!output(cacheDir, "status", "--porcelain").trim().isEmpty()) {
            run(cacheDir, "commit", "-m", message);
            run(cacheDir, "push", "origin", branch);
        }
    }

    private void synchronizeRepository() {
        if (Files.isDirectory(cacheDir.resolve(".git"))) {
            run(cacheDir, "pull", "origin", branch);
            return;
        }
        try {
            Files.createDirectories(cacheDir.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("unable to prepare git storage", exception);
        }
        run(cacheDir.getParent(), "clone", "--branch", branch, authenticatedUrl(), cacheDir.getFileName().toString());
    }

    private String authenticatedUrl() {
        if (token.isEmpty()) {
            return repoUrl;
        }
        if (repoUrl.startsWith("https://")) {
            return repoUrl.replace("https://", "https://" + token + "@");
        }
        if (repoUrl.startsWith("git@") && repoUrl.contains(":")) {
            return "https://" + token + "@" + repoUrl.substring(4).replace(':', '/');
        }
        return repoUrl;
    }

    private void run(Path directory, String... arguments) {
        String result = output(directory, arguments);
        if (result == null) {
            throw new IllegalStateException("git command failed");
        }
    }

    private String output(Path directory, String... arguments) {
        List<String> command = new ArrayList<String>();
        command.add("git");
        for (String argument : arguments) {
            command.add(argument);
        }
        try {
            Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            byte[] bytes = readBytes(process);
            int exit = process.waitFor();
            String output = new String(bytes, StandardCharsets.UTF_8);
            if (exit != 0) {
                throw new IllegalStateException(output.trim());
            }
            return output;
        } catch (Exception exception) {
            throw new IllegalStateException("git storage command failed: " + exception.getMessage(), exception);
        }
    }

    private byte[] readBytes(Process process) throws IOException {
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = process.getInputStream().read(buffer)) != -1) {
            result.write(buffer, 0, read);
        }
        return result.toByteArray();
    }

    private String maskToken(String value) {
        return value.replaceFirst("://[^@]+@", "://****@");
    }
}
