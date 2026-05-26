package com.chatgpt2api.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelServiceTest {
    @Test
    void exposesCompatibleImageAndTextModels() {
        Map<String, Object> result = new ModelService().listModels();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");

        assertTrue(data.stream().anyMatch(item -> "gpt-image-2".equals(item.get("id"))));
        assertTrue(data.stream().anyMatch(item -> "codex-gpt-image-2".equals(item.get("id"))));
        assertTrue(data.stream().anyMatch(item -> "auto".equals(item.get("id"))));
    }
}
