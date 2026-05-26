package com.chatgpt2api.image;

import com.chatgpt2api.common.ApiException;
import com.chatgpt2api.config.AppConfigService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ImageService {
    private final AppConfigService config;
    private final ImageTagService tags;
    private final ImageStorageService storage;

    public ImageService(AppConfigService config, ImageTagService tags, ImageStorageService storage) {
        this.config = config;
        this.tags = tags;
        this.storage = storage;
    }

    public Map<String, Object> listImages(String baseUrl, String startDate, String endDate) {
        cleanupOldImages();
        Map<String, List<String>> tagMap = tags.loadTags();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> stored : storage.listItems(baseUrl, startDate, endDate)) {
            String relative = String.valueOf(stored.get("path"));
            Map<String, Object> item = new LinkedHashMap<String, Object>(stored);
            item.put("thumbnail_url", baseUrl + "/image-thumbnails/" + relative);
            item.put("tags", tagMap.containsKey(relative) ? tagMap.get(relative) : new ArrayList<String>());
            items.add(item);
        }
        Collections.sort(items, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> first, Map<String, Object> second) {
                return String.valueOf(second.get("created_at")).compareTo(String.valueOf(first.get("created_at")));
            }
        });
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (Map<String, Object> item : items) {
            String date = String.valueOf(item.get("date"));
            if (!groups.containsKey(date)) {
                groups.put(date, new ArrayList<Map<String, Object>>());
            }
            groups.get(date).add(item);
        }
        List<Map<String, Object>> groupItems = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, List<Map<String, Object>>> group : groups.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("date", group.getKey());
            item.put("items", group.getValue());
            groupItems.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("groups", groupItems);
        return result;
    }

    public Resource image(String relative) {
        return resource(safeImagePath(relative));
    }

    public byte[] imageBytes(String relative) {
        return storage.getBytes(relative);
    }

    public Resource thumbnail(String relative) {
        Path target = safeThumbnailPath(relative);
        try {
            Path source = config.getImagesDir().resolve(safeRelative(relative)).normalize();
            long sourceModified = Files.isRegularFile(source) ? Files.getLastModifiedTime(source).toMillis() : 0L;
            if (Files.isRegularFile(target) && (sourceModified == 0L || Files.getLastModifiedTime(target).toMillis() >= sourceModified)) {
                return resource(target);
            }
            Files.createDirectories(target.getParent());
            BufferedImage original = Files.isRegularFile(source) ? ImageIO.read(source.toFile()) : ImageIO.read(new ByteArrayInputStream(storage.getBytes(relative)));
            if (original == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "failed to create thumbnail");
            }
            double scale = Math.min(1d, Math.min(320d / original.getWidth(), 320d / original.getHeight()));
            int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
            Image scaled = original.getScaledInstance(width, height, Image.SCALE_SMOOTH);
            BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = output.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(scaled, 0, 0, null);
            graphics.dispose();
            ImageIO.write(output, "png", target.toFile());
            return resource(target);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "failed to create thumbnail");
        }
    }

    public Map<String, Integer> deleteImages(List<String> paths, String startDate, String endDate, boolean allMatching) {
        List<String> targets = new ArrayList<String>();
        if (allMatching) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) listImages("", startDate, endDate).get("items");
            for (Map<String, Object> item : items) {
                targets.add(String.valueOf(item.get("path")));
            }
        } else if (paths != null) {
            targets.addAll(paths);
        }
        int removed = 0;
        for (String item : targets) {
            try {
                if (!storage.delete(item)) {
                    continue;
                }
                Files.deleteIfExists(safeThumbnailPath(item));
                tags.removeTags(safeRelative(item));
                removed++;
            } catch (ApiException ignored) {
                // 已删除或非法路径不计入成功数量。
            } catch (IOException ignored) {
                // 文件删除失败不计入成功数量。
            }
        }
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        result.put("removed", removed);
        return result;
    }

    public byte[] zip(List<String> paths) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ZipOutputStream zip = new ZipOutputStream(output);
            Set<String> names = new LinkedHashSet<String>();
            int added = 0;
            for (String value : paths == null ? new ArrayList<String>() : paths) {
                String relative;
                try {
                    relative = safeRelative(value);
                } catch (ApiException exception) {
                    continue;
                }
                String name = java.nio.file.Paths.get(relative).getFileName().toString();
                if (names.contains(name)) {
                    String base = name;
                    String extension = "";
                    int dot = name.lastIndexOf('.');
                    if (dot >= 0) {
                        base = name.substring(0, dot);
                        extension = name.substring(dot);
                    }
                    int suffix = 2;
                    while (names.contains(base + "_" + suffix + extension)) {
                        suffix++;
                    }
                    name = base + "_" + suffix + extension;
                }
                names.add(name);
                zip.putNextEntry(new ZipEntry(name));
                zip.write(storage.getBytes(relative));
                zip.closeEntry();
                added++;
            }
            zip.finish();
            zip.close();
            if (added == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "no images found");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("unable to package images", exception);
        }
    }

    public Map<String, Object> storageStats() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try {
            FileStore store = Files.getFileStore(config.getImagesDir());
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            long imageSize = 0;
            List<Path> files = imageFiles();
            for (Path path : files) {
                imageSize += Files.size(path);
            }
            result.put("disk_total_mb", total / 1024 / 1024);
            result.put("disk_used_mb", (total - free) / 1024 / 1024);
            result.put("disk_free_mb", free / 1024 / 1024);
            result.put("image_count", files.size());
            result.put("image_size_mb", imageSize / 1024 / 1024);
            result.put("image_size_bytes", imageSize);
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("unable to inspect image storage", exception);
        }
    }

    public Map<String, Object> cleanupToTarget(int targetFreeMb, boolean dryRun) {
        long currentFree = ((Number) storageStats().get("disk_free_mb")).longValue();
        List<Path> paths = imageFiles();
        Collections.sort(paths, new Comparator<Path>() {
            @Override
            public int compare(Path first, Path second) {
                try {
                    return Files.getLastModifiedTime(first).compareTo(Files.getLastModifiedTime(second));
                } catch (IOException ignored) {
                    return 0;
                }
            }
        });
        int removed = 0;
        long freed = 0;
        for (Path path : paths) {
            if (currentFree + freed / 1024 / 1024 >= targetFreeMb) {
                break;
            }
            try {
                long size = Files.size(path);
                if (!dryRun) {
                    String relative = relative(path);
                    Files.deleteIfExists(path);
                    Files.deleteIfExists(safeThumbnailPath(relative));
                    tags.removeTags(relative);
                }
                removed++;
                freed += size;
            } catch (IOException ignored) {
                // 清理任务忽略单个不可删除文件。
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("removed", removed);
        result.put("freed_mb", freed / 1024 / 1024);
        result.put("target_free_mb", targetFreeMb);
        result.put("current_free_mb", currentFree + freed / 1024 / 1024);
        result.put("done", currentFree + freed / 1024 / 1024 >= targetFreeMb);
        result.put("dry_run", dryRun);
        return result;
    }

    public Map<String, Object> compressImages() {
        long saved = 0;
        int compressed = 0;
        for (Path path : imageFiles()) {
            if (!path.getFileName().toString().toLowerCase().endsWith(".png")) {
                continue;
            }
            Path temporary = path.resolveSibling(path.getFileName().toString() + ".tmp");
            try {
                long originalSize = Files.size(path);
                BufferedImage image = ImageIO.read(path.toFile());
                if (image == null || !ImageIO.write(image, "png", temporary.toFile())) {
                    continue;
                }
                long newSize = Files.size(temporary);
                if (newSize < originalSize) {
                    Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    saved += originalSize - newSize;
                    compressed++;
                } else {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException ignored) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignoredDelete) {
                    // 临时文件清理失败不影响其它图片压缩。
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("compressed", compressed);
        result.put("saved_bytes", saved);
        result.put("saved_mb", saved / 1024 / 1024);
        return result;
    }

    private void cleanupOldImages() {
        Instant cutoff = Instant.now().minusSeconds(config.getImageRetentionDays() * 86400L);
        for (Path path : imageFiles()) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                if (attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(path);
                    tags.removeTags(relative(path));
                }
            } catch (IOException ignored) {
                // 定期清理忽略不可访问文件。
            }
        }
    }

    private List<Path> imageFiles() {
        List<Path> result = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(config.getImagesDir())) {
            stream.filter(Files::isRegularFile).filter(path -> {
                String value = path.getFileName().toString().toLowerCase();
                return value.endsWith(".png") || value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".webp");
            }).forEach(result::add);
        } catch (IOException ignored) {
            return result;
        }
        return result;
    }

    private String relative(Path path) {
        return config.getImagesDir().relativize(path).toString().replace('\\', '/');
    }

    private String date(Path path, String relative) {
        String[] parts = relative.split("/");
        if (parts.length >= 4 && parts[0].matches("\\d{4}") && parts[1].matches("\\d{2}") && parts[2].matches("\\d{2}")) {
            return parts[0] + "-" + parts[1] + "-" + parts[2];
        }
        try {
            return Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
        } catch (IOException exception) {
            return "";
        }
    }

    private Path safeImagePath(String relative) {
        Path path = config.getImagesDir().resolve(safeRelative(relative)).normalize();
        if (!path.startsWith(config.getImagesDir()) || !Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "image not found");
        }
        return path;
    }

    private Path safeThumbnailPath(String relative) {
        Path path = config.getImageThumbnailsDir().resolve(safeRelative(relative) + ".png").normalize();
        if (!path.startsWith(config.getImageThumbnailsDir())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "image not found");
        }
        return path;
    }

    private String safeRelative(String value) {
        String relative = AppConfigService.clean(value).replace('\\', '/').replaceAll("^/+", "");
        if (relative.isEmpty() || relative.contains("../") || relative.equals("..") || relative.contains("/./")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "image not found");
        }
        return relative;
    }

    private Resource resource(Path path) {
        try {
            return new UrlResource(path.toUri());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "image not found");
        }
    }
}
