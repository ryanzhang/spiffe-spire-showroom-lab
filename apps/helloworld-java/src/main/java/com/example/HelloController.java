package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HelloController {

    @Value("${app.name:helloworld-java}")
    private String appName;

    @Value("${service.b.url:}")
    private String serviceBUrl;

    @GetMapping("/")
    public Map<String, Object> root() {
        return hello();
    }

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Hello from " + appName + "!");
        response.put("service", appName);
        try {
            response.put("host", InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            response.put("host", "unknown");
        }
        response.put("timestamp", Instant.now().toString());
        return response;
    }

    // service-a calls this endpoint to demonstrate service-to-service communication
    @GetMapping("/call")
    public Map<String, Object> call() {
        Map<String, Object> response = new HashMap<>();
        response.put("caller", appName);
        if (serviceBUrl == null || serviceBUrl.isEmpty()) {
            response.put("error", "SERVICE_B_URL not configured");
            return response;
        }
        try {
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<Map> result = restTemplate.getForEntity(
                serviceBUrl + "/hello", Map.class);
            response.put("upstream", result.getBody());
            response.put("status", "success");
            response.put("protocol", "HTTP (pre-SPIFFE) or mTLS (post-OSSM)");
        } catch (Exception e) {
            response.put("error", e.getMessage());
            response.put("status", "failed");
        }
        return response;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "UP");
        status.put("service", appName);
        return status;
    }
}
