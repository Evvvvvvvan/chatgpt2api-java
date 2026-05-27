package com.chatgpt2api.register;

import com.chatgpt2api.account.AccountService;
import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.http.UpstreamHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OpenAiRegisterClient {
    private static final String AUTH = "https://auth.openai.com";
    private static final String PLATFORM = "https://platform.openai.com";
    private static final String CLIENT_ID = "app_2SKx67EdpoN0G6j64rFvigXD";
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.4 Safari/605.1.15";
    private static final Pattern CODE = Pattern.compile("[?&]code=([^&]+)");
    private final UpstreamHttpClient http;
    private final MailProviderService mail;
    private final AccountService accounts;
    private final SecureRandom random = new SecureRandom();

    public OpenAiRegisterClient(UpstreamHttpClient http, MailProviderService mail, AccountService accounts) {
        this.http = http;
        this.mail = mail;
        this.accounts = accounts;
    }

    public Map<String, Object> register(Map<String, Object> config, Logger logger) {
        @SuppressWarnings("unchecked")
        Map<String, Object> mailConfig = config.get("mail") instanceof Map ? (Map<String, Object>) config.get("mail") : new LinkedHashMap<String, Object>();
        logger.add("creating mailbox", "info");
        Map<String, Object> mailbox = mail.createMailbox(mailConfig);
        String email = AppConfigService.clean(mailbox.get("address"));
        String password = password();
        String deviceId = UUID.randomUUID().toString();
        String verifier = token(64);
        String challenge = challenge(verifier);
        UpstreamHttpClient.Session session = http.session();
        String authorize = authorizeUrl(email, deviceId, challenge);
        logger.add("starting authorization for " + email, "info");
        UpstreamHttpClient.Response initial = session.get(authorize, navigateHeaders(), 30);
        initial.requireSuccess("register_authorize");
        Map<String, String> headers = jsonHeaders(deviceId, AUTH + "/create-account/password");
        headers.put("openai-sentinel-token", sentinelToken(session, deviceId, "username_password_create"));
        UpstreamHttpClient.Response created = session.postJson(AUTH + "/api/accounts/user/register", headers,
                map("username", email, "password", password), 30);
        created.requireSuccess("register_user");
        UpstreamHttpClient.Response sent = session.get(AUTH + "/api/accounts/email-otp/send", navigateHeaders(), 30);
        sent.requireSuccess("send_otp");
        logger.add("waiting for email verification code", "info");
        String code = mail.waitForCode(mailConfig, mailbox);
        if (code.isEmpty()) {
            throw new IllegalStateException("email verification code timeout");
        }
        headers = jsonHeaders(deviceId, AUTH + "/email-verification");
        UpstreamHttpClient.Response validated = session.postJson(AUTH + "/api/accounts/email-otp/validate", headers, map("code", code), 30);
        if (validated.getStatus() >= 400) {
            headers.put("openai-sentinel-token", sentinelToken(session, deviceId, "authorize_continue"));
            validated = session.postJson(AUTH + "/api/accounts/email-otp/validate", headers, map("code", code), 30);
        }
        validated.requireSuccess("validate_otp");
        headers = jsonHeaders(deviceId, AUTH + "/about-you");
        headers.put("openai-sentinel-token", sentinelToken(session, deviceId, "oauth_create_account"));
        UpstreamHttpClient.Response profile = session.postJson(AUTH + "/api/accounts/create_account", headers,
                map("name", randomName(), "birthdate", "2000-01-01"), 30);
        profile.requireSuccess("create_account");
        String continueUrl = first(profile.jsonObject().get("continue_url"), validated.jsonObject().get("continue_url"));
        if (continueUrl.isEmpty()) {
            continueUrl = authorize;
        } else if (continueUrl.startsWith("/")) {
            continueUrl = AUTH + continueUrl;
        }
        UpstreamHttpClient.Response consent = session.get(continueUrl, navigateHeaders(), 30);
        String authorizationCode = extractCode(first(consent.getFinalUrl(), consent.header("Location"), continueUrl));
        if (authorizationCode.isEmpty()) {
            throw new IllegalStateException("oauth authorization code was not returned after account creation");
        }
        String form = "grant_type=authorization_code&code=" + encode(authorizationCode)
                + "&redirect_uri=" + encode(PLATFORM + "/auth/callback") + "&client_id=" + CLIENT_ID
                + "&code_verifier=" + encode(verifier);
        UpstreamHttpClient.Response tokenResponse = session.postForm(AUTH + "/oauth/token", mapString("User-Agent", USER_AGENT), form, 60);
        tokenResponse.requireSuccess("oauth_token");
        Map<String, Object> tokens = tokenResponse.jsonObject();
        String accessToken = AppConfigService.clean(tokens.get("access_token"));
        if (accessToken.isEmpty()) {
            throw new IllegalStateException("oauth token response has no access_token");
        }
        Map<String, Object> account = map("email", email, "password", password, "access_token", accessToken,
                "refresh_token", tokens.get("refresh_token"), "id_token", tokens.get("id_token"), "created_at", Instant.now().toString());
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<Map<String, Object>>();
        items.add(account);
        accounts.addAccountItems(items);
        accounts.refreshAccounts(java.util.Collections.singletonList(accessToken));
        logger.add("registered account " + email, "success");
        return account;
    }

    private String sentinelToken(UpstreamHttpClient.Session session, String deviceId, String flow) {
        String proof = sentinelProof("", "", false);
        Map<String, String> headers = mapString("Content-Type", "text/plain;charset=UTF-8", "Referer",
                "https://sentinel.openai.com/backend-api/sentinel/frame.html", "Origin", "https://sentinel.openai.com", "User-Agent", USER_AGENT);
        Map<String, Object> body = map("p", proof, "id", deviceId, "flow", flow);
        UpstreamHttpClient.Response response = session.postJson("https://sentinel.openai.com/backend-api/sentinel/req", headers, body, 20);
        response.requireSuccess("sentinel_req");
        Map<String, Object> result = response.jsonObject();
        String challenge = AppConfigService.clean(result.get("token"));
        @SuppressWarnings("unchecked")
        Map<String, Object> required = result.get("proofofwork") instanceof Map ? (Map<String, Object>) result.get("proofofwork") : new LinkedHashMap<String, Object>();
        String finalProof = AppConfigService.booleanValue(required.get("required"), false)
                ? sentinelProof(AppConfigService.clean(required.get("seed")), AppConfigService.clean(required.get("difficulty")), true) : proof;
        return "{\"p\":\"" + finalProof + "\",\"t\":\"\",\"c\":\"" + challenge + "\",\"id\":\"" + deviceId + "\",\"flow\":\"" + flow + "\"}";
    }

    private String sentinelProof(String seed, String difficulty, boolean solve) {
        List<Object> values = new ArrayList<Object>();
        values.add("1920x1080");
        values.add(Instant.now().toString());
        values.add(4294705152L);
        values.add(1);
        values.add(USER_AGENT);
        values.add("https://sentinel.openai.com/sentinel/20260124ceb8/sdk.js");
        values.add(null);
        values.add(null);
        values.add("en-US");
        values.add(10);
        values.add("hardwareConcurrency-undefined");
        values.add("location");
        values.add("Object");
        values.add(System.nanoTime() / 1000000.0d);
        values.add(UUID.randomUUID().toString());
        values.add("");
        values.add(8);
        values.add(System.currentTimeMillis());
        for (int count = 0; count < (solve ? 500000 : 1); count++) {
            values.set(3, count);
            values.set(9, count);
            String encoded;
            try {
                encoded = Base64.getEncoder().encodeToString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(values));
            } catch (Exception exception) {
                throw new IllegalStateException("unable to serialize sentinel proof", exception);
            }
            if (!solve || fnv(seed + encoded).substring(0, difficulty.length()).compareTo(difficulty) <= 0) {
                return (solve ? "gAAAAAB" : "gAAAAAC") + encoded + (solve ? "~S" : "");
            }
        }
        throw new IllegalStateException("unable to solve registration sentinel proof");
    }

    private String fnv(String value) {
        long hash = 2166136261L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash = (hash * 16777619L) & 0xffffffffL;
        }
        hash ^= hash >>> 16;
        hash = (hash * 2246822507L) & 0xffffffffL;
        hash ^= hash >>> 13;
        hash = (hash * 3266489909L) & 0xffffffffL;
        hash ^= hash >>> 16;
        return String.format("%08x", hash & 0xffffffffL);
    }

    private String authorizeUrl(String email, String deviceId, String challenge) {
        return AUTH + "/api/accounts/authorize?issuer=" + encode(AUTH) + "&client_id=" + CLIENT_ID
                + "&audience=" + encode("https://api.openai.com/v1") + "&redirect_uri=" + encode(PLATFORM + "/auth/callback")
                + "&device_id=" + encode(deviceId) + "&screen_hint=login_or_signup&max_age=0&login_hint=" + encode(email)
                + "&scope=" + encode("openid profile email offline_access") + "&response_type=code&response_mode=query"
                + "&state=" + token(32) + "&nonce=" + token(32) + "&code_challenge=" + encode(challenge) + "&code_challenge_method=S256";
    }

    private Map<String, String> jsonHeaders(String deviceId, String referer) {
        return mapString("Accept", "application/json", "Content-Type", "application/json", "Origin", AUTH, "Referer", referer,
                "User-Agent", USER_AGENT, "oai-device-id", deviceId);
    }

    private Map<String, String> navigateHeaders() {
        return mapString("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "User-Agent", USER_AGENT);
    }

    private String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException("unable to generate PKCE challenge", exception);
        }
    }

    private String extractCode(String url) {
        Matcher matcher = CODE.matcher(url == null ? "" : url);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String randomName() {
        return "Alex " + new String[]{"Smith", "Johnson", "Brown", "Miller"}[random.nextInt(4)];
    }

    private String password() {
        return token(12) + "A1!";
    }

    private String token(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception exception) {
            return "";
        }
    }

    private String first(Object... values) {
        for (Object value : values) {
            String text = AppConfigService.clean(value);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private Map<String, String> mapString(String... values) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }

    public interface Logger {
        void add(String text, String level);
    }
}
