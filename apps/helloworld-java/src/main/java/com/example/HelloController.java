package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.time.Instant;
import java.util.*;

@RestController
public class HelloController {

    @Value("${app.name:helloworld-java}")
    private String appName;

    @GetMapping("/")
    public Map<String, Object> root() {
        return hello(null);
    }

    @GetMapping("/hello")
    public Map<String, Object> hello(
            @RequestHeader(value = "X-Forwarded-Client-Cert", required = false) String xfcc) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", appName);
        response.put("message", "Hello from " + appName + "!");
        try {
            response.put("host", InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            response.put("host", "unknown");
        }
        response.put("timestamp", Instant.now().toString());
        // Expose mTLS status so callers can display it
        boolean mtls = xfcc != null && !xfcc.isEmpty();
        response.put("mtls_active", mtls);
        if (mtls) {
            response.put("spiffe_id", extractSpiffeId(xfcc));
            response.put("xfcc_raw", xfcc);
        }
        return response;
    }

    /**
     * GET /call/{serviceName}
     * Calls http://{serviceName}:8080/hello via Java RestTemplate (no curl needed).
     * Returns an HTML page showing the upstream response and whether SPIFFE mTLS is active.
     */
    @GetMapping(value = "/call/{serviceName}")
    public ResponseEntity<String> callService(
            @PathVariable String serviceName,
            @RequestHeader(value = "X-Forwarded-Client-Cert", required = false) String myXfcc) {

        String upstreamUrl = "http://" + serviceName + ":8080/hello";
        Map<String, Object> upstreamData = null;
        String errorMsg = null;
        boolean upstreamMtls = false;
        String upstreamSpiffeId = "";

        try {
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<Map> result = restTemplate.getForEntity(upstreamUrl, Map.class);
            upstreamData = result.getBody();
            if (upstreamData != null) {
                upstreamMtls = Boolean.TRUE.equals(upstreamData.get("mtls_active"));
                upstreamSpiffeId = (String) upstreamData.getOrDefault("spiffe_id", "");
            }
        } catch (Exception e) {
            errorMsg = e.getMessage();
        }

        boolean myMtls = myXfcc != null && !myXfcc.isEmpty();
        String mySpiffeId = myMtls ? extractSpiffeId(myXfcc) : "";
        boolean spiffeEnabled = myMtls || upstreamMtls;

        String html = buildHtml(appName, serviceName, upstreamData, errorMsg,
                spiffeEnabled, mySpiffeId, upstreamSpiffeId, myXfcc, upstreamData == null ? null : (String) upstreamData.get("xfcc_raw"));

        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", appName);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String extractSpiffeId(String xfcc) {
        if (xfcc == null) return "";
        for (String part : xfcc.split("[,;]")) {
            if (part.trim().startsWith("URI=spiffe://") || part.trim().startsWith("By=spiffe://")) {
                return part.trim().replaceFirst("^(URI=|By=)", "");
            }
        }
        return xfcc.length() > 80 ? xfcc.substring(0, 80) + "..." : xfcc;
    }

