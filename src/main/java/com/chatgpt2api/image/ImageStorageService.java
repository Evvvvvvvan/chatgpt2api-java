package com.chatgpt2api.image;

import com.chatgpt2api.common.ApiException;
import com.chatgpt2api.config.AppConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ImageStorageService {
    private final AppConfigService config;
    private final ObjectMapper mapper;
    private final Path indexFile;

    public ImageStorageService(AppConfigService config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.indexFile = config.getDataDir().resolve("image_index.json");
    }

    public synchronized Map<String, Object> save(byte[] payload, String baseUrl) {
        String relative = relativePath(payload);
        Map<String, Object> settings = config.getImageStorageSettings();
        String mode = AppConfigService.clean(settings.get("mode"));
        boolean local = "local".equals(mode) || "both".equals(mode);
        boolean webdav = "webdav".equals(mode) || "both".equals(mode);
        String remoteUrl = "";
        try {
            if (local) {
                Path file = localPath(relative);
                Files.createDirectories(file.getParent());
                Files.write(file, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            if (webdav) {
                remoteUrl = new WebDavClient(settings).put(relative, payload, "image/png");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("unable to store image", exception);
        }
        Map<String, Object> item = item(relative, payload.length, local, webdav, remoteUrl);
        Map<String, Map<String, Object>> index = loadIndex();
        index.put(relative, item);
        saveIndex(index);
        item.put("url", publicUrl(relative, baseUrl));
        return item;
    }

    public synchronized List<Map<String, Object>> listItems(String baseUrl, String startDate, String endDate) {
        Map<String, Map<String, Object>> index = loadIndex();
        boolean changed = false;
        for (Path file : localImageFiles()) {
            String relative = config.getImagesDir().relativize(file).toString().replace('\\', '/');
            if (!index.containsKey(relative)) {
                try {
                    index.put(relative, item(relative, Files.size(file), true, false, ""));
                    changed = true;
                } catch (IOException ignored) {
                    // 无法读取元数据的文件不加入列表。
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> entry : new ArrayList<Map.Entry<String, Map<String, Object>>>(index.entrySet())) {
            String relative = entry.getKey();
            if (!isImage(relative)) {
                index.remove(relative);
                changed = true;
                continue;
            }
            Map<String, Object> source = entry.getValue();
            boolean local = Files.isRegularFile(localPath(relative));
            boolean webdav = AppConfigService.booleanValue(source.get("webdav"), false);
            if (!local && !webdav) {
                index.remove(relative);
                changed = true;
                continue;
            }
            String date = AppConfigService.clean(source.get("date"));
            if (!startDate.isEmpty() && date.compareTo(startDate) < 0 || !endDate.isEmpty() && date.compareTo(endDate) > 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>(source);
            item.put("local", local);
            item.put("webdav", webdav);
            item.put("storage", local && webdav ? "both" : webdav ? "webdav" : "local");
            item.put("url", publicUrl(relative, baseUrl));
            result.add(item);
        }
        if (changed) {
            saveIndex(index);
        }
        Collections.sort(result, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> first, Map<String, Object> second) {
                return AppConfigService.clean(second.get("created_at")).compareTo(AppConfigService.clean(first.get("created_at")));
            }
        });
        return result;
    }

    public byte[] getBytes(String relative) {
        Path local = localPath(relative);
        try {
            if (Files.isRegularFile(local)) {
                return Files.readAllBytes(local);
            }
            Map<String, Object> item = loadIndex().get(safeRelative(relative));
            if (item != null && AppConfigService.booleanValue(item.get("webdav"), false)) {
                return new WebDavClient(config.getImageStorageSettings()).get(safeRelative(relative));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("unable to read image", exception);
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "image not found");
    }

    public boolean hasLocal(String relative) {
        return Files.isRegularFile(localPath(relative));
    }

    public synchronized boolean delete(String relative) {
        String safe = safeRelative(relative);
        Map<String, Map<String, Object>> index = loadIndex();
        Map<String, Object> item = index.get(safe);
        boolean removed = false;
        try {
            removed = Files.deleteIfExists(localPath(safe));
            if (item != null && AppConfigService.booleanValue(item.get("webdav"), false)) {
                removed = new WebDavClient(config.getImageStorageSettings()).delete(safe) || removed;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("unable to delete image", exception);
        }
        if (item != null) {
            index.remove(safe);
            saveIndex(index);
        }
        return removed;
    }

    public synchronized Map<String, Integer> syncAll() {
        String mode = AppConfigService.clean(config.getImageStorageSettings().get("mode"));
        if (!"webdav".equals(mode) && !"both".equals(mode)) {
            throw new IllegalStateException("WebDAV 图片存储未启用");
        }
        Map<String, Map<String, Object>> index = loadIndex();
        WebDavClient client = new WebDavClient(config.getImageStorageSettings());
        int uploaded = 0;
        int skipped = 0;
        int failed = 0;
        for (Path file : localImageFiles()) {
            String relative = config.getImagesDir().relativize(file).toString().replace('\\', '/');
            Map<String, Object> current = index.get(relative);
            if (current != null && AppConfigService.booleanValue(current.get("webdav"), false)) {
                skipped++;
                continue;
            }
            try {
                byte[] payload = Files.readAllBytes(file);
                String remote = client.put(relative, payload, "image/png");
                index.put(relative, item(relative, payload.length, true, true, remote));
                uploaded++;
            } catch (RuntimeException exception) {
                failed++;
            } catch (IOException exception) {
                failed++;
            }
        }
        saveIndex(index);
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        result.put("uploaded", uploaded);
        result.put("skipped", skipped);
        result.put("failed", failed);
        return result;
    }

    public Map<String, Object> testWebdav() {
        return new WebDavClient(config.getImageStorageSettings()).test();
    }

    private Map<String, Object> item(String relative, long size, boolean local, boolean webdav, String remoteUrl) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("rel", relative);
        item.put("path", relative);
        item.put("name", Paths.get(relative).getFileName().toString());
        String[] parts = relative.split("/");
        item.put("date", parts.length >= 4 ? parts[0] + "-" + parts[1] + "-" + parts[2] : LocalDateTime.now().toLocalDate().toString());
        item.put("size", size);
        item.put("created_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        item.put("storage", local && webdav ? "both" : webdav ? "webdav" : "local");
        item.put("local", local);
        item.put("webdav", webdav);
        item.put("remote_url", remoteUrl);
        try {
            byte[] payload = local && Files.isRegularFile(localPath(relative)) ? Files.readAllBytes(localPath(relative)) : null;
            if (payload != null) {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(payload));
                if (image != null) {
                    item.put("width", image.getWidth());
                    item.put("height", image.getHeight());
                }
            }
        } catch (IOException ignored) {
            // 无法解析尺寸时保留基础图片元数据。
        }
        return item;
    }

    private String relativePath(byte[] payload) {
        String hash;
        try {
            byte[] result = MessageDigest.getInstance("MD5").digest(payload);
            StringBuilder builder = new StringBuilder();
            for (byte value : result) {
                builder.append(String.format("%02x", value));
            }
            hash = builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        LocalDateTime now = LocalDateTime.now();
        return now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "/" + (System.currentTimeMillis() / 1000L) + "_" + hash + ".png";
    }

    private String publicUrl(String relative, String baseUrl) {
        String configured = AppConfigService.clean(config.getImageStorageSettings().get("public_base_url"));
        String prefix = configured.isEmpty() ? AppConfigService.clean(baseUrl) + "/images" : configured;
        return prefix.replaceAll("/+$", "") + "/" + safeRelative(relative);
    }

    private Path localPath(String relative) {
        Path result = config.getImagesDir().resolve(safeRelative(relative)).normalize();
        if (!result.startsWith(config.getImagesDir())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "image not found");
        }
        return result;
    }

    private String safeRelative(String value) {
        String relative = AppConfigService.clean(value).replace('\\', '/').replaceAll("^/+", "");
        if (relative.isEmpty() || relative.contains("../") || relative.equals("..") || relative.contains("/./")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "image not found");
        }
        return relative;
    }

    private boolean isImage(String value) {
        String lower = value.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp");
    }

    private List<Path> localImageFiles() {
        List<Path> result = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.walk(config.getImagesDir())) {
            stream.filter(Files::isRegularFile).filter(path -> isImage(path.getFileName().toString())).forEach(result::add);
        } catch (IOException ignored) {
            return result;
        }
        return result;
    }

    private Map<String, Map<String, Object>> loadIndex() {
        if (!Files.isRegularFile(indexFile)) {
            return new LinkedHashMap<String, Map<String, Object>>();
        }
        try {
            Map<String, Object> root = mapper.readValue(indexFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
            Object raw = root.get("items");
            return raw instanceof Map ? mapper.convertValue(raw, new TypeReference<LinkedHashMap<String, Map<String, Object>>>() { })
                    : new LinkedHashMap<String, Map<String, Object>>();
        } catch (IOException exception) {
            return new LinkedHashMap<String, Map<String, Object>>();
        }
    }

    private void saveIndex(Map<String, Map<String, Object>> items) {
        try {
            Files.createDirectories(indexFile.getParent());
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("items", items);
            Files.write(indexFile, (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to persist image index", exception);
        }
    }

    private static class WebDavClient {
        private final Map<String, Object> settings;
        private final String url;
        private final String root;
        private final String authorization;

        private WebDavClient(Map<String, Object> settings) {
            this.settings = settings;
            this.url = AppConfigService.clean(settings.get("webdav_url")).replaceAll("/+$", "");
            this.root = AppConfigService.clean(settings.get("webdav_root_path")).replaceAll("^/+|/+$", "");
            String credentials = AppConfigService.clean(settings.get("webdav_username")) + ":" + AppConfigService.clean(settings.get("webdav_password"));
            this.authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }

        private String put(String relative, byte[] payload, String type) {
            ensureDirectories(relative);
            request("PUT", remote(relative), payload, type);
            return remote(relative);
        }

        private byte[] get(String relative) {
            return request("GET", remote(relative), null, null);
        }

        private boolean delete(String relative) {
            try {
                request("DELETE", remote(relative), null, null);
                return true;
            } catch (RuntimeException exception) {
                if (exception.getMessage() != null && exception.getMessage().contains("HTTP 404")) {
                    return false;
                }
                throw exception;
            }
        }

        private Map<String, Object> test() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            URI target;
            try {
                target = URI.create(url);
            } catch (IllegalArgumentException exception) {
                target = null;
            }
            if (target == null || !("http".equalsIgnoreCase(target.getScheme()) || "https".equalsIgnoreCase(target.getScheme()))) {
                result.put("ok", false);
                result.put("status", 0);
                result.put("error", "invalid WebDAV URL");
                return result;
            }
            try {
                put(".chatgpt2api_webdav_test.txt", "chatgpt2api webdav test\n".getBytes(StandardCharsets.UTF_8), "text/plain");
                delete(".chatgpt2api_webdav_test.txt");
                result.put("ok", true);
                result.put("status", 200);
                result.put("error", null);
            } catch (RuntimeException exception) {
                result.put("ok", false);
                result.put("status", 0);
                result.put("error", exception.getMessage());
            }
            return result;
        }

        private void ensureDirectories(String relative) {
            Path relativePath = Paths.get(relative);
            String parent = root + "/" + (relativePath.getParent() == null ? "" : relativePath.getParent().toString().replace('\\', '/'));
            String current = url;
            for (String segment : parent.split("/")) {
                if (segment.isEmpty()) {
                    continue;
                }
                current += "/" + encode(segment);
                try {
                    request("MKCOL", current, null, null);
                } catch (RuntimeException exception) {
                    if (exception.getMessage() == null || !exception.getMessage().contains("HTTP 405")) {
                        throw exception;
                    }
                }
            }
        }

        private String remote(String relative) {
            String path = root.isEmpty() ? relative : root + "/" + relative;
            StringBuilder result = new StringBuilder(url);
            for (String segment : path.split("/")) {
                if (!segment.isEmpty()) {
                    result.append('/').append(encode(segment));
                }
            }
            return result.toString();
        }

        private String encode(String value) {
            try {
                return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private byte[] request(String method, String target, byte[] payload, String contentType) {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create(target).toURL().openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Authorization", authorization);
                if (payload != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", contentType);
                    connection.getOutputStream().write(payload);
                }
                int status = connection.getResponseCode();
                if (status >= 400 && !("MKCOL".equals(method) && status == 405)) {
                    throw new IllegalStateException("WebDAV " + method + " failed: HTTP " + status);
                }
                if ("GET".equals(method)) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = connection.getInputStream().read(buffer)) >= 0) {
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
                return new byte[0];
            } catch (IOException exception) {
                throw new IllegalStateException("WebDAV " + method + " failed: " + exception.getMessage(), exception);
            }
        }
    }
}
