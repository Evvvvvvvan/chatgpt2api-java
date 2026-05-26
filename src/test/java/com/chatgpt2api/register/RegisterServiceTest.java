package com.chatgpt2api.register;

import com.chatgpt2api.account.AccountService;
import com.chatgpt2api.config.AppConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegisterServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void completesConfiguredTotalRegistrationJob() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Files.write(tempDir.resolve("config.json"), "{\"auth-key\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        AppConfigService config = new AppConfigService(mapper, tempDir.toString());
        config.initialize();
        AccountService accounts = mock(AccountService.class);
        when(accounts.listAccounts()).thenReturn(new java.util.ArrayList<Map<String, Object>>());
        OpenAiRegisterClient client = mock(OpenAiRegisterClient.class);
        when(client.register(any(Map.class), any(OpenAiRegisterClient.Logger.class))).thenReturn(map("access_token", "token"));
        RegisterService service = new RegisterService(mapper, config, accounts, client);
        service.initialize();
        service.update(map("total", 1, "threads", 1));

        service.start();
        Map<String, Object> current = waitFor(service);

        Map<?, ?> stats = (Map<?, ?>) current.get("stats");
        assertEquals(1, stats.get("success"));
        assertEquals(false, current.get("enabled"));
    }

    @Test
    void resumesEnabledRegistrationJobAfterRestart() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Files.write(tempDir.resolve("config.json"), "{\"auth-key\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(tempDir.resolve("data"));
        Files.write(tempDir.resolve("data/register.json"), "{\"enabled\":true,\"total\":1,\"threads\":1}".getBytes(StandardCharsets.UTF_8));
        AppConfigService config = new AppConfigService(mapper, tempDir.toString());
        config.initialize();
        AccountService accounts = mock(AccountService.class);
        when(accounts.listAccounts()).thenReturn(new java.util.ArrayList<Map<String, Object>>());
        OpenAiRegisterClient client = mock(OpenAiRegisterClient.class);
        when(client.register(any(Map.class), any(OpenAiRegisterClient.Logger.class))).thenReturn(map("access_token", "token"));
        RegisterService service = new RegisterService(mapper, config, accounts, client);

        service.initialize();
        Map<String, Object> current = waitFor(service);

        assertEquals(1, ((Map<?, ?>) current.get("stats")).get("success"));
        assertEquals(false, current.get("enabled"));
    }

    private Map<String, Object> waitFor(RegisterService service) throws Exception {
        for (int count = 0; count < 100; count++) {
            Map<String, Object> result = service.get();
            Map<?, ?> stats = (Map<?, ?>) result.get("stats");
            if (Integer.valueOf(1).equals(stats.get("success"))) {
                return result;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("registration job did not finish");
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
