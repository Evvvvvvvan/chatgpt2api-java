package com.chatgpt2api.auth;

import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.storage.StorageBackend;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
    private final StorageBackend storage;
    private final AppConfigService config;
    private final SecureRandom random = new SecureRandom();
    private List<Map<String, Object>> items;
    private final Map<String, Instant> lastFlushAt = new LinkedHashMap<String, Instant>();

    public AuthService(StorageBackend storage, AppConfigService config) {
        this.storage = storage;
        this.config = config;
        this.items = load();
    }

    public synchronized List<Map<String, Object>> listKeys(String role) {
        items = load();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : items) {
            if (role == null || role.equals(item.get("role"))) {
                result.add(publicItem(item));
            }
        }
        return result;
    }

    public synchronized Map<String, Object> createKey(String role, String name) {
        items = load();
        String normalizedRole = "admin".equals(role) ? "admin" : "user";
        String normalizedName = buildName(name, normalizedRole, "");
        String rawKey;
        String keyHash;
        do {
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            rawKey = "sk-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            keyHash = sha256(rawKey);
        } while (hasKeyHash(keyHash, ""));
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", compactUuid());
        item.put("name", normalizedName);
        item.put("role", normalizedRole);
        item.put("key_hash", keyHash);
        item.put("enabled", true);
        item.put("created_at", Instant.now().toString());
        item.put("last_used_at", null);
        items.add(item);
        save();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("item", publicItem(item));
        result.put("key", rawKey);
        return result;
    }

    public synchronized Map<String, Object> updateKey(String keyId, Map<String, Object> updates, String role) {
        items = load();
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> item = items.get(index);
            if (!AppConfigService.clean(keyId).equals(item.get("id")) || (role != null && !role.equals(item.get("role")))) {
                continue;
            }
            Map<String, Object> next = new LinkedHashMap<String, Object>(item);
            String itemRole = "admin".equals(item.get("role")) ? "admin" : "user";
            if (updates.containsKey("name") && updates.get("name") != null) {
                next.put("name", buildName(AppConfigService.clean(updates.get("name")), itemRole, keyId));
            }
            if (updates.containsKey("enabled") && updates.get("enabled") != null) {
                next.put("enabled", AppConfigService.booleanValue(updates.get("enabled"), true));
            }
            if (updates.containsKey("key") && updates.get("key") != null) {
                next.put("key_hash", buildKeyHash(AppConfigService.clean(updates.get("key")), keyId));
            }
            items.set(index, next);
            save();
            return publicItem(next);
        }
        return null;
    }

    public synchronized boolean deleteKey(String keyId, String role) {
        items = load();
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> item = items.get(index);
            if (AppConfigService.clean(keyId).equals(item.get("id")) && (role == null || role.equals(item.get("role")))) {
                items.remove(index);
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized Map<String, Object> authenticate(String rawKey) {
        String candidate = AppConfigService.clean(rawKey);
        if (candidate.isEmpty()) {
            return null;
        }
        String candidateHash = sha256(candidate);
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> item = items.get(index);
            if (!AppConfigService.booleanValue(item.get("enabled"), true)
                    || !constantEquals(AppConfigService.clean(item.get("key_hash")), candidateHash)) {
                continue;
            }
            Map<String, Object> next = new LinkedHashMap<String, Object>(item);
            Instant now = Instant.now();
            next.put("last_used_at", now.toString());
            items.set(index, next);
            String id = AppConfigService.clean(next.get("id"));
            Instant prior = lastFlushAt.get(id);
            if (prior == null || now.minusSeconds(60).isAfter(prior)) {
                try {
                    save();
                    lastFlushAt.put(id, now);
                } catch (RuntimeException ignored) {
                    // 鉴权结果不依赖最近使用时间的持久化。
                }
            }
            return publicItem(next);
        }
        return null;
    }

    private List<Map<String, Object>> load() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> raw : storage.loadAuthKeys()) {
            String role = AppConfigService.clean(raw.get("role")).toLowerCase();
            String hash = AppConfigService.clean(raw.get("key_hash"));
            if ((!"admin".equals(role) && !"user".equals(role)) || hash.isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", valueOr(raw.get("id"), compactUuid()));
            item.put("name", valueOr(raw.get("name"), defaultName(role)));
            item.put("role", role);
            item.put("key_hash", hash);
            item.put("enabled", AppConfigService.booleanValue(raw.get("enabled"), true));
            item.put("created_at", valueOr(raw.get("created_at"), Instant.now().toString()));
            item.put("last_used_at", emptyToNull(raw.get("last_used_at")));
            result.add(item);
        }
        return result;
    }

    private void save() {
        storage.saveAuthKeys(items);
    }

    private Map<String, Object> publicItem(Map<String, Object> item) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", item.get("id"));
        result.put("name", item.get("name"));
        result.put("role", item.get("role"));
        result.put("enabled", item.get("enabled"));
        result.put("created_at", item.get("created_at"));
        result.put("last_used_at", item.get("last_used_at"));
        return result;
    }

    private String buildName(String name, String role, String excludedId) {
        String candidate = AppConfigService.clean(name);
        if (candidate.isEmpty()) {
            candidate = defaultName(role);
            if (hasName(candidate, role, excludedId)) {
                int suffix = 2;
                while (hasName(candidate + " " + suffix, role, excludedId)) {
                    suffix++;
                }
                candidate = candidate + " " + suffix;
            }
            return candidate;
        }
        if (hasName(candidate, role, excludedId)) {
            throw new IllegalArgumentException("这个名称已经在使用中了，换一个更容易区分的名称吧");
        }
        return candidate;
    }

    private String buildKeyHash(String rawKey, String excludedId) {
        if (rawKey.isEmpty()) {
            throw new IllegalArgumentException("请输入新的专用密钥");
        }
        if (constantEquals(rawKey, config.getAuthKey())) {
            throw new IllegalArgumentException("这个密钥和管理员密钥冲突了，请换一个新的密钥");
        }
        String hash = sha256(rawKey);
        if (hasKeyHash(hash, excludedId)) {
            throw new IllegalArgumentException("这个专用密钥已经存在，请换一个新的密钥");
        }
        return hash;
    }

    private boolean hasName(String name, String role, String excludedId) {
        for (Map<String, Object> item : items) {
            if (!excludedId.isEmpty() && excludedId.equals(item.get("id"))) {
                continue;
            }
            if (role.equals(item.get("role")) && name.equals(item.get("name"))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasKeyHash(String hash, String excludedId) {
        for (Map<String, Object> item : items) {
            if (!excludedId.isEmpty() && excludedId.equals(item.get("id"))) {
                continue;
            }
            if (constantEquals(hash, AppConfigService.clean(item.get("key_hash")))) {
                return true;
            }
        }
        return false;
    }

    private String defaultName(String role) {
        return "admin".equals(role) ? "管理员密钥" : "普通用户";
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Object emptyToNull(Object value) {
        String text = AppConfigService.clean(value);
        return text.isEmpty() ? null : text;
    }

    private String valueOr(Object value, String fallback) {
        String text = AppConfigService.clean(value);
        return text.isEmpty() ? fallback : text;
    }

    private String sha256(String value) {
        try {
            byte[] encoded = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            for (byte item : encoded) {
                text.append(String.format("%02x", item));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean constantEquals(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }
}