    private String buildHtml(String caller, String callee, Map<String, Object> upstream,
                              String error, boolean spiffeEnabled,
                              String callerSpiffeId, String calleeSpiffeId,
                              String callerXfcc, String calleeXfcc) {

        String statusColor = spiffeEnabled ? "#2e7d32" : "#b71c1c";
        String statusBg    = spiffeEnabled ? "#e8f5e9" : "#ffebee";
        String statusIcon  = spiffeEnabled ? "🔒" : "⚠️";
        String statusTitle = spiffeEnabled ? "SPIFFE mTLS 已启用" : "普通 HTTP（无 SPIFFE）";

        String statusDesc = spiffeEnabled
                ? "Envoy sidecar 已注入，双方使用 SPIFFE X.509-SVID 进行双向 TLS 认证。<br>" +
                  "Java 业务代码 <strong>零改动</strong>，所有 mTLS 由 OpenShift Service Mesh 透明处理。"
                : "服务之间是明文 HTTP 通信。任何能访问网络的进程都可以拦截或伪造请求。<br>" +
                  "完成<strong>练习 4–6</strong>（加入 Service Mesh + 应用 PeerAuthentication STRICT）后刷新此页查看变化。";

        String upstreamJson = upstream == null
                ? "<span style='color:red'>" + (error != null ? escHtml(error) : "无响应") + "</span>"
                : "<code>" + escHtml(upstream.toString()) + "</code>";

        String spiffeSection = spiffeEnabled ? "<table style='width:100%;border-collapse:collapse'>" +
                "<tr><th style='text-align:left;padding:6px;background:#c8e6c9'>角色</th>" +
                "<th style='text-align:left;padding:6px;background:#c8e6c9'>SPIFFE ID（来自 X-Forwarded-Client-Cert）</th></tr>" +
                "<tr><td style='padding:6px;border-bottom:1px solid #ddd'>" + escHtml(caller) + "（调用方）</td>" +
                "<td style='padding:6px;border-bottom:1px solid #ddd;font-family:monospace'>" +
                (callerSpiffeId.isEmpty() ? "—" : escHtml(callerSpiffeId)) + "</td></tr>" +
                "<tr><td style='padding:6px'>" + escHtml(callee) + "（被调用方）</td>" +
                "<td style='padding:6px;font-family:monospace'>" +
                (calleeSpiffeId.isEmpty() ? "—" : escHtml(calleeSpiffeId)) + "</td></tr>" +
                "</table>"
                : "<p style='color:#666;font-style:italic'>启用 SPIFFE 后此处将显示双方的 SPIFFE ID（spiffe://&lt;trust-domain&gt;/ns/.../sa/...）</p>";

        return "<!DOCTYPE html><html lang='zh'><head><meta charset='UTF-8'>" +
                "<title>SPIFFE Lab – " + escHtml(caller) + " → " + escHtml(callee) + "</title>" +
                "<style>body{font-family:sans-serif;max-width:900px;margin:30px auto;padding:0 20px;background:#fafafa}" +
                "h1{color:#1565c0}h2{color:#333;border-bottom:2px solid #eee;padding-bottom:6px}" +
                ".card{background:#fff;border-radius:8px;padding:20px;margin:16px 0;box-shadow:0 1px 4px rgba(0,0,0,.1)}" +
                ".status{border-radius:8px;padding:16px 20px;margin:16px 0}" +
                "code,pre{background:#f5f5f5;padding:2px 6px;border-radius:4px;font-size:13px}" +
                "table{border:1px solid #ddd;border-radius:6px;overflow:hidden}" +
                ".before-after{display:grid;grid-template-columns:1fr 1fr;gap:16px}" +
                ".ba-card{border-radius:8px;padding:16px}" +
                ".no-spiffe{background:#fff3e0;border-left:4px solid #ff9800}" +
                ".spiffe-on{background:#e8f5e9;border-left:4px solid #4caf50}</style></head><body>" +
                "<h1>🔗 Service 间通信验证</h1>" +
                "<p><strong>" + escHtml(caller) + "</strong>（调用方）→ <strong>" + escHtml(callee) + "</strong>（被调用方）</p>" +

                // Status banner
                "<div class='status' style='background:" + statusBg + ";border-left:4px solid " + statusColor + "'>" +
                "<h2 style='margin:0 0 8px;color:" + statusColor + "'>" + statusIcon + " " + statusTitle + "</h2>" +
                "<p style='margin:0'>" + statusDesc + "</p></div>" +

                // SPIFFE IDs
                "<div class='card'><h2>SPIFFE 身份信息</h2>" + spiffeSection + "</div>" +

                // Upstream response
                "<div class='card'><h2>来自 " + escHtml(callee) + " 的响应</h2>" + upstreamJson + "</div>" +

                // Before / After explanation
                "<div class='card'><h2>📚 Before / After 对比</h2>" +
                "<div class='before-after'>" +
                "<div class='ba-card no-spiffe'><strong>⚠️ 无 SPIFFE（当前可能的状态）</strong><ul>" +
                "<li>服务之间使用明文 HTTP</li><li>无加密、无身份验证</li>" +
                "<li>任何进程可伪造请求</li><li>Java 代码无需改动</li></ul></div>" +
                "<div class='ba-card spiffe-on'><strong>✅ 启用 SPIFFE + Service Mesh</strong><ul>" +
                "<li>Envoy sidecar 自动处理 mTLS</li><li>双方出示 SPIFFE X.509-SVID</li>" +
                "<li>SPIFFE ID 来自 Kubernetes 工作负载元数据</li><li>Java 代码 <strong>零改动</strong></li></ul></div>" +
                "</div></div>" +

                "<p style='color:#999;font-size:12px'>刷新此页：启用 Service Mesh 后状态会自动变为绿色 ✅</p>" +
                "</body></html>";
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
