package com.chatgpt2api.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiProtocolServiceTest {
    @Test
    void mapsChatCompletionAndStreamingChunks() {
        ChatgptWebService backend = mock(ChatgptWebService.class);
        when(backend.textDeltas(anyList(), anyString())).thenReturn(Arrays.asList("hel", "lo"));
        AiProtocolService service = new AiProtocolService(backend, new ObjectMapper());
        Map<String, Object> request = map("model", "auto", "stream", true,
                "messages", Arrays.asList(map("role", "user", "content", "hi")));

        AiProtocolService.ProtocolOutput result = service.chat(request, "http://localhost");

        assertTrue(result.isStream());
        assertEquals("hello", ((Map<?, ?>) ((List<?>) result.getBody().get("choices")).get(0)).get("message") instanceof Map
                ? ((Map<?, ?>) ((Map<?, ?>) ((List<?>) result.getBody().get("choices")).get(0)).get("message")).get("content") : "");
        assertEquals(3, result.getEvents().size());
    }

    @Test
    void mapsImageGenerationResponse() {
        ChatgptWebService backend = mock(ChatgptWebService.class);
        when(backend.images(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(map("created", 1L, "data", Arrays.asList(map("b64_json", "aGVsbG8=", "url", "/images/test.png"))));
        AiProtocolService service = new AiProtocolService(backend, new ObjectMapper());

        Map<String, Object> result = service.generateImages(map("prompt", "draw", "model", "gpt-image-2"), "http://localhost");

        assertEquals(1, ((List<?>) result.get("data")).size());
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
