package com.chatgpt2api.backup;

import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.storage.StorageBackend;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BackupService {
    private final AppConfigService config;
    private final StorageBackend storage;
    private final ObjectMapper mapper;
    private final Path stateFile;
    private volatile boolean running;

    public BackupService(AppConfigService config, StorageBackend storage, ObjectMapper mapper) {
        this.config = config;
        this.storage = storage;
        this.mapper = mapper;
        this.stateFile = config.getDataDir().resolve("backup_state.json");
    }

    public Map<String, Object> testConnection() {
        return client().testConnection();
    }

    public List<Map<String, Object>> listBackups() {
        if (!configured()) {
            return new ArrayList<Map<String, Object>>();
        }
        return client().listObjects();
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> state = readState();
        state.put("running", running);
        return state;
    }

    public Map<String, Object> getSettings() {
        Map<String, Object> settings = config.getBackupSettings();
        if (!AppConfigService.clean(settings.get("secret_access_key")).isEmpty()) {
            settings.put("secret_access_key", "********");
        }
        if (!AppConfigService.clean(settings.get("passphrase")).isEmpty()) {
            settings.put("passphrase", "********");
        }
        return settings;
    }

    public synchronized Map<String, Object> runBackup() {
        if (running) {
            throw new IllegalStateException("当前已有备份任务正在执行");
        }
        Map<String, Object> current = readState();
        String started = now();
        running = true;
        writeState(map("last_started_at", started, "last_finished_at", current.get("last_finished_at"),
                "last_status", "idle", "last_error", null, "last_object_key", current.get("last_object_key")));
        try {
            Map<String, Object> settings = config.getBackupSettings();
            byte[] payload = archive(settings, "manual");
            boolean encrypted = AppConfigService.booleanValue(settings.get("encrypt"), false);
            if (encrypted) {
                String passphrase = AppConfigService.clean(settings.get("passphrase"));
                if (passphrase.isEmpty()) {
                    throw new IllegalStateException("已启用备份加密，但未设置加密口令");
                }
                payload = openssl(payload, passphrase, false);
            }
            R2Client client = client();
            String suffix = encrypted ? ".tar.gz.enc" : ".tar.gz";
            String key = client.prefix + "/backup-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC).format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 4) + suffix;
            client.put(key, payload);
            rotate(client, AppConfigService.intValue(settings.get("rotation_keep"), 10, 0));
            Map<String, Object> result = map("key", key, "size", payload.length, "encrypted", encrypted);
            writeState(map("last_started_at", started, "last_finished_at", now(), "last_status", "success",
                    "last_error", null, "last_object_key", key));
            return result;
        } catch (RuntimeException exception) {
            writeState(map("last_started_at", started, "last_finished_at", now(), "last_status", "error",
                    "last_error", exception.getMessage(), "last_object_key", current.get("last_object_key")));
            throw exception;
        } finally {
            running = false;
        }
    }

    public void delete(String key) {
        requireKey(key);
        client().delete(key);
    }

    public Map<String, Object> detail(String key) {
        requireKey(key);
        byte[] payload = decodedPayload(key);
        Map<String, Object> detail = archiveDetail(payload);
        detail.put("key", key);
        detail.put("name", fileName(key));
        detail.put("encrypted", key.endsWith(".enc"));
        return detail;
    }

    public Map<String, Object> download(String key) {
        requireKey(key);
        byte[] payload = decodedPayload(key);
        String name = fileName(key);
        if (name.endsWith(".enc")) {
            name = name.substring(0, name.length() - 4);
        }
        return map("key", key, "name", name, "content_type", "application/gzip", "payload", payload, "size", payload.length);
    }

    private boolean configured() {
        Map<String, Object> settings = config.getBackupSettings();
        return !AppConfigService.clean(settings.get("account_id")).isEmpty()
                && !AppConfigService.clean(settings.get("access_key_id")).isEmpty()
                && !AppConfigService.clean(settings.get("secret_access_key")).isEmpty()
                && !AppConfigService.clean(settings.get("bucket")).isEmpty();
    }

    private R2Client client() {
        if (!configured()) {
            throw new IllegalStateException("R2 配置不完整");
        }
        return new R2Client(config.getBackupSettings());
    }

    private byte[] decodedPayload(String key) {
        byte[] payload = client().get(key);
        if (!key.endsWith(".enc")) {
            return payload;
        }
        String passphrase = AppConfigService.clean(config.getBackupSettings().get("passphrase"));
        if (passphrase.isEmpty()) {
            throw new IllegalStateException("当前未配置加密口令，无法读取已加密备份");
        }
        return openssl(payload, passphrase, true);
    }

    private void rotate(R2Client client, int keep) {
        if (keep <= 0) {
            return;
        }
        List<Map<String, Object>> items = client.listObjects();
        for (int index = keep; index < items.size(); index++) {
            client.delete(AppConfigService.clean(items.get(index).get("key")));
        }
    }

    private byte[] archive(Map<String, Object> settings, String trigger) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes));
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            add(tar, "backup-metadata.json", json(map("version", 2, "created_at", now(), "trigger", trigger,
                    "app_version", config.getAppVersion(), "storage_backend", storage.getBackendInfo())));
            Map<String, Object> include = asMap(settings.get("include"));
            addIfEnabled(tar, include, "config", config.getRootDir().resolve("config.json"), "config.json");
            addIfEnabled(tar, include, "register", config.getDataDir().resolve("register.json"), "data/register.json");
            addIfEnabled(tar, include, "cpa", config.getDataDir().resolve("cpa_config.json"), "data/cpa_config.json");
            addIfEnabled(tar, include, "sub2api", config.getDataDir().resolve("sub2api_config.json"), "data/sub2api_config.json");
            addIfEnabled(tar, include, "logs", config.getDataDir().resolve("logs.jsonl"), "data/logs.jsonl");
            addIfEnabled(tar, include, "image_tasks", config.getDataDir().resolve("image_tasks.json"), "data/image_tasks.json");
            addIfEnabled(tar, include, "image_tasks", config.getDataDir().resolve("image_index.json"), "data/image_index.json");
            if (AppConfigService.booleanValue(include.get("accounts_snapshot"), true)) {
                add(tar, "snapshots/accounts.json", json(storage.loadAccounts()));
            }
            if (AppConfigService.booleanValue(include.get("auth_keys_snapshot"), true)) {
                add(tar, "snapshots/auth_keys.json", json(storage.loadAuthKeys()));
            }
            if (AppConfigService.booleanValue(include.get("images"), false)) {
                addFile(tar, config.getDataDir().resolve("image_tags.json"), "data/image_tags.json");
                for (Path file : imageFiles()) {
                    addFile(tar, file, "data/images/" + config.getImagesDir().relativize(file).toString().replace('\\', '/'));
                }
            }
            tar.finish();
            tar.close();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("生成备份压缩包失败", exception);
        }
    }

    private Map<String, Object> archiveDetail(byte[] payload) {
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> snapshots = new ArrayList<Map<String, Object>>();
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        try {
            TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(payload)));
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (!entry.isFile()) {
                    continue;
                }
                byte[] data = readAll(tar);
                String name = entry.getName();
                if ("backup-metadata.json".equals(name)) {
                    metadata = mapper.readValue(data, new TypeReference<LinkedHashMap<String, Object>>() { });
                } else if (name.startsWith("snapshots/") && name.endsWith(".json")) {
                    Object value = mapper.readValue(data, Object.class);
                    int count = value instanceof List ? ((List<?>) value).size() : value instanceof Map ? ((Map<?, ?>) value).size() : 0;
                    snapshots.add(map("name", name.substring(10, name.length() - 5), "count", count));
                } else {
                    files.add(map("name", name, "exists", true, "content_type", contentType(name),
                            "size", data.length, "sha256", sha256(data)));
                }
            }
            tar.close();
        } catch (IOException exception) {
            throw new IllegalStateException("解析备份压缩包失败，备份可能已损坏", exception);
        }
        return map("created_at", metadata.get("created_at"), "trigger", metadata.get("trigger"),
                "app_version", metadata.get("app_version"), "storage_backend", metadata.get("storage_backend"),
                "files", files, "snapshots", snapshots);
    }

    private void addIfEnabled(TarArchiveOutputStream tar, Map<String, Object> include, String key, Path source, String name) throws IOException {
        if (AppConfigService.booleanValue(include.get(key), true)) {
            addFile(tar, source, name);
        }
    }

    private void addFile(TarArchiveOutputStream tar, Path source, String name) throws IOException {
        if (Files.isRegularFile(source)) {
            add(tar, name, Files.readAllBytes(source));
        }
    }

    private void add(TarArchiveOutputStream tar, String name, byte[] payload) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(payload.length);
        entry.setModTime(System.currentTimeMillis());
        tar.putArchiveEntry(entry);
        tar.write(payload);
        tar.closeArchiveEntry();
    }

    private List<Path> imageFiles() throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.walk(config.getImagesDir())) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        return files;
    }

    private byte[] openssl(byte[] payload, String passphrase, boolean decrypt) {
        Path input = config.getDataDir().resolve(".backup-" + UUID.randomUUID().toString() + ".in");
        Path output = config.getDataDir().resolve(".backup-" + UUID.randomUUID().toString() + ".out");
        try {
            Files.write(input, payload, StandardOpenOption.CREATE_NEW);
            List<String> command = new ArrayList<String>();
            command.add("openssl");
            command.add("enc");
            if (decrypt) {
                command.add("-d");
            }
            command.add("-aes-256-cbc");
            command.add("-pbkdf2");
            if (!decrypt) {
                command.add("-salt");
            }
            command.add("-md");
            command.add("sha256");
            command.add("-pass");
            command.add("env:CHATGPT2API_BACKUP_PASSPHRASE");
            command.add("-in");
            command.add(input.toString());
            command.add("-out");
            command.add(output.toString());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("CHATGPT2API_BACKUP_PASSPHRASE", passphrase);
            Process process = builder.start();
            if (process.waitFor() != 0) {
                throw new IllegalStateException(decrypt ? "解密备份失败" : "加密备份失败");
            }
            return Files.readAllBytes(output);
        } catch (IOException exception) {
            throw new IllegalStateException("当前环境缺少 openssl，无法执行加密备份", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("备份加密任务已中断", exception);
        } finally {
            try {
                Files.deleteIfExists(input);
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响备份结果。
            }
        }
    }

    private Map<String, Object> readState() {
        Map<String, Object> state = map("last_started_at", null, "last_finished_at", null, "last_status", "idle",
                "last_error", null, "last_object_key", null);
        if (Files.isRegularFile(stateFile)) {
            try {
                state.putAll(mapper.readValue(stateFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { }));
            } catch (IOException ignored) {
                return state;
            }
        }
        return state;
    }

    private void writeState(Map<String, Object> state) {
        try {
            Files.createDirectories(stateFile.getParent());
            Files.write(stateFile, (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存备份状态", exception);
        }
    }

    private byte[] json(Object value) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    private byte[] readAll(java.io.InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
            if (input instanceof TarArchiveInputStream && ((TarArchiveInputStream) input).getBytesRead() >= Long.MAX_VALUE) {
                break;
            }
        }
        return output.toByteArray();
    }

    private String contentType(String name) {
        return name.endsWith(".json") ? "application/json" : name.endsWith(".jsonl") ? "application/x-ndjson" : "application/octet-stream";
    }

    private void requireKey(String key) {
        if (AppConfigService.clean(key).isEmpty()) {
            throw new IllegalStateException("备份对象 key 不能为空");
        }
    }

    private String fileName(String key) {
        return key.substring(key.lastIndexOf('/') + 1);
    }

    private String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value) : new LinkedHashMap<String, Object>();
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private String sha256(byte[] value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private class R2Client {
        private final String accountId;
        private final String accessKey;
        private final String secretKey;
        private final String bucket;
        private final String prefix;

        private R2Client(Map<String, Object> settings) {
            this.accountId = AppConfigService.clean(settings.get("account_id"));
            this.accessKey = AppConfigService.clean(settings.get("access_key_id"));
            this.secretKey = AppConfigService.clean(settings.get("secret_access_key"));
            this.bucket = AppConfigService.clean(settings.get("bucket"));
            this.prefix = AppConfigService.clean(settings.get("prefix")).isEmpty() ? "backups" : AppConfigService.clean(settings.get("prefix"));
        }

        private Map<String, Object> testConnection() {
            request("GET", "", map("list-type", "2", "max-keys", "1"), new byte[0], null);
            return map("ok", true, "status", 200);
        }

        private void put(String key, byte[] payload) {
            request("PUT", key, null, payload, "application/octet-stream");
        }

        private byte[] get(String key) {
            return request("GET", key, null, new byte[0], null);
        }

        private void delete(String key) {
            request("DELETE", key, null, new byte[0], null);
        }

        private List<Map<String, Object>> listObjects() {
            byte[] payload = request("GET", "", map("list-type", "2", "prefix", prefix + "/", "max-keys", "1000"), new byte[0], null);
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            try {
                Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(payload));
                NodeList nodes = document.getElementsByTagName("Contents");
                for (int index = 0; index < nodes.getLength(); index++) {
                    Element item = (Element) nodes.item(index);
                    String key = text(item, "Key");
                    String name = fileName(key);
                    if (!name.startsWith("backup-") || !(name.endsWith(".tar.gz") || name.endsWith(".tar.gz.enc"))) {
                        continue;
                    }
                    result.add(map("key", key, "name", name, "size", Long.parseLong(text(item, "Size")),
                            "updated_at", text(item, "LastModified"), "encrypted", name.endsWith(".enc")));
                }
            } catch (Exception exception) {
                throw new IllegalStateException("读取备份列表失败", exception);
            }
            Collections.sort(result, new Comparator<Map<String, Object>>() {
                @Override
                public int compare(Map<String, Object> first, Map<String, Object> second) {
                    return AppConfigService.clean(second.get("updated_at")).compareTo(AppConfigService.clean(first.get("updated_at")));
                }
            });
            return result;
        }

        private byte[] request(String method, String key, Map<String, Object> query, byte[] payload, String contentType) {
            try {
                String path = "/" + bucket + (key.isEmpty() ? "" : "/" + encodePath(key));
                String queryString = query(query);
                String target = "https://" + accountId + ".r2.cloudflarestorage.com" + path + (queryString.isEmpty() ? "" : "?" + queryString);
                Map<String, String> headers = signedHeaders(method, path, queryString, payload);
                HttpURLConnection connection = (HttpURLConnection) URI.create(target).toURL().openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
                if (contentType != null) {
                    connection.setRequestProperty("Content-Type", contentType);
                }
                if (payload.length > 0) {
                    connection.setDoOutput(true);
                    connection.getOutputStream().write(payload);
                }
                int status = connection.getResponseCode();
                if (status >= 400 && !("DELETE".equals(method) && status == 404)) {
                    throw new IllegalStateException("R2 " + method + " 失败：HTTP " + status);
                }
                return status == 204 ? new byte[0] : readAll(connection.getInputStream());
            } catch (IOException exception) {
                throw new IllegalStateException("R2 请求失败：" + exception.getMessage(), exception);
            }
        }

        private Map<String, String> signedHeaders(String method, String path, String query, byte[] payload) {
            String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(Instant.now());
            String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
            String host = accountId + ".r2.cloudflarestorage.com";
            String hash = sha256(payload);
            String canonical = "host:" + host + "\n" + "x-amz-content-sha256:" + hash + "\n" + "x-amz-date:" + amzDate + "\n";
            String signed = "host;x-amz-content-sha256;x-amz-date";
            String request = method + "\n" + path + "\n" + query + "\n" + canonical + "\n" + signed + "\n" + hash;
            String scope = date + "/auto/s3/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256(request.getBytes(StandardCharsets.UTF_8));
            byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
            key = hmac(key, "auto");
            key = hmac(key, "s3");
            key = hmac(key, "aws4_request");
            String signature = hex(hmac(key, stringToSign));
            return stringMap("host", host, "x-amz-content-sha256", hash, "x-amz-date", amzDate,
                    "authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + signature);
        }

        private String query(Map<String, Object> values) {
            if (values == null) {
                return "";
            }
            List<String> keys = new ArrayList<String>(values.keySet());
            Collections.sort(keys);
            List<String> pairs = new ArrayList<String>();
            for (String key : keys) {
                pairs.add(encode(key) + "=" + encode(String.valueOf(values.get(key))));
            }
            return String.join("&", pairs);
        }

        private String encodePath(String value) {
            String[] parts = value.split("/");
            List<String> encoded = new ArrayList<String>();
            for (String part : parts) {
                encoded.add(encode(part));
            }
            return String.join("/", encoded);
        }

        private String encode(String value) {
            try {
                return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~");
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private String text(Element element, String name) {
            NodeList nodes = element.getElementsByTagName(name);
            return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
        }

        private Map<String, String> stringMap(String... values) {
            Map<String, String> result = new LinkedHashMap<String, String>();
            for (int index = 0; index < values.length; index += 2) {
                result.put(values[index], values[index + 1]);
            }
            return result;
        }

        private byte[] hmac(byte[] key, String message) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private String hex(byte[] value) {
            StringBuilder result = new StringBuilder();
            for (byte item : value) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        }
    }
}
