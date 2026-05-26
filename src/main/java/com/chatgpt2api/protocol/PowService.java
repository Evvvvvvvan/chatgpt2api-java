package com.chatgpt2api.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PowService {
    private static final String DEFAULT_SCRIPT = "https://chatgpt.com/backend-api/sentinel/sdk.js";
    private static final Pattern SCRIPT = Pattern.compile("<script[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUILD = Pattern.compile("(?:data-build=[\"']([^\"']+)[\"']|c/[^/]+/_)", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public PowService(ObjectMapper mapper) {
        this.mapper = mapper;
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public Resources parseResources(String html) {
        List<String> scripts = new ArrayList<String>();
        Matcher matcher = SCRIPT.matcher(html == null ? "" : html);
        while (matcher.find()) {
            scripts.add(matcher.group(1));
        }
        if (scripts.isEmpty()) {
            scripts.add(DEFAULT_SCRIPT);
        }
        String build = "";
        Matcher buildMatcher = BUILD.matcher(html == null ? "" : html);
        if (buildMatcher.find()) {
            build = buildMatcher.group(1) == null ? buildMatcher.group(0) : buildMatcher.group(1);
        }
        return new Resources(scripts, build);
    }

    public String legacyToken(String userAgent, Resources resources) {
        return "gAAAAAC" + solve(String.valueOf(random.nextDouble()), "0fffff", userAgent, resources);
    }

    public String proofToken(String seed, String difficulty, String userAgent, Resources resources) {
        return "gAAAAAB" + solve(seed, difficulty, userAgent, resources);
    }

    private String solve(String seed, String difficulty, String userAgent, Resources resources) {
        try {
            byte[] target = hex(difficulty);
            int compareLength = difficulty.length() / 2;
            List<Object> config = buildConfig(userAgent, resources);
            for (int index = 0; index < 500000; index++) {
                config.set(3, index);
                config.set(9, index >> 1);
                String encoded = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(config));
                MessageDigest digest = MessageDigest.getInstance("SHA3-512", BouncyCastleProvider.PROVIDER_NAME);
                byte[] payload = digest.digest((seed + encoded).getBytes(StandardCharsets.UTF_8));
                if (lessOrEqual(payload, target, compareLength)) {
                    return encoded;
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to build proof token", exception);
        }
        throw new IllegalStateException("failed to solve proof token: difficulty=" + difficulty);
    }

    private List<Object> buildConfig(String userAgent, Resources resources) {
        List<String> navigator = Arrays.asList(
                "webdriver-false", "vendor-Google Inc.", "language-zh-CN", "cookieEnabled-true",
                "hardwareConcurrency-12", "pdfViewerEnabled-true");
        List<String> window = Arrays.asList("window", "document", "location", "navigator", "fetch", "crypto");
        List<Object> result = new ArrayList<Object>();
        result.add(3000 + random.nextInt(3) * 1000);
        result.add(ZonedDateTime.now(ZoneOffset.ofHours(-5)).format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss", Locale.ENGLISH)) + " GMT-0500 (Eastern Standard Time)");
        result.add(4294705152L);
        result.add(0);
        result.add(userAgent);
        result.add(resources.scripts.get(random.nextInt(resources.scripts.size())));
        result.add(resources.dataBuild);
        result.add("en-US");
        result.add("en-US,es-US,en,es");
        result.add(0);
        result.add(navigator.get(random.nextInt(navigator.size())));
        result.add(random.nextBoolean() ? "location" : "_reactListeningo743lnnpvdg");
        result.add(window.get(random.nextInt(window.size())));
        result.add(System.nanoTime() / 1000000.0d);
        result.add(UUID.randomUUID().toString());
        result.add("");
        result.add(Arrays.asList(8, 16, 24, 32).get(random.nextInt(4)));
        result.add(System.currentTimeMillis() - (System.nanoTime() / 1000000.0d));
        return result;
    }

    private byte[] hex(String value) {
        String source = value.length() % 2 == 0 ? value : "0" + value;
        byte[] result = new byte[source.length() / 2];
        for (int index = 0; index < source.length(); index += 2) {
            result[index / 2] = (byte) Integer.parseInt(source.substring(index, index + 2), 16);
        }
        return result;
    }

    private boolean lessOrEqual(byte[] value, byte[] target, int length) {
        for (int index = 0; index < length; index++) {
            int left = value[index] & 0xff;
            int right = target[index] & 0xff;
            if (left != right) {
                return left < right;
            }
        }
        return true;
    }

    public static final class Resources {
        private final List<String> scripts;
        private final String dataBuild;

        private Resources(List<String> scripts, String dataBuild) {
            this.scripts = scripts;
            this.dataBuild = dataBuild;
        }
    }
}
