package com.chatgpt2api.image;

import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.log.LogService;
import com.chatgpt2api.protocol.AiProtocolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageTaskServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsAndReturnsIdempotentImageTasks() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Files.write(tempDir.resolve("config.json"), "{\"auth-key\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        AppConfigService config = new AppConfigService(mapper, tempDir.toString());
        config.initialize();
        AiProtocolService protocols = mock(AiProtocolService.class);
        when(protocols.generateImages(any(Map.class), anyString()))
                .thenReturn(map("created", 1L, "data", Arrays.asList(map("url", "/images/result.png"))));
        ImageTaskService service = new ImageTaskService(mapper, config, protocols, mock(LogService.class));
        service.initialize();
        Map<String, Object> identity = map("id", "admin");
        Map<String, Object> body = map("client_task_id", "task-1", "prompt", "draw");

        service.submitGeneration(identity, body, "http://localhost");
        Map<String, Object> result = waitFor(service, identity);
        Map<String, Object> duplicate = service.submitGeneration(identity, body, "http://localhost");

        assertEquals("success", ((Map<?, ?>) ((java.util.List<?>) result.get("items")).get(0)).get("status"));
        assertEquals("success", duplicate.get("status"));
        assertEquals(true, Files.isRegularFile(config.getDataDir().resolve("image_tasks.json")));
    }

    private Map<String, Object> waitFor(ImageTaskService service, Map<String, Object> identity) throws Exception {
        for (int count = 0; count < 50; count++) {
            Map<String, Object> result = service.list(identity, "task-1");
            Object status = ((Map<?, ?>) ((java.util.List<?>) result.get("items")).get(0)).get("status");
            if ("success".equals(status)) {
                return result;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("task did not finish");
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
