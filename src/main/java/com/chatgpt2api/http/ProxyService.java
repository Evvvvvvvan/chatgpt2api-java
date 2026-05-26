package com.chatgpt2api.http;

import com.chatgpt2api.config.AppConfigService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ProxyService {
    private final UpstreamHttpClient http;

    public ProxyService(UpstreamHttpClient http) {
        this.http = http;
    }

    public Map<String, Object> test(String url) {
        String candidate = AppConfigService.clean(url);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (candidate.isEmpty()) {
            return result(false, 0, 0, "proxy url is required");
        }
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException exception) {
            return result(false, 0, 0, "invalid proxy url");
        }
        String scheme = AppConfigService.clean(uri.getScheme()).toLowerCase();
        if (uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme) || "socks5".equals(scheme) || "socks5h".equals(scheme))) {
            return result(false, 0, 0, "invalid proxy url");
        }
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", "Mozilla/5.0 (chatgpt2api proxy test)");
        long started = System.nanoTime();
        try {
            UpstreamHttpClient.Response response = http.sessionWithProxy(candidate)
                    .get("https://chatgpt.com/api/auth/csrf", headers, 15);
            int status = response.getStatus();
            return result(status < 500, status, elapsed(started), status < 500 ? null : "HTTP " + status);
        } catch (RuntimeException exception) {
            return result(false, 0, elapsed(started), exception.getMessage());
        }
    }

    private int elapsed(long started) {
        return (int) ((System.nanoTime() - started) / 1000000L);
    }

    private Map<String, Object> result(boolean ok, int status, int latency, String error) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", ok);
        result.put("status", status);
        result.put("latency_ms", latency);
        result.put("error", error);
        return result;
    }
}
