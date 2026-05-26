package com.chatgpt2api.storage;

import com.chatgpt2api.config.AppConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfiguration {
    @Bean
    public StorageBackend storageBackend(ObjectMapper mapper, AppConfigService config) {
        String backend = environment("STORAGE_BACKEND", "json").toLowerCase();
        if ("json".equals(backend)) {
            return new JsonStorageBackend(
                    mapper,
                    config.getDataDir().resolve("accounts.json"),
                    config.getDataDir().resolve("auth_keys.json")
            );
        }
        if ("mysql".equals(backend) || "database".equals(backend)) {
            String databaseUrl = environment("DATABASE_URL", "");
            if (databaseUrl.isEmpty()) {
                throw new IllegalStateException("DATABASE_URL is required when using mysql storage backend");
            }
            if (!databaseUrl.startsWith("mysql://") && !databaseUrl.startsWith("jdbc:mysql://")) {
                throw new IllegalStateException("Only MySQL DATABASE_URL is supported for database storage");
            }
            return new DatabaseStorageBackend(mapper, databaseUrl);
        }
        if ("git".equals(backend)) {
            String repoUrl = environment("GIT_REPO_URL", "");
            if (repoUrl.isEmpty()) {
                throw new IllegalStateException("GIT_REPO_URL is required when using git storage backend");
            }
            return new GitStorageBackend(
                    mapper,
                    repoUrl,
                    environment("GIT_TOKEN", ""),
                    environment("GIT_BRANCH", "main"),
                    environment("GIT_FILE_PATH", "accounts.json"),
                    environment("GIT_AUTH_KEYS_FILE_PATH", "auth_keys.json"),
                    config.getDataDir().resolve("git_cache")
            );
        }
        throw new IllegalStateException("Unknown storage backend: " + backend + ". Supported backends: json, mysql, git");
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
