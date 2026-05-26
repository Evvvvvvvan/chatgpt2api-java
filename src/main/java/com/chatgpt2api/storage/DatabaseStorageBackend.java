package com.chatgpt2api.storage;

import com.chatgpt2api.config.AppConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DatabaseStorageBackend implements StorageBackend {
    private final ObjectMapper mapper;
    private final String sourceUrl;
    private final String jdbcUrl;

    public DatabaseStorageBackend(ObjectMapper mapper, String databaseUrl) {
        this.mapper = mapper;
        this.sourceUrl = databaseUrl;
        this.jdbcUrl = jdbcUrl(databaseUrl);
        initialize();
    }

    @Override
    public List<Map<String, Object>> loadAccounts() {
        return loadRows("accounts");
    }

    @Override
    public void saveAccounts(List<Map<String, Object>> accounts) {
        saveRows("accounts", "access_token", accounts, "access_token");
    }

    @Override
    public List<Map<String, Object>> loadAuthKeys() {
        return loadRows("auth_keys");
    }

    @Override
    public void saveAuthKeys(List<Map<String, Object>> authKeys) {
        saveRows("auth_keys", "key_id", authKeys, "id");
    }

    @Override
    public Map<String, Object> healthCheck() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT 1");
            result.put("status", "healthy");
            result.put("backend", "database");
            result.put("database_url", maskPassword(sourceUrl));
            result.put("account_count", count(connection, "accounts"));
            result.put("auth_key_count", count(connection, "auth_keys"));
        } catch (SQLException exception) {
            result.put("status", "unhealthy");
            result.put("backend", "database");
            result.put("error", exception.getMessage());
        }
        return result;
    }

    @Override
    public Map<String, Object> getBackendInfo() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", "database");
        result.put("db_type", "mysql");
        result.put("description", "数据库存储 (mysql)");
        result.put("database_url", maskPassword(sourceUrl));
        return result;
    }

    private void initialize() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            String idColumn = "BIGINT PRIMARY KEY AUTO_INCREMENT";
            statement.execute("CREATE TABLE IF NOT EXISTS accounts (id " + idColumn + ", access_token VARCHAR(2048) UNIQUE NOT NULL, data LONGTEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS auth_keys (id " + idColumn + ", key_id VARCHAR(255) UNIQUE NOT NULL, data LONGTEXT NOT NULL)");
        } catch (SQLException exception) {
            throw new IllegalStateException("unable to initialize database storage", exception);
        }
    }

    private List<Map<String, Object>> loadRows(String table) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT data FROM " + table)) {
            while (rows.next()) {
                result.add(mapper.readValue(rows.getString("data"), new TypeReference<LinkedHashMap<String, Object>>() { }));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("unable to load database storage", exception);
        }
        return result;
    }

    private void saveRows(String table, String column, List<Map<String, Object>> items, String sourceKey) {
        String insert = "INSERT INTO " + table + " (" + column + ", data) VALUES (?, ?)";
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM " + table);
            }
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                for (Map<String, Object> item : items) {
                    String key = AppConfigService.clean(item.get(sourceKey));
                    if (key.isEmpty()) {
                        continue;
                    }
                    statement.setString(1, key);
                    statement.setString(2, mapper.writeValueAsString(item));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (Exception exception) {
            throw new IllegalStateException("unable to save database storage", exception);
        }
    }

    private int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private String jdbcUrl(String value) {
        if (value.startsWith("jdbc:")) {
            return value;
        }
        if (value.startsWith("mysql://")) {
            return "jdbc:" + value;
        }
        throw new IllegalArgumentException("Only MySQL DATABASE_URL is supported");
    }

    private String maskPassword(String value) {
        return value.replaceFirst("://([^:/@]+):[^@]+@", "://$1:****@");
    }
}
