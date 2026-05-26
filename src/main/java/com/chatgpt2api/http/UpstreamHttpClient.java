package com.chatgpt2api.http;

import com.chatgpt2api.config.AppConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UpstreamHttpClient {
    private final AppConfigService config;
    private final ObjectMapper mapper;

    public UpstreamHttpClient(AppConfigService config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    public Session session() {
        return new Session(config.getProxySettings());
    }

    public Session sessionWithProxy(String proxy) {
        return new Session(AppConfigService.clean(proxy));
    }

    public final class Session {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final Path nativeCookieFile = temporary("chatgpt2api-cookies-", ".txt");
        private final String proxy;

        private Session(String proxy) {
            this.proxy = proxy;
        }

        public Response get(String url, Map<String, String> headers, int timeoutSeconds) {
            return execute("GET", url, headers, null, timeoutSeconds);
        }

        public Response postJson(String url, Map<String, String> headers, Object body, int timeoutSeconds) {
            Map<String, String> next = new LinkedHashMap<String, String>(headers);
            next.put("Content-Type", "application/json");
            try {
                return execute("POST", url, next, mapper.writeValueAsBytes(body), timeoutSeconds);
            } catch (IOException exception) {
                throw new IllegalStateException("unable to serialize upstream request", exception);
            }
        }

        public Response postForm(String url, Map<String, String> headers, String body, int timeoutSeconds) {
            Map<String, String> next = new LinkedHashMap<String, String>(headers);
            next.put("Content-Type", "application/x-www-form-urlencoded");
            return execute("POST", url, next, body.getBytes(StandardCharsets.UTF_8), timeoutSeconds);
        }

        public Response putBytes(String url, Map<String, String> headers, byte[] body, int timeoutSeconds) {
            return execute("PUT", url, headers, body, timeoutSeconds);
        }

        private Response execute(String method, String url, Map<String, String> headers, byte[] body, int timeoutSeconds) {
            String nativeBinary = AppConfigService.clean(System.getenv("CHATGPT2API_CURL_BIN"));
            if (!nativeBinary.isEmpty() && Files.isRegularFile(java.nio.file.Paths.get(nativeBinary))) {
                return executeNative(nativeBinary, method, url, headers, body, timeoutSeconds);
            }
            HttpURLConnection connection = null;
            try {
                URI uri = URI.create(url);
                connection = (HttpURLConnection) new URL(url).openConnection(createProxy(proxy));
                connection.setRequestMethod(method);
                connection.setConnectTimeout(timeoutSeconds * 1000);
                connection.setReadTimeout(timeoutSeconds * 1000);
                connection.setInstanceFollowRedirects(true);
                connection.setUseCaches(false);
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getValue() != null && !header.getValue().isEmpty()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
                Map<String, List<String>> cookieHeaders = cookies.get(uri, Collections.<String, List<String>>emptyMap());
                for (Map.Entry<String, List<String>> header : cookieHeaders.entrySet()) {
                    for (String value : header.getValue()) {
                        connection.addRequestProperty(header.getKey(), value);
                    }
                }
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    OutputStream output = connection.getOutputStream();
                    output.write(body);
                    output.close();
                }
                int status = connection.getResponseCode();
                cookies.put(uri, connection.getHeaderFields());
                InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                byte[] bytes = source == null ? new byte[0] : readBytes(source);
                return new Response(status, connection.getHeaderFields(), bytes, connection.getURL().toString());
            } catch (IOException exception) {
                throw new IllegalStateException("upstream request failed: " + method + " " + url + ": " + exception.getMessage(), exception);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private Response executeNative(String binary, String method, String url, Map<String, String> headers, byte[] body, int timeoutSeconds) {
            Path responseFile = temporary("chatgpt2api-response-", ".bin");
            Path headerFile = temporary("chatgpt2api-headers-", ".txt");
            Path bodyFile = null;
            try {
                List<String> command = new ArrayList<String>();
                Collections.addAll(command, binary, "--silent", "--show-error", "--location", "--max-time",
                        String.valueOf(timeoutSeconds), "--request", method, "--cookie", nativeCookieFile.toString(),
                        "--cookie-jar", nativeCookieFile.toString(), "--dump-header", headerFile.toString(),
                        "--output", responseFile.toString(), "--write-out", "%{http_code}\\n%{url_effective}");
                if (!proxy.isEmpty()) {
                    Collections.addAll(command, "--proxy", proxy);
                }
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getValue() != null && !header.getValue().isEmpty()) {
                        Collections.addAll(command, "--header", header.getKey() + ": " + header.getValue());
                    }
                }
                if (body != null) {
                    bodyFile = temporary("chatgpt2api-request-", ".bin");
                    Files.write(bodyFile, body);
                    Collections.addAll(command, "--data-binary", "@" + bodyFile.toString());
                }
                command.add(url);
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                String metadata = new String(readBytes(process.getInputStream()), StandardCharsets.UTF_8).trim();
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new IllegalStateException("native upstream request failed: " + metadata);
                }
                String[] lines = metadata.split("\\r?\\n");
                int status = Integer.parseInt(lines[0].trim());
                String effectiveUrl = lines.length > 1 ? lines[lines.length - 1].trim() : url;
                return new Response(status, parseNativeHeaders(headerFile), Files.readAllBytes(responseFile), effectiveUrl);
            } catch (IOException exception) {
                throw new IllegalStateException("native upstream transport failed: " + exception.getMessage(), exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("native upstream request interrupted", exception);
            } finally {
                deleteQuietly(responseFile);
                deleteQuietly(headerFile);
                deleteQuietly(bodyFile);
            }
        }
    }

    public final class Response {
        private final int status;
        private final Map<String, List<String>> headers;
        private final byte[] body;
        private final String finalUrl;

        private Response(int status, Map<String, List<String>> headers, byte[] body, String finalUrl) {
            this.status = status;
            this.headers = headers == null ? new LinkedHashMap<String, List<String>>() : headers;
            this.body = body;
            this.finalUrl = finalUrl;
        }

        public int getStatus() {
            return status;
        }

        public byte[] getBody() {
            return body;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return "";
        }

        public String getFinalUrl() {
            return finalUrl;
        }

        public Map<String, Object> jsonObject() {
            try {
                return mapper.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() { });
            } catch (IOException exception) {
                throw new IllegalStateException("upstream response is not a JSON object: " + text(), exception);
            }
        }

        public Object json() {
            try {
                return mapper.readValue(body, Object.class);
            } catch (IOException exception) {
                throw new IllegalStateException("upstream response is not JSON: " + text(), exception);
            }
        }

        public void requireSuccess(String context) {
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(context + " failed: HTTP " + status + ", body=" + abbreviate(text()));
            }
        }
    }

    private Proxy createProxy(String candidate) {
        if (candidate.isEmpty()) {
            return Proxy.NO_PROXY;
        }
        try {
            URI uri = URI.create(candidate);
            int port = uri.getPort();
            if (port <= 0 || uri.getHost() == null) {
                return Proxy.NO_PROXY;
            }
            Proxy.Type type = uri.getScheme().toLowerCase().startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            return new Proxy(type, new InetSocketAddress(uri.getHost(), port));
        } catch (RuntimeException exception) {
            return Proxy.NO_PROXY;
        }
    }

    private byte[] readBytes(InputStream source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = source.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        source.close();
        return output.toByteArray();
    }

    private Path temporary(String prefix, String suffix) {
        try {
            Path result = Files.createTempFile(prefix, suffix);
            result.toFile().deleteOnExit();
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("unable to create transport temporary file", exception);
        }
    }

    private Map<String, List<String>> parseNativeHeaders(Path path) throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.startsWith("HTTP/")) {
                result.clear();
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            if (!result.containsKey(key)) {
                result.put(key, new ArrayList<String>());
            }
            result.get(key).add(line.substring(separator + 1).trim());
        }
        return result;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private String abbreviate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500) + "...[truncated]";
    }
}
