package com.chatgpt2api.image;

import com.chatgpt2api.config.AppConfigService;
import com.chatgpt2api.http.UpstreamHttpClient;
import com.chatgpt2api.protocol.ChatgptWebService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.LinkedMultiValueMap;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageInputServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void readsRemoteAndInlineImageReferences() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Files.write(tempDir.resolve("config.json"), "{\"auth-key\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        AppConfigService config = new AppConfigService(mapper, tempDir.toString());
        config.initialize();
        ImageInputService service = new ImageInputService(new UpstreamHttpClient(config, mapper));
        final byte[] image = new byte[] {1, 2, 3};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/source.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, image.length);
            exchange.getResponseBody().write(image);
            exchange.close();
        });
        server.start();
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("image_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/source.png");
            List<ChatgptWebService.ImageInput> remote = service.jsonInputs(body);
            body.put("image_url", "data:image/png,%01%02%03");
            List<ChatgptWebService.ImageInput> inline = service.jsonInputs(body);
            LinkedMultiValueMap<String, String> fields = new LinkedMultiValueMap<String, String>();
            fields.add("image[]", "data:image/png,%01%02%03");
            List<ChatgptWebService.ImageInput> multipart = service.multipartInputs(new ArrayList<>(), fields);

            assertArrayEquals(image, bytes(remote.get(0)));
            assertEquals("image/png", value(remote.get(0), "mimeType"));
            assertArrayEquals(image, bytes(inline.get(0)));
            assertArrayEquals(image, bytes(multipart.get(0)));
        } finally {
            server.stop(0);
        }
    }

    private byte[] bytes(ChatgptWebService.ImageInput image) throws Exception {
        return (byte[]) value(image, "bytes");
    }

    private Object value(ChatgptWebService.ImageInput image, String name) throws Exception {
        Field field = ChatgptWebService.ImageInput.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(image);
    }
}
