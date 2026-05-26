package com.chatgpt2api.register;

import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.http.UpstreamHttpClient;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MailProviderService {
    private static final Pattern CODE = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
    private final UpstreamHttpClient http;
    private final SecureRandom random = new SecureRandom();
    private int providerIndex;

    public MailProviderService(UpstreamHttpClient http) {
        this.http = http;
    }

    public synchronized Map<String, Object> createMailbox(Map<String, Object> mailConfig) {
        List<Map<String, Object>> providers = enabledProviders(mailConfig);
        if (providers.isEmpty()) {
            throw new IllegalStateException("mail.providers has no enabled provider");
        }
        RuntimeException last = null;
        for (int count = 0; count < providers.size(); count++) {
            Map<String, Object> provider = providers.get(providerIndex++ % providers.size());
            try {
                return create(provider);
            } catch (RuntimeException exception) {
                last = exception;
            }
        }
        throw last == null ? new IllegalStateException("unable to create mailbox") : last;
    }

    public String waitForCode(Map<String, Object> mailConfig, Map<String, Object> mailbox) {
        int timeout = AppConfigService.intValue(mailConfig.get("wait_timeout"), 30, 1);
        int interval = AppConfigService.intValue(mailConfig.get("wait_interval"), 2, 1);
        long end = System.currentTimeMillis() + timeout * 1000L;
        while (System.currentTimeMillis() < end) {
            String content = fetch(mailConfig, mailbox);
            Matcher matcher = CODE.matcher(content);
            while (matcher.find()) {
                if (!"177010".equals(matcher.group(1))) {
                    return matcher.group(1);
                }
            }
            try {
                Thread.sleep(interval * 1000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
        return "";
    }

    private Map<String, Object> create(Map<String, Object> provider) {
        String type = AppConfigService.clean(provider.get("type"));
        String local = randomName();
        UpstreamHttpClient.Session session = http.session();
        if ("cloudflare_temp_email".equals(type)) {
            String domain = domain(provider, "domain");
            Map<String, Object> body = map("enablePrefix", true, "name", local, "domain", domain);
            Map<String, String> headers = mapString("x-admin-auth", AppConfigService.clean(provider.get("admin_password")));
            Map<String, Object> data = post(session, url(provider, "api_base", "/admin/new_address"), headers, body);
            return mailbox(type, provider, data.get("address"), data.get("jwt"), null);
        }
        if ("cloudmail_gen".equals(type) || "inbucket".equals(type)) {
            return mailbox(type, provider, local + "@" + domain(provider, "domain"), "", null);
        }
        if ("tempmail_lol".equals(type)) {
            Map<String, Object> data = post(session, "https://api.tempmail.lol/v2/inbox/create", auth(provider), map("domain", domainOptional(provider)));
            return mailbox(type, provider, data.get("address"), data.get("token"), null);
        }
        if ("duckmail".equals(type)) {
            String address = local + "@" + value(provider.get("default_domain"), "duckmail.sbs");
            String password = password();
            Map<String, Object> payload = map("address", address, "password", password);
            post(session, "https://api.duckmail.sbs/accounts", bearer(AppConfigService.clean(provider.get("api_key"))), payload);
            Map<String, Object> token = post(session, "https://api.duckmail.sbs/token", bearer(AppConfigService.clean(provider.get("api_key"))), payload);
            return mailbox(type, provider, address, token.get("token"), password);
        }
        if ("gptmail".equals(type)) {
            Map<String, Object> data = post(session, "https://mail.chatgpt.org.uk/api/generate-email",
                    mapString("X-API-Key", AppConfigService.clean(provider.get("api_key"))),
                    map("prefix", local, "domain", AppConfigService.clean(provider.get("default_domain"))));
            data = unwrap(data);
            return mailbox(type, provider, data.get("email"), "", null);
        }
        if ("moemail".equals(type)) {
            Map<String, Object> data = post(session, url(provider, "api_base", "/api/emails/generate"),
                    mapString("X-API-Key", AppConfigService.clean(provider.get("api_key"))),
                    map("name", local, "domain", domain(provider, "domain"), "expiryTime", provider.get("expiry_time")));
            Map<String, Object> result = mailbox(type, provider, data.get("email"), "", null);
            result.put("email_id", data.get("id"));
            return result;
        }
        if ("yyds_mail".equals(type)) {
            Map<String, Object> data = post(session, url(provider, "api_base", "/accounts"),
                    mapString("X-API-Key", AppConfigService.clean(provider.get("api_key"))),
                    map("localPart", local, "domain", domainOptional(provider), "subdomain", provider.get("subdomain")));
            data = unwrap(data);
            return mailbox(type, provider, first(data.get("address"), data.get("email")),
                    first(data.get("token"), data.get("temp_token"), data.get("access_token")), null);
        }
        if ("ddg_mail".equals(type)) {
            Map<String, Object> ddg = post(session, "https://quack.duckduckgo.com/api/email/addresses",
                    bearer(AppConfigService.clean(provider.get("ddg_token"))), new LinkedHashMap<String, Object>());
            return mailbox(type, provider, AppConfigService.clean(ddg.get("address")) + "@duck.com",
                    provider.get("cf_inbox_jwt"), null);
        }
        throw new IllegalStateException("unsupported mail.provider: " + type);
    }

    private String fetch(Map<String, Object> mailConfig, Map<String, Object> mailbox) {
        String type = AppConfigService.clean(mailbox.get("provider"));
        Map<String, Object> provider = providerByType(mailConfig, type);
        UpstreamHttpClient.Session session = http.session();
        Object payload;
        if ("cloudflare_temp_email".equals(type) || "ddg_mail".equals(type)) {
            String base = "ddg_mail".equals(type) ? first(provider.get("api_base"), provider.get("cf_api_base")) : AppConfigService.clean(provider.get("api_base"));
            payload = get(session, base + "/api/mails?limit=30&offset=0", bearer(AppConfigService.clean(mailbox.get("token"))));
        } else if ("cloudmail_gen".equals(type)) {
            Map<String, Object> token = post(session, url(provider, "api_base", "/api/public/genToken"), new LinkedHashMap<String, String>(),
                    map("email", provider.get("admin_email"), "password", provider.get("admin_password")));
            payload = post(session, url(provider, "api_base", "/api/public/emailList"), mapString("Authorization", nested(token, "data", "token")),
                    map("toEmail", mailbox.get("address"), "size", 20, "timeSort", "desc"));
        } else if ("tempmail_lol".equals(type)) {
            payload = get(session, "https://api.tempmail.lol/v2/inbox?token=" + encode(mailbox.get("token")), auth(provider));
        } else if ("duckmail".equals(type)) {
            payload = get(session, "https://api.duckmail.sbs/messages?page=1", bearer(AppConfigService.clean(mailbox.get("token"))));
        } else if ("gptmail".equals(type)) {
            payload = get(session, "https://mail.chatgpt.org.uk/api/emails?email=" + encode(mailbox.get("address")),
                    mapString("X-API-Key", AppConfigService.clean(provider.get("api_key"))));
        } else if ("moemail".equals(type)) {
            payload = get(session, url(provider, "api_base", "/api/emails/" + AppConfigService.clean(mailbox.get("email_id"))),
                    mapString("X-API-Key", AppConfigService.clean(provider.get("api_key"))));
        } else if ("inbucket".equals(type)) {
            payload = get(session, url(provider, "api_base", "/api/v1/mailbox/" + mailboxName(mailbox)), new LinkedHashMap<String, String>());
        } else if ("yyds_mail".equals(type)) {
            payload = get(session, url(provider, "api_base", "/messages?address=" + encode(mailbox.get("address"))),
                    bearer(AppConfigService.clean(mailbox.get("token"))));
        } else {
            return "";
        }
        return flatten(payload);
    }

    private Map<String, Object> post(UpstreamHttpClient.Session session, String url, Map<String, String> headers, Object body) {
        UpstreamHttpClient.Response response = session.postJson(url, headers, body, 30);
        response.requireSuccess("mail_provider");
        return response.jsonObject();
    }

    private Object get(UpstreamHttpClient.Session session, String url, Map<String, String> headers) {
        UpstreamHttpClient.Response response = session.get(url, headers, 30);
        response.requireSuccess("mail_provider");
        return response.json();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> enabledProviders(Map<String, Object> mailConfig) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Object raw = mailConfig.get("providers");
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item instanceof Map && AppConfigService.booleanValue(((Map<String, Object>) item).get("enable"), false)) {
                    result.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
                }
            }
        }
        return result;
    }

    private Map<String, Object> providerByType(Map<String, Object> mailConfig, String type) {
        for (Map<String, Object> provider : enabledProviders(mailConfig)) {
            if (type.equals(provider.get("type"))) {
                return provider;
            }
        }
        throw new IllegalStateException("mail provider is no longer enabled: " + type);
    }

    private Map<String, Object> mailbox(String type, Map<String, Object> provider, Object address, Object token, String password) {
        Map<String, Object> result = map("provider", type, "address", AppConfigService.clean(address), "token", AppConfigService.clean(token));
        if (AppConfigService.clean(result.get("address")).isEmpty()) {
            throw new IllegalStateException("mail provider returned no address");
        }
        if (password != null) {
            result.put("password", password);
        }
        return result;
    }

    private String flatten(Object value) {
        return String.valueOf(value);
    }

    private String nested(Map<String, Object> source, String first, String second) {
        Object value = source.get(first);
        return value instanceof Map ? AppConfigService.clean(((Map<?, ?>) value).get(second)) : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrap(Map<String, Object> value) {
        Object data = value.get("data");
        return data instanceof Map ? (Map<String, Object>) data : value;
    }

    private String url(Map<String, Object> provider, String field, String suffix) {
        String base = AppConfigService.clean(provider.get(field)).replaceAll("/+$", "");
        if (base.isEmpty()) {
            throw new IllegalStateException(field + " is required");
        }
        return base + suffix;
    }

    private String domain(Map<String, Object> provider, String field) {
        String result = domainOptional(provider);
        if (result.isEmpty()) {
            throw new IllegalStateException(field + " is required");
        }
        return result;
    }

    private String domainOptional(Map<String, Object> provider) {
        Object raw = provider.get("domain");
        if (raw instanceof List && !((List<?>) raw).isEmpty()) {
            return AppConfigService.clean(((List<?>) raw).get(0));
        }
        return AppConfigService.clean(raw);
    }

    private String mailboxName(Map<String, Object> mailbox) {
        String address = AppConfigService.clean(mailbox.get("address"));
        int at = address.indexOf('@');
        return at < 0 ? address : address.substring(0, at);
    }

    private String randomName() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < 10; index++) {
            result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return result.toString();
    }

    private String password() {
        return randomName() + "A!2";
    }

    private String encode(Object value) {
        try {
            return URLEncoder.encode(AppConfigService.clean(value), StandardCharsets.UTF_8.name());
        } catch (Exception exception) {
            return "";
        }
    }

    private Map<String, String> bearer(String token) {
        return mapString("Authorization", "Bearer " + token);
    }

    private Map<String, String> auth(Map<String, Object> provider) {
        String token = AppConfigService.clean(provider.get("api_key"));
        return token.isEmpty() ? new LinkedHashMap<String, String>() : bearer(token);
    }

    private String value(Object input, String fallback) {
        String output = AppConfigService.clean(input);
        return output.isEmpty() ? fallback : output;
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
}
