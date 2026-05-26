package com.chatgpt2api.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class TurnstileService {
    private final ObjectMapper mapper;
    private final Random random = new Random();

    public TurnstileService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String solve(String dx, String key) {
        try {
            String decoded = new String(Base64.getDecoder().decode(dx), StandardCharsets.UTF_8);
            List<List<Object>> tokens = mapper.readValue(xor(decoded, key), new TypeReference<List<List<Object>>>() { });
            Map<Integer, Object> state = seed(tokens, key);
            String result = "";
            long start = System.nanoTime();
            for (List<Object> token : tokens) {
                if (token.isEmpty()) {
                    continue;
                }
                int operation = number(token.get(0));
                try {
                    if (operation == 1) {
                        state.put(number(token.get(1)), xor(string(state.get(number(token.get(1)))), string(state.get(number(token.get(2))))));
                    } else if (operation == 2) {
                        state.put(number(token.get(1)), token.get(2));
                    } else if (operation == 3) {
                        result = Base64.getEncoder().encodeToString(string(state.get(number(token.get(1)))).getBytes(StandardCharsets.UTF_8));
                    } else if (operation == 5) {
                        int target = number(token.get(1));
                        state.put(target, string(state.get(target)) + string(state.get(number(token.get(2)))));
                    } else if (operation == 6 || operation == 24) {
                        String value = string(state.get(number(token.get(2)))) + "." + string(state.get(number(token.get(3))));
                        state.put(number(token.get(1)), "window.document.location".equals(value) ? "https://chatgpt.com/" : value);
                    } else if (operation == 8) {
                        state.put(number(token.get(1)), state.get(number(token.get(2))));
                    } else if (operation == 14) {
                        state.put(number(token.get(1)), mapper.readValue(string(state.get(number(token.get(2)))), Object.class));
                    } else if (operation == 15) {
                        state.put(number(token.get(1)), mapper.writeValueAsString(state.get(number(token.get(2)))));
                    } else if (operation == 17) {
                        invokeReturn(state, token, start);
                    } else if (operation == 18) {
                        int target = number(token.get(1));
                        state.put(target, new String(Base64.getDecoder().decode(string(state.get(target))), StandardCharsets.UTF_8));
                    } else if (operation == 19) {
                        int target = number(token.get(1));
                        state.put(target, Base64.getEncoder().encodeToString(string(state.get(target)).getBytes(StandardCharsets.UTF_8)));
                    }
                } catch (Exception ignored) {
                }
            }
            return result;
        } catch (Exception exception) {
            return "";
        }
    }

    private Map<Integer, Object> seed(List<List<Object>> tokens, String key) {
        Map<Integer, Object> state = new LinkedHashMap<Integer, Object>();
        state.put(9, tokens);
        state.put(10, "window");
        state.put(16, key);
        return state;
    }

    private void invokeReturn(Map<Integer, Object> state, List<Object> token, long start) {
        int destination = number(token.get(1));
        String target = string(state.get(number(token.get(2))));
        if ("window.performance.now".equals(target)) {
            state.put(destination, (System.nanoTime() - start) / 1000000.0d + random.nextDouble());
        } else if ("window.Object.create".equals(target)) {
            state.put(destination, new LinkedHashMap<String, Object>());
        } else if ("window.Object.keys".equals(target)) {
            List<String> keys = new ArrayList<String>();
            keys.add("STATSIG_LOCAL_STORAGE_INTERNAL_STORE_V4");
            keys.add("STATSIG_LOCAL_STORAGE_STABLE_ID");
            keys.add("client-correlated-secret");
            keys.add("oai/apps/capExpiresAt");
            keys.add("oai-did");
            state.put(destination, keys);
        } else if ("window.Math.random".equals(target)) {
            state.put(destination, random.nextDouble());
        }
    }

    private String xor(String value, String key) {
        if (key == null || key.isEmpty()) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            result.append((char) (value.charAt(index) ^ key.charAt(index % key.length())));
        }
        return result.toString();
    }

    private int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
    }

    private String string(Object value) {
        if (value == null) {
            return "undefined";
        }
        if ("window.Math".equals(value)) {
            return "[object Math]";
        }
        if ("window.Reflect".equals(value)) {
            return "[object Reflect]";
        }
        if ("window.performance".equals(value)) {
            return "[object Performance]";
        }
        if ("window.localStorage".equals(value)) {
            return "[object Storage]";
        }
        return String.valueOf(value);
    }
}
