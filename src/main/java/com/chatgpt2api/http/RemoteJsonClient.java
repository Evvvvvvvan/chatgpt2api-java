package com.chatgpt2api.http;

import com.chatgpt2api.config.AppConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RemoteJsonClient {
    private final ObjectMapper mapper;
    private final AppConfigService config;

    public RemoteJsonClient(ObjectMapper mapper, AppConfigService config) {
        this.mapper = mapper;
        this.config = config;
    }

    public Map<String, Object> get(String url, Map<String, String> headers, Map<String, Object> query) {
        return request(HttpMethod.GET, url, headers, query, null);
    }

    public Map<String, Object> post(String url, Map<String, String> headers, Object body) {
        return request(HttpMethod.POST, url, headers, null, body);
    }

    public Map<String, Object> postForm(String url, Map<String, String> headers, Map<String, String> form) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<String, String>();
        if (form != null) {
            for (Map.Entry<String, String> item : form.entrySet()) {
                body.add(item.getKey(), item.getValue());
            }
        }
        return request(HttpMethod.POST, url, headers, null, body);
    }

    private Map<String, Object> request(
            HttpMethod method,
            String url,
            Map<String, String> headers,
            Map<String, Object> query,
            Object body) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url);
        if (query != null) {
            for (Map.Entry<String, Object> item : query.entrySet()) {
                if (item.getValue() != null && !AppConfigService.clean(item.getValue()).isEmpty()) {
                    builder.queryParam(item.getKey(), item.getValue());
                }
            }
        }
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        if (body instanceof MultiValueMap) {
            requestHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        } else if (body != null) {
            requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        }
        if (headers != null) {
            for (Map.Entry<String, String> item : headers.entrySet()) {
                requestHeaders.set(item.getKey(), item.getValue());
            }
        }
        try {
            ResponseEntity<String> response = template().exchange(
                    builder.build(true).toUri(),
                    method,
                    new HttpEntity<Object>(body, requestHeaders),
                    String.class
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("HTTP " + response.getStatusCodeValue());
            }
            return mapper.readValue(response.getBody() == null ? "{}" : response.getBody(),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (RestClientException exception) {
            throw new IllegalStateException("remote request failed: " + exception.getMessage(), exception);
        } catch (Exception exception) {
            throw new IllegalStateException("remote payload is invalid: " + exception.getMessage(), exception);
        }
    }

    private RestTemplate template() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(60000);
        String proxy = config.getProxySettings();
        if (!proxy.isEmpty()) {
            URI uri = URI.create(proxy);
            if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), uri.getPort())));
            }
        }
        return new RestTemplate(factory);
    }
}
