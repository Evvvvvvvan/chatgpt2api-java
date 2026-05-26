package com.chatgpt2api.image;

import com.chatgpt2api.config.AppConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageStorageServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void localImagesAreIndexedAndNonImagesAreIgnored() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Files.write(tempDir.resolve("config.json"), "{\"auth-key\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        AppConfigService config = new AppConfigService(mapper, tempDir.toString());
        config.initialize();
        ImageStorageService service = new ImageStorageService(config, mapper);

        Map<String, Object> stored = service.save(png(), "http://app.test");
        Files.write(config.getImagesDir().resolve(".DS_Store"), "ignored".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> items = service.listItems("http://app.test", "", "");

        assertEquals(1, items.size());
        assertEquals(stored.get("path"), items.get(0).get("path"));
        assertEquals("local", items.get(0).get("storage"));
        assertEquals(2, items.get(0).get("width"));
        assertTrue(service.hasLocal(String.valueOf(stored.get("path"))));
        assertTrue(service.delete(String.valueOf(stored.get("path"))));
        assertFalse(service.hasLocal(String.valueOf(stored.get("path"))));
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
