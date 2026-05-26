package com.chatgpt2api.controller;

import com.chatgpt2api.auth.AuthGuard;
import com.chatgpt2api.register.RegisterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class RegisterController {
    private final AuthGuard authGuard;
    private final RegisterService registers;
    private final ObjectMapper mapper;

    public RegisterController(AuthGuard authGuard, RegisterService registers, ObjectMapper mapper) {
        this.authGuard = authGuard;
        this.registers = registers;
        this.mapper = mapper;
    }

    @GetMapping("/api/register")
    public Map<String, Object> config(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return result(registers.get());
    }

    @PostMapping("/api/register")
    public Map<String, Object> update(@RequestBody Map<String, Object> body,
                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return result(registers.update(body));
    }

    @PostMapping("/api/register/start")
    public Map<String, Object> start(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return result(registers.start());
    }

    @PostMapping("/api/register/stop")
    public Map<String, Object> stop(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return result(registers.stop());
    }

    @PostMapping("/api/register/reset")
    public Map<String, Object> reset(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authGuard.requireAdmin(authorization);
        return result(registers.reset());
    }

    @GetMapping("/api/register/events")
    public ResponseEntity<StreamingResponseBody> events(@RequestParam(value = "token", defaultValue = "") String token) {
        authGuard.requireAdmin("Bearer " + token);
        StreamingResponseBody stream = output -> {
            String last = "";
            while (true) {
                String current = mapper.writeValueAsString(registers.get());
                if (!current.equals(last)) {
                    output.write(("data: " + current + "\n\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    last = current;
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream);
    }

    private Map<String, Object> result(Map<String, Object> register) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("register", register);
        return result;
    }
}
