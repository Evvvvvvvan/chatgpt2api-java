package com.chatgpt2api.image;

import com.chatgpt2api.common.ApiException;
import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.http.UpstreamHttpClient;
import com.chatgpt2api.protocol.ChatgptWebService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImageInputService {
    private static final Pattern DATA_URL = Pattern.compile("^data:([-+./\\w]+);base64,(.*)$", Pattern.DOTALL);
    private static final Pattern RAW_DATA_URL = Pattern.compile("^data:([-+./\\w]+),(.*)$", Pattern.DOTALL);
    private static final int MAX_BYTES = 50 * 1024 * 1024;
    private final UpstreamHttpClient http;

    public ImageInputService(UpstreamHttpClient http) {
        this.http = http;
    }

    public List<ChatgptWebService.ImageInput> jsonInputs(Map<String, Object> body) {
        Object raw = body.containsKey("images") ? body.get("images") : body.get("image");
        if (raw == null && body.containsKey("image_url")) {
            raw = body.get("image_url");
        }
        List<?> entries = raw instanceof List ? (List<?>) raw : single(raw);
        if (entries.isEmpty()) {
            throw badRequest("image file or image_url is required");
        }
        List<ChatgptWebService.ImageInput> result = new ArrayList<ChatgptWebService.ImageInput>();
        for (int index = 0; index < entries.size(); index++) {
            result.add(decode(entries.get(index), index + 1));
        }
        return result;
    }

    public List<ChatgptWebService.ImageInput> multipartInputs(List<MultipartFile> files, MultiValueMap<String, String> fields) {
        List<ChatgptWebService.ImageInput> result = new ArrayList<ChatgptWebService.ImageInput>();
        if (files != null) {
            for (MultipartFile file : files) {
                try {
                    byte[] bytes = file.getBytes();
                    if (bytes.length == 0) {
                        throw badRequest("image file is empty");
                    }
                    checkSize(bytes);
                    String filename = AppConfigService.clean(file.getOriginalFilename());
                    result.add(new ChatgptWebService.ImageInput(bytes, filename.isEmpty() ? "image.png" : filename,
                            AppConfigService.clean(file.getContentType()).isEmpty() ? "image/png" : file.getContentType()));
                } catch (IOException exception) {
                    throw badRequest("unable to read image file");
                }
            }
        }
        if (fields != null) {
            int index = result.size() + 1;
            for (String key : new String[] {"image", "image[]", "images", "images[]", "image_url", "image_url[]"}) {
                List<String> values = fields.get(key);
                if (values == null) {
                    continue;
                }
                for (String value : values) {
                    if (!AppConfigService.clean(value).isEmpty()) {
                        result.add(decodeString(AppConfigService.clean(value), "", "", index++));
                    }
                }
            }
        }
        if (result.isEmpty()) {
            throw badRequest("image file or image_url is required");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private ChatgptWebService.ImageInput decode(Object entry, int index) {
        if (entry instanceof Map) {
            Map<String, Object> source = (Map<String, Object>) entry;
            if (source.containsKey("file_id")) {
                throw badRequest("file_id image references are not supported");
            }
            Object value = source.containsKey("b64_json") ? source.get("b64_json") : source.get("base64");
            if (value == null) {
                value = source.containsKey("image_url") ? source.get("image_url") : source.get("url");
            }
            if (value instanceof Map) {
                value = ((Map<String, Object>) value).get("url");
            }
            return decodeString(AppConfigService.clean(value), AppConfigService.clean(source.get("filename")),
                    AppConfigService.clean(source.get("mime_type")), index);
        }
        return decodeString(AppConfigService.clean(entry), "", "", index);
    }

    private ChatgptWebService.ImageInput decodeString(String value, String filename, String mimeType, int index) {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return download(value, filename);
        }
        Matcher matcher = DATA_URL.matcher(value);
        String data = value;
        String resolvedMime = mimeType.isEmpty() ? "image/png" : mimeType;
        byte[] bytes;
        if (matcher.matches()) {
            resolvedMime = matcher.group(1);
            data = matcher.group(2);
            try {
                bytes = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException exception) {
                throw badRequest("invalid base64 image data");
            }
        } else {
            Matcher rawDataMatcher = RAW_DATA_URL.matcher(value);
            if (rawDataMatcher.matches()) {
                resolvedMime = rawDataMatcher.group(1);
                bytes = percentDecode(rawDataMatcher.group(2));
            } else {
                try {
                    bytes = Base64.getDecoder().decode(data);
                } catch (IllegalArgumentException exception) {
                    throw badRequest("invalid base64 image data");
                }
            }
        }
        if (!resolvedMime.startsWith("image/")) {
            throw badRequest("unsupported image mime type");
        }
        if (bytes.length == 0) {
            throw badRequest("image file is empty");
        }
        checkSize(bytes);
        String name = filename.isEmpty() ? "image_" + index + "." + extension(resolvedMime) : filename;
        return new ChatgptWebService.ImageInput(bytes, name, resolvedMime);
    }

    private ChatgptWebService.ImageInput download(String url, String requestedFilename) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "image/*,*/*;q=0.8");
        headers.put("User-Agent", "chatgpt2api image fetcher");
        UpstreamHttpClient.Response response;
        try {
            response = http.session().get(url, headers, 60);
        } catch (RuntimeException exception) {
            throw badRequest("image_url fetch failed: " + exception.getMessage());
        }
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw badRequest("image_url fetch failed: HTTP " + response.getStatus());
        }
        byte[] bytes = response.getBody();
        if (bytes.length == 0) {
            throw badRequest("image_url returned empty content");
        }
        checkSize(bytes);
        String mimeType = response.header("Content-Type").split(";", 2)[0].trim().toLowerCase();
        String path = URI.create(response.getFinalUrl()).getPath();
        if (mimeType.isEmpty() || "application/octet-stream".equals(mimeType) || "binary/octet-stream".equals(mimeType)) {
            mimeType = guessedMime(path);
        }
        if (!mimeType.startsWith("image/")) {
            throw badRequest("image_url must point to an image");
        }
        String name = requestedFilename.isEmpty() ? fileName(path, mimeType) : requestedFilename;
        return new ChatgptWebService.ImageInput(bytes, name, mimeType);
    }

    private String guessedMime(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "image/png";
    }

    private String fileName(String path, String mimeType) {
        String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._]+|[._]+$", "");
        if (name.isEmpty()) {
            return "image_url." + extension(mimeType);
        }
        return name.indexOf('.') < 0 ? name + "." + extension(mimeType) : name;
    }

    private byte[] percentDecode(String value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '%' && index + 2 < value.length()) {
                try {
                    output.write(Integer.parseInt(value.substring(index + 1, index + 3), 16));
                    index += 2;
                    continue;
                } catch (NumberFormatException exception) {
                    throw badRequest("invalid data image URL");
                }
            }
            byte[] bytes = String.valueOf(current).getBytes(StandardCharsets.UTF_8);
            output.write(bytes, 0, bytes.length);
        }
        return output.toByteArray();
    }

    private List<Object> single(Object value) {
        List<Object> result = new ArrayList<Object>();
        if (value != null) {
            result.add(value);
        }
        return result;
    }

    private void checkSize(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw badRequest("image file is too large");
        }
    }

    private String extension(String mimeType) {
        String value = mimeType.substring(mimeType.indexOf('/') + 1).toLowerCase();
        return "jpeg".equals(value) ? "jpg" : value;
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
