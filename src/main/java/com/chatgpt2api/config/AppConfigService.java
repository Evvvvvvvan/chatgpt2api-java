package com.chatgpt2api.config;

import com.chatgpt2api.common.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AppConfigService {
    private final ObjectMapper mapper;
    private final Path rootDir;
    private final Path dataDir;
    private final Path configFile;
    private final Path versionFile;
    private Map<String, Object> data;

    public AppConfigService(ObjectMapper mapper, @Value("${chatgpt2api.root-dir:.}") String rootDir) {
        this.mapper = mapper;
        this.rootDir = Paths.get(rootDir).toAbsolutePath().normalize();
        this.dataDir = this.rootDir.resolve("data");
        this.configFile = this.rootDir.resolve("config.json");
        this.versionFile = this.rootDir.resolve("VERSION");
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        Files.createDirectories(dataDir);
        data = readObject(configFile);
        if (getAuthKey().isEmpty()) {
            throw new IllegalStateException("auth-key 未配置，请设置 CHATGPT2API_AUTH_KEY 或 config.json 中的 auth-key");
        }
    }

    public Path getRootDir() {
        return rootDir;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public Path getImagesDir() {
        return ensureDirectory(dataDir.resolve("images"));
    }

    public Path getImageThumbnailsDir() {
        return ensureDirectory(dataDir.resolve("image_thumbnails"));
    }

    public synchronized String getAuthKey() {
        String environment = clean(System.getenv("CHATGPT2API_AUTH_KEY"));
        return environment.isEmpty() ? clean(data.get("auth-key")) : environment;
    }

    public String getBaseUrl() {
        String environment = clean(System.getenv("CHATGPT2API_BASE_URL"));
        return trimSlash(environment.isEmpty() ? clean(data.get("base_url")) : environment);
    }

    public synchronized int getRefreshAccountIntervalMinute() {
        return intValue(data.get("refresh_account_interval_minute"), 5, 0);
    }

    public synchronized int getImageRetentionDays() {
        return intValue(data.get("image_retention_days"), 30, 1);
    }

    public synchronized int getImageAccountConcurrency() {
        return intValue(data.get("image_account_concurrency"), 3, 1);
    }

    public synchronized boolean getAutoRemoveInvalidAccounts() {
        return booleanValue(data.get("auto_remove_invalid_accounts"), false);
    }

    public synchronized boolean getAutoRemoveRateLimitedAccounts() {
        return booleanValue(data.get("auto_remove_rate_limited_accounts"), false);
    }

    public synchronized String getProxySettings() {
        return clean(data.get("proxy"));
    }

    public String getAppVersion() {
        try {
            return clean(new String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return "0.0.0";
        }
    }

    public synchronized List<String> getSensitiveWords() {
        List<String> words = new ArrayList<String>();
        Object source = data.get("sensitive_words");
        if (source instanceof List) {
            for (Object item : (List<?>) source) {
                String word = clean(item);
                if (!word.isEmpty()) {
                    words.add(word);
                }
            }
        }
        return words;
    }

    public synchronized Map<String, Object> getImageStorageSettings() {
        return normalizeImageStorage(data.get("image_storage"));
    }

    public synchronized Map<String, Object> getBackupSettings() {
        return normalizeBackup(data.get("backup"));
    }

    public synchronized Map<String, Object> getPublicSettings() {
        Map<String, Object> result = deepCopy(data);
        result.remove("auth-key");
        result.put("refresh_account_interval_minute", getRefreshAccountIntervalMinute());
        result.put("image_retention_days", getImageRetentionDays());
        result.put("image_account_concurrency", getImageAccountConcurrency());
        result.put("auto_remove_invalid_accounts", getAutoRemoveInvalidAccounts());
        result.put("auto_remove_rate_limited_accounts", getAutoRemoveRateLimitedAccounts());
        result.put("sensitive_words", getSensitiveWords());
        result.put("backup", normalizeBackup(result.get("backup")));
        result.put("image_storage", normalizeImageStorage(result.get("image_storage")));
        return result;
    }

    public synchronized Map<String, Object> update(Map<String, Object> updates) {
        Map<String, Object> next = deepCopy(data);
        if (updates != null) {
            next.putAll(updates);
        }
        if (next.containsKey("backup")) {
            next.put("backup", normalizeBackup(next.get("backup")));
        }
        if (next.containsKey("image_storage")) {
            Map<String, Object> imageStorage = normalizeImageStorage(next.get("image_storage"));
            validateImageStorage(imageStorage);
            next.put("image_storage", imageStorage);
        }
        next.remove("backup_state");
        data = next;
        writeObject(configFile, data);
        return getPublicSettings();
    }

    private Map<String, Object> normalizeBackup(Object value) {
        Map<String, Object> source = value instanceof Map ? castMap(value) : new LinkedHashMap<String, Object>();
        Map<String, Object> includeSource = source.get("include") instanceof Map
                ? castMap(source.get("include")) : new LinkedHashMap<String, Object>();
        Map<String, Object> include = new LinkedHashMap<String, Object>();
        Map<String, Boolean> defaults = new LinkedHashMap<String, Boolean>();
        defaults.put("config", true);
        defaults.put("register", true);
        defaults.put("cpa", true);
        defaults.put("sub2api", true);
        defaults.put("logs", true);
        defaults.put("image_tasks", true);
        defaults.put("accounts_snapshot", true);
        defaults.put("auth_keys_snapshot", true);
        defaults.put("images", false);
        for (Map.Entry<String, Boolean> item : defaults.entrySet()) {
            include.put(item.getKey(), booleanValue(includeSource.get(item.getKey()), item.getValue()));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", booleanValue(source.get("enabled"), false));
        result.put("provider", "cloudflare_r2");
        result.put("account_id", clean(source.get("account_id")));
        result.put("access_key_id", clean(source.get("access_key_id")));
        result.put("secret_access_key", clean(source.get("secret_access_key")));
        result.put("bucket", clean(source.get("bucket")));
        String prefix = trimSlash(clean(source.get("prefix")));
        result.put("prefix", prefix.isEmpty() ? "backups" : prefix);
        result.put("interval_minutes", intValue(source.get("interval_minutes"), 360, 1));
        result.put("rotation_keep", intValue(source.get("rotation_keep"), 10, 0));
        result.put("encrypt", booleanValue(source.get("encrypt"), false));
        result.put("passphrase", clean(source.get("passphrase")));
        result.put("include", include);
        return result;
    }

    private Map<String, Object> normalizeImageStorage(Object value) {
        Map<String, Object> source = value instanceof Map ? castMap(value) : new LinkedHashMap<String, Object>();
        boolean enabled = booleanValue(source.get("enabled"), false);
        String mode = clean(source.get("mode")).toLowerCase();
        if (!Arrays.asList("local", "webdav", "both").contains(mode) || !enabled) {
            mode = "local";
        }
        String rootPath = trimSlash(clean(source.get("webdav_root_path")));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", enabled);
        result.put("mode", mode);
        result.put("webdav_url", trimSlash(clean(source.get("webdav_url"))));
        result.put("webdav_username", clean(source.get("webdav_username")));
        result.put("webdav_password", clean(source.get("webdav_password")));
        result.put("webdav_root_path", rootPath.isEmpty() ? "chatgpt2api/images" : rootPath);
        result.put("public_base_url", trimSlash(clean(source.get("public_base_url"))));
        return result;
    }

    private void validateImageStorage(Map<String, Object> settings) {
        if (!booleanValue(settings.get("enabled"), false)) {
            return;
        }
        if (clean(settings.get("webdav_url")).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "启用 WebDAV 图片存储后必须填写 WebDAV URL");
        }
        if (clean(settings.get("webdav_password")).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "启用 WebDAV 图片存储后必须填写 WebDAV 密码");
        }
    }

    private Path ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("unable to create data directory: " + path, exception);
        }
    }

    private Map<String, Object> readObject(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return mapper.readValue(path.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException exception) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private void writeObject(Path path, Map<String, Object> value) {
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes = (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to write config", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return new LinkedHashMap<String, Object>((Map<String, Object>) value);
    }

    private Map<String, Object> deepCopy(Map<String, Object> value) {
        return mapper.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    public static String clean(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String trimSlash(String value) {
        return value.replaceAll("^/+|/+$", "");
    }

    public static boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = clean(value).toLowerCase();
        if (Arrays.asList("1", "true", "yes", "on").contains(text)) {
            return true;
        }
        if (Arrays.asList("0", "false", "no", "off").contains(text)) {
            return false;
        }
        return defaultValue;
    }

    public static int intValue(Object value, int defaultValue, int minimum) {
        int result = defaultValue;
        try {
            if (value != null) {
                result = Integer.parseInt(clean(value));
            }
        } catch (NumberFormatException ignored) {
            result = defaultValue;
        }
        return Math.max(minimum, result);
    }
}
