package com.chatgpt2api.controller;

import com.chatgpt2api.account.AccountService;
import com.chatgpt2api.auth.AuthGuard;
import com.chatgpt2api.backup.BackupService;
import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.common.ApiException;
import com.chatgpt2api.image.ImageService;
import com.chatgpt2api.image.ImageStorageService;
import com.chatgpt2api.image.ImageTagService;
import com.chatgpt2api.http.ProxyService;
import com.chatgpt2api.log.LogService;
import com.chatgpt2api.storage.StorageBackend;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class SystemController {
    private final AppConfigService config;
    private final AuthGuard authGuard;
    private final StorageBackend storage;
    private final AccountService accountService;
    private final ImageService imageService;
    private final ImageTagService imageTagService;
    private final LogService logService;
    private final ProxyService proxyService;
    private final ImageStorageService imageStorageService;
    private final BackupService backupService;

    public SystemController(
            AppConfigService config,
            AuthGuard authGuard,
            StorageBackend storage,
            AccountService accountService,
            ImageService imageService,
            ImageTagService imageTagService,
            LogService logService,
            ProxyService proxyService,
            ImageStorageService imageStorageService,
            BackupService backupService) {
        this.config = config;
        this.authGuard = authGuard;
        this.storage = storage;
        this.accountService = accountService;
        this.imageService = imageService;
        this.imageTagService = imageTagService;
        this.logService = logService;
        this.proxyService = proxyService;
        this.imageStorageService = imageStorageService;
        this.backupService = backupService;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> identity = authGuard.requireIdentity(authorization);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("version", config.getAppVersion());
        result.put("role", identity.get("role"));
        result.put("subject_id", identity.get("id"));
        result.put("name", identity.get("name"));
        return result;
    }

    @GetMapping("/version")
    public Map<String, Object> version() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("version", config.getAppVersion());
        return result;
    }

    @GetMapping("/api/settings")
    public Map<String, Object> settings(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("config", config.getPublicSettings());
        return result;
    }

    @PostMapping("/api/settings")
    public Map<String, Object> saveSettings(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("config", config.update(body));
        return result;
    }

    @GetMapping("/api/storage/info")
    public Map<String, Object> storageInfo(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("backend", storage.getBackendInfo());
        result.put("health", storage.healthCheck());
        return result;
    }

    @GetMapping("/api/images")
    public Map<String, Object> images(
            HttpServletRequest request,
            @RequestParam(value = "start_date", defaultValue = "") String startDate,
            @RequestParam(value = "end_date", defaultValue = "") String endDate,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return imageService.listImages(resolveBaseUrl(request), startDate.trim(), endDate.trim());
    }

    @GetMapping("/images/**")
    public ResponseEntity<byte[]> image(HttpServletRequest request) throws IOException {
        String relative = wildcardPath(request, "/images/");
        return ResponseEntity.ok()
                .contentType(mediaType(relative))
                .body(imageService.imageBytes(relative));
    }

    @GetMapping("/image-thumbnails/**")
    public ResponseEntity<Resource> thumbnail(HttpServletRequest request) {
        Resource resource = imageService.thumbnail(wildcardPath(request, "/image-thumbnails/"));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(resource);
    }

    @PostMapping("/api/images/delete")
    public Map<String, Integer> deleteImages(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return imageService.deleteImages(
                strings(body.get("paths")),
                AppConfigService.clean(body.get("start_date")),
                AppConfigService.clean(body.get("end_date")),
                AppConfigService.booleanValue(body.get("all_matching"), false)
        );
    }

    @PostMapping("/api/images/download")
    public ResponseEntity<byte[]> downloadImages(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"images.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(imageService.zip(strings(body.get("paths"))));
    }

    @GetMapping("/api/images/download/**")
    public ResponseEntity<byte[]> downloadImage(
            HttpServletRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) throws IOException {
        authGuard.requireAdmin(authorization);
        String relative = wildcardPath(request, "/api/images/download/");
        String filename = java.nio.file.Paths.get(relative).getFileName().toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType(relative))
                .body(imageService.imageBytes(relative));
    }

    @GetMapping("/api/images/tags")
    public Map<String, Object> imageTags(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tags", imageTagService.getAllTags());
        return result;
    }

    @PostMapping("/api/images/tags")
    public Map<String, Object> updateImageTags(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        String path = AppConfigService.clean(body.get("path")).replaceAll("^/+", "");
        if (path.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "path is required");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("tags", imageTagService.setTags(path, strings(body.get("tags"))));
        return result;
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/images/tags/{tag}")
    public Map<String, Object> deleteImageTag(
            @org.springframework.web.bind.annotation.PathVariable String tag,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("removed_from", imageTagService.deleteTag(tag));
        return result;
    }

    @GetMapping("/api/images/storage")
    public Map<String, Object> imageStorage(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return imageService.storageStats();
    }

    @PostMapping("/api/images/storage/compress")
    public Map<String, Object> compressImages(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return imageService.compressImages();
    }

    @PostMapping("/api/images/storage/cleanup-to-target")
    public Map<String, Object> cleanupImages(
            @RequestParam(value = "target_free_mb", defaultValue = "500") int targetFreeMb,
            @RequestParam(value = "dry_run", defaultValue = "false") boolean dryRun,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return imageService.cleanupToTarget(targetFreeMb, dryRun);
    }

    @GetMapping("/api/logs")
    public Map<String, Object> logs(
            @RequestParam(value = "type", defaultValue = "") String type,
            @RequestParam(value = "start_date", defaultValue = "") String startDate,
            @RequestParam(value = "end_date", defaultValue = "") String endDate,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", logService.list(type.trim(), startDate.trim(), endDate.trim()));
        return result;
    }

    @PostMapping("/api/logs/delete")
    public Map<String, Integer> deleteLogs(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return logService.delete(strings(body.get("ids")));
    }

    @PostMapping("/api/proxy/test")
    public Map<String, Object> testProxy(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        String candidate = body == null ? "" : AppConfigService.clean(body.get("url"));
        if (candidate.isEmpty()) {
            candidate = config.getProxySettings();
        }
        if (candidate.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "proxy url is required");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("result", proxyService.test(candidate));
        return result;
    }

    @PostMapping("/api/image-storage/test")
    public Map<String, Object> testImageStorage(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("result", imageStorageService.testWebdav());
        return result;
    }

    @PostMapping("/api/image-storage/sync")
    public Map<String, Object> syncImageStorage(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        try {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("result", imageStorageService.syncAll());
            return result;
        } catch (IllegalStateException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/api/backup/test")
    public Map<String, Object> testBackup(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        try {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("result", backupService.testConnection());
            return result;
        } catch (IllegalStateException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/api/backups")
    public Map<String, Object> backups(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        try {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("items", backupService.listBackups());
            result.put("state", backupService.getStatus());
            result.put("settings", backupService.getSettings());
            return result;
        } catch (IllegalStateException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/api/backups/run")
    public Map<String, Object> runBackup(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        try {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("result", backupService.runBackup());
            return result;
        } catch (IllegalStateException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/api/backups/delete")
    public Map<String, Object> deleteBackup(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        try {
            backupService.delete(AppConfigService.clean(body.get("key")));
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("ok", true);
            return result;
        } catch (IllegalStateException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/api/backups/detail")
    public Map<String, Object> backupDetail(
            @RequestParam(value = "key", defaultValue = "") String key,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        try {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("item", backupService.detail(key));
            return result;
        } catch (IllegalStateException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/api/backups/download")
    public ResponseEntity<byte[]> downloadBackup(
            @RequestParam(value = "key", defaultValue = "") String key,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        try {
            Map<String, Object> item = backupService.download(key);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + item.get("name") + "\"")
                    .contentType(MediaType.parseMediaType(String.valueOf(item.get("content_type"))))
                    .contentLength(((Number) item.get("size")).longValue())
                    .body((byte[]) item.get("payload"));
        } catch (IllegalStateException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/health")
    public ResponseEntity<?> health(@RequestParam(value = "format", defaultValue = "html") String format) {
        Map<String, Object> stats = accountService.getStats();
        boolean healthy = ((Integer) stats.get("active")) > 0 || ((Integer) stats.get("unlimited_quota_count")) > 0;
        Map<String, Object> storageResult = new LinkedHashMap<String, Object>();
        storageResult.put("backend", storage.getBackendInfo());
        storageResult.put("health", storage.healthCheck());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", healthy ? "ok" : "degraded");
        result.put("healthy", healthy);
        result.put("version", config.getAppVersion());
        result.put("storage", storageResult);
        result.put("accounts", stats);
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().body(result);
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(healthHtml(healthy, stats));
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        if (!config.getBaseUrl().isEmpty()) {
            return config.getBaseUrl();
        }
        return request.getScheme() + "://" + request.getHeader("Host");
    }

    private String wildcardPath(HttpServletRequest request, String prefix) {
        String path = String.valueOf(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE));
        int index = path.indexOf(prefix);
        return index >= 0 ? path.substring(index + prefix.length()) : "";
    }

    private MediaType mediaType(Resource resource) throws IOException {
        String contentType = Files.probeContentType(resource.getFile().toPath());
        return contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
    }

    private MediaType mediaType(String path) {
        String value = path.toLowerCase();
        if (value.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (value.endsWith(".jpg") || value.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return value.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : MediaType.APPLICATION_OCTET_STREAM;
    }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                String text = AppConfigService.clean(item);
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private String healthHtml(boolean healthy, Map<String, Object> stats) {
        @SuppressWarnings("unchecked")
        Map<String, Integer> byType = (Map<String, Integer>) stats.get("by_type");
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, Integer> item : byType.entrySet()) {
            rows.append("<tr><td>").append(escapeHtml(item.getKey())).append("</td><td>")
                    .append(item.getValue()).append("</td></tr>");
        }
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta http-equiv=\"refresh\" content=\"30\"><title>号池健康监控 - chatgpt2api</title>"
                + "<style>*{margin:0;padding:0;box-sizing:border-box}body{font-family:system-ui,-apple-system,sans-serif;background:#0f1117;color:#e2e8f0;min-height:100vh}"
                + ".header{background:#1a1d27;border-bottom:1px solid #2a2d3a;padding:16px 24px;display:flex;justify-content:space-between;align-items:center}.header h1{font-size:20px}"
                + ".status-dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:8px}.status-ok{background:#22c55e}.status-degraded{background:#f59e0b}"
                + ".container{max-width:960px;margin:0 auto;padding:24px}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px;margin-bottom:24px}"
                + ".card{background:#1a1d27;border:1px solid #2a2d3a;border-radius:10px;padding:16px}.value{font-size:28px;font-weight:700;margin:4px 0}.label{font-size:13px;color:#94a3b8}"
                + ".green{color:#22c55e}.yellow{color:#f59e0b}.red{color:#ef4444}.blue{color:#6c63ff}table{width:100%;border-collapse:collapse;background:#1a1d27;border:1px solid #2a2d3a}"
                + "th,td{padding:10px 12px;text-align:left;border-top:1px solid #2a2d3a}</style></head><body>"
                + "<div class=\"header\"><h1><span class=\"status-dot " + (healthy ? "status-ok" : "status-degraded") + "\"></span>号池健康监控</h1>"
                + "<div>v" + escapeHtml(config.getAppVersion()) + " / 30s 自动刷新</div></div><div class=\"container\"><div class=\"cards\">"
                + card("号池状态", healthy ? "正常" : "异常", healthy ? "green" : "yellow")
                + card("当前账号", stats.get("total"), "blue")
                + card("累计入库", stats.get("cumulative_total"), "")
                + card("可用账号", stats.get("active"), "green")
                + card("无限额", stats.get("unlimited_quota_count"), "")
                + card("剩余额度", stats.get("total_quota"), "")
                + card("限流", stats.get("limited"), "yellow")
                + card("异常", stats.get("abnormal"), "red")
                + card("禁用", stats.get("disabled"), "")
                + "</div><h2>账号类型分布</h2><table><tr><th>类型</th><th>数量</th></tr>" + rows
                + "</table></div></body></html>";
    }

    private String card(String label, Object value, String color) {
        return "<div class=\"card\"><div class=\"label\">" + label + "</div><div class=\"value " + color + "\">"
                + escapeHtml(String.valueOf(value)) + "</div></div>";
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
