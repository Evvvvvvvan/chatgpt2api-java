package com.chatgpt2api.auth;

import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.storage.JsonStorageBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsAuthenticatesDisablesAndDeletesUserKey() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Files.write(tempDir.resolve("config.json"), "{\"auth-key\":\"admin-key\"}".getBytes(StandardCharsets.UTF_8));
        AppConfigService config = new AppConfigService(mapper, tempDir.toString());
        config.initialize();
        AuthService service = new AuthService(
                new JsonStorageBackend(mapper, tempDir.resolve("data/accounts.json"), tempDir.resolve("data/auth_keys.json")),
                config
        );

        Map<String, Object> created = service.createKey("user", "Alice");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) created.get("item");
        String key = (String) created.get("key");

        assertTrue(key.startsWith("sk-"));
        assertEquals("Alice", item.get("name"));
        assertNotNull(service.authenticate(key));

        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("enabled", false);
        Map<String, Object> disabled = service.updateKey((String) item.get("id"), updates, "user");
        assertFalse((Boolean) disabled.get("enabled"));
        assertNull(service.authenticate(key));
        assertTrue(service.deleteKey((String) item.get("id"), "user"));
        assertTrue(service.listKeys("user").isEmpty());
    }
}
