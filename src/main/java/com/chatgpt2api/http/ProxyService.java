package com.chatgpt2api.http;

import com.chatgpt2api.config.AppConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ProxyService {
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
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(new Proxy(("socks5".equals(scheme) || "socks5h".equals(scheme)) ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                new InetSocketAddress(uri.getHost(), port)));
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(15000);
        RestTemplate template = new RestTemplate(factory);
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (chatgpt2api proxy test)");
        long started = System.nanoTime();
        try {
            ResponseEntity<String> response = template.exchange("https://chatgpt.com/api/auth/csrf", HttpMethod.GET,
                    new org.springframework.http.HttpEntity<String>(headers), String.class);
            int status = response.getStatusCodeValue();
            return result(status < 500, status, elapsed(started), status < 500 ? null : "HTTP " + status);
        } catch (RestClientResponseException exception) {
            int status = exception.getRawStatusCode();
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
