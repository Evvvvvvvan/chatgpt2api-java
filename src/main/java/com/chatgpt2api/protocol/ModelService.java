package com.chatgpt2api.protocol;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelService {
    private static final List<String> MODEL_IDS = Arrays.asList(
            "gpt-image-2",
            "codex-gpt-image-2",
            "auto",
            "gpt-5",
            "gpt-5-1",
            "gpt-5-2",
            "gpt-5-3",
            "gpt-5-3-mini",
            "gpt-5-mini"
    );

    public Map<String, Object> listModels() {
        List<Map<String, Object>> models = new ArrayList<Map<String, Object>>();
        for (String id : MODEL_IDS) {
            Map<String, Object> model = new LinkedHashMap<String, Object>();
            model.put("id", id);
            model.put("object", "model");
            model.put("created", 0);
            model.put("owned_by", "chatgpt2api");
            model.put("permission", new ArrayList<Object>());
            model.put("root", id);
            model.put("parent", null);
            models.add(model);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("object", "list");
        result.put("data", models);
        return result;
    }
}
