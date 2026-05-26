package com.chatgpt2api.storage;

import java.util.List;
import java.util.Map;

public interface StorageBackend {
    List<Map<String, Object>> loadAccounts();

    void saveAccounts(List<Map<String, Object>> accounts);

    List<Map<String, Object>> loadAuthKeys();

    void saveAuthKeys(List<Map<String, Object>> authKeys);

    Map<String, Object> healthCheck();

    Map<String, Object> getBackendInfo();
}
