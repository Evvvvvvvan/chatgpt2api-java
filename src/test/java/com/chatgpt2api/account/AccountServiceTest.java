package com.chatgpt2api.account;

import com.chatgpt2api.storage.JsonStorageBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesCodexExportFieldsAndJwtClaims() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AccountService service = new AccountService(
                new JsonStorageBackend(mapper, tempDir.resolve("accounts.json"), tempDir.resolve("auth_keys.json")),
                mapper
        );
        Map<String, Object> authClaim = new LinkedHashMap<String, Object>();
        authClaim.put("chatgpt_account_id", "acct_123");
        Map<String, Object> profileClaim = new LinkedHashMap<String, Object>();
        profileClaim.put("email", "test@example.com");
        Map<String, Object> accessClaims = new LinkedHashMap<String, Object>();
        accessClaims.put("exp", 0);
        accessClaims.put("iat", 3600);
        accessClaims.put("https://api.openai.com/auth", authClaim);
        accessClaims.put("https://api.openai.com/profile", profileClaim);
        Map<String, Object> idClaims = new LinkedHashMap<String, Object>();
        idClaims.put("email", "fallback@example.com");
        String accessToken = jwt(mapper, accessClaims);
        String idToken = jwt(mapper, idClaims);
        Map<String, Object> account = new LinkedHashMap<String, Object>();
        account.put("type", "codex");
        account.put("access_token", accessToken);
        account.put("refresh_token", "rt_test");
        account.put("id_token", idToken);
        List<Map<String, Object>> imports = new ArrayList<Map<String, Object>>();
        imports.add(account);

        service.addAccountItems(imports);
        List<String> requested = new ArrayList<String>();
        requested.add(accessToken);
        Map<String, String> item = service.buildExportItems(requested).get(0);

        assertEquals("codex", item.get("type"));
        assertEquals("test@example.com", item.get("email"));
        assertEquals("acct_123", item.get("account_id"));
        assertEquals("1970-01-01T08:00:00+08:00", item.get("expired"));
        assertEquals("1970-01-01T09:00:00+08:00", item.get("last_refresh"));
        assertEquals("free", service.listAccounts().get(0).get("type"));
        assertNotNull(service.listAccounts().get(0).get("created_at"));
    }

    @Test
    void selectsAccountsForPeriodicRefreshAndKeepalive() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AccountService service = new AccountService(
                new JsonStorageBackend(mapper, tempDir.resolve("watch-accounts.json"), tempDir.resolve("watch-auth_keys.json")),
                mapper
        );
        Map<String, Object> expiringClaims = new LinkedHashMap<String, Object>();
        expiringClaims.put("exp", java.time.Instant.now().plusSeconds(60).getEpochSecond());
        Map<String, Object> activeClaims = new LinkedHashMap<String, Object>();
        activeClaims.put("exp", java.time.Instant.now().plusSeconds(3 * 24 * 60 * 60).getEpochSecond());
        Map<String, Object> limited = new LinkedHashMap<String, Object>();
        limited.put("access_token", jwt(mapper, expiringClaims));
        limited.put("refresh_token", "rt_limited");
        limited.put("status", "限流");
        Map<String, Object> keepalive = new LinkedHashMap<String, Object>();
        keepalive.put("access_token", jwt(mapper, activeClaims));
        keepalive.put("refresh_token", "rt_keepalive");
        keepalive.put("created_at", "2020-01-01 00:00:00");
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(limited);
        items.add(keepalive);

        service.addAccountItems(items);

        assertEquals(1, service.listLimitedTokens().size());
        assertEquals(limited.get("access_token"), service.listExpiringAccessTokens().get(0));
        assertTrue(service.listRefreshTokenKeepaliveTokens().contains(keepalive.get("access_token")));
    }

    private String jwt(ObjectMapper mapper, Map<String, Object> claims) throws Exception {
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("alg", "none");
        header.put("typ", "JWT");
        return encode(mapper.writeValueAsString(header)) + "." + encode(mapper.writeValueAsString(claims)) + ".sig";
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
