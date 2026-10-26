package com.storyforge.infra.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * OpenAI 协议客户端（仅文本模型）。
 *
 * <p>只做两件事：发一次最小 chat 请求验证连通性、拉取模型列表。真正的生成调用在流水线模块中复用本客户端。</p>
 *
 * <p>请求体保持最小——不带 {@code max_tokens} / {@code temperature}，避免部分推理模型（如 o 系列）
 * 因不支持这些字段而直接报错。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiClient {

    /** 建连超时，与单次请求超时（可配置）区分开 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 错误信息截断长度，避免日志与响应体过大 */
    private static final int ERROR_MAX_LENGTH = 400;
    /** 不允许通过额外请求头覆盖的字段 */
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "authorization");

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 发送最小 chat 请求验证端点可用性。
     *
     * <p>失败不抛异常，统一收敛为 {@link AiTestResult#failure}，便于界面直接展示原因。</p>
     */
    public AiTestResult testChat(AiEndpoint endpoint) {
        if (endpoint.apiKey() == null || endpoint.apiKey().isBlank()) {
            return AiTestResult.failure(0L, "未配置 API Key");
        }
        if (endpoint.model() == null || endpoint.model().isBlank()) {
            return AiTestResult.failure(0L, "未配置默认模型");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model());
        body.put("messages", List.of(Map.of("role", "user", "content", "ping")));
        body.put("stream", false);

        long startedAt = System.currentTimeMillis();
        try {
            HttpRequest request = baseRequest(endpoint, "/chat/completions", body)
                    .timeout(Duration.ofMillis(normalizeTimeout(endpoint.timeoutMs())))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latency = System.currentTimeMillis() - startedAt;

            if (response.statusCode() / 100 != 2) {
                return AiTestResult.failure(latency, describeHttpError(response.statusCode(), response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            String reply = extractReply(root);
            String model = root.path("model").asText(endpoint.model());
            Integer totalTokens = root.path("usage").hasNonNull("total_tokens")
                    ? root.path("usage").get("total_tokens").asInt()
                    : null;
            return AiTestResult.success(latency, model, reply, totalTokens);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AiTestResult.failure(System.currentTimeMillis() - startedAt, "请求被中断");
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - startedAt;
            log.warn("[ai] connectivity test failed baseUrl={} error={}", endpoint.baseUrl(), ex.toString());
            return AiTestResult.failure(latency, describeException(ex));
        }
    }

    /**
     * 多轮对话（非流式）。
     *
     * <p>失败直接抛 {@link IllegalStateException}，由上层转成业务错误码。
     * 部分推理模型不接受 {@code temperature} / {@code max_tokens}：若首次请求因这类参数被拒，
     * 会自动去掉采样参数重试一次，避免用户侧的对话直接不可用。</p>
     */
    public ChatResult chat(AiEndpoint endpoint, List<ChatMessage> messages) {
        if (endpoint.apiKey() == null || endpoint.apiKey().isBlank()) {
            throw new IllegalStateException("未配置 API Key");
        }
        if (endpoint.model() == null || endpoint.model().isBlank()) {
            throw new IllegalStateException("未配置默认模型");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalStateException("消息内容为空");
        }

        long startedAt = System.currentTimeMillis();
        try {
            HttpResponse<String> response = sendChat(endpoint, messages, true);
            if (response.statusCode() / 100 != 2 && isSamplingUnsupported(response.body())) {
                log.info("[ai] endpoint rejected sampling params, retry without them model={}", endpoint.model());
                response = sendChat(endpoint, messages, false);
            }
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(describeHttpError(response.statusCode(), response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = extractReply(root);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("模型返回内容为空");
            }
            JsonNode usage = root.path("usage");
            return new ChatResult(
                    content,
                    root.path("model").asText(endpoint.model()),
                    usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
                    usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null,
                    System.currentTimeMillis() - startedAt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断", ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - startedAt;
            log.warn("[ai] chat failed baseUrl={} model={} latency={}ms error={}",
                    endpoint.baseUrl(), endpoint.model(), latency, ex.toString());
            throw new IllegalStateException(describeException(ex), ex);
        }
    }

    private HttpResponse<String> sendChat(AiEndpoint endpoint, List<ChatMessage> messages, boolean withSampling)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model());
        List<Map<String, String>> payload = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            payload.add(Map.of("role", message.role(), "content", message.content()));
        }
        body.put("messages", payload);
        body.put("stream", false);
        if (withSampling) {
            if (endpoint.temperature() != null && endpoint.temperature() > 0D) {
                body.put("temperature", endpoint.temperature());
            }
            if (endpoint.maxTokens() != null && endpoint.maxTokens() > 0) {
                body.put("max_tokens", endpoint.maxTokens());
            }
        }

        HttpRequest request = baseRequest(endpoint, "/chat/completions", body)
                .timeout(Duration.ofMillis(normalizeTimeout(endpoint.timeoutMs())))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 判断错误是否由采样参数不被支持引起。 */
    private boolean isSamplingUnsupported(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        boolean mentionsParam = lower.contains("temperature")
                || lower.contains("max_tokens")
                || lower.contains("top_p");
        boolean mentionsReject = lower.contains("unsupported")
                || lower.contains("not support")
                || lower.contains("does not support")
                || lower.contains("unknown")
                || lower.contains("invalid");
        return mentionsParam && mentionsReject;
    }

    /**
     * 拉取模型列表（{@code GET {baseUrl}/models}）。
     *
     * <p>与连通性测试不同，这里失败直接抛出异常，由上层转换为业务错误提示。</p>
     */
    public List<String> listModels(AiEndpoint endpoint) {
        return listModels(endpoint, null);
    }

    /**
     * 拉取模型列表，可附加查询串（如硅基流动的 {@code type=image} 只看生图模型）。
     *
     * @param query 不含 {@code ?} 的查询串，为空表示不过滤
     */
    public List<String> listModels(AiEndpoint endpoint, String query) {
        if (endpoint.apiKey() == null || endpoint.apiKey().isBlank()) {
            throw new IllegalStateException("未配置 API Key");
        }
        long startedAt = System.currentTimeMillis();
        String path = (query == null || query.isBlank()) ? "/models" : "/models?" + query.trim();
        try {
            HttpRequest request = baseRequest(endpoint, path, null)
                    .timeout(Duration.ofMillis(normalizeTimeout(endpoint.timeoutMs())))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(describeHttpError(response.statusCode(), response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            Set<String> models = new TreeSet<>();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String id = item.path("id").asText(null);
                    if (id != null && !id.isBlank()) {
                        models.add(id);
                    }
                }
            }
            log.info("[ai] listed {} models in {} ms", models.size(), System.currentTimeMillis() - startedAt);
            return new ArrayList<>(models);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断", ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(describeException(ex), ex);
        }
    }

    /* ------------------------------------------------------------------ */

    private HttpRequest.Builder baseRequest(AiEndpoint endpoint, String path, Map<String, Object> body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(joinUrl(endpoint.baseUrl(), path)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + endpoint.apiKey());
        }
        if (endpoint.extraHeaders() != null) {
            endpoint.extraHeaders().forEach((name, value) -> {
                if (name == null || name.isBlank() || value == null) {
                    return;
                }
                if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    return;
                }
                builder.header(name.trim(), value);
            });
        }
        if (body == null) {
            return builder;
        }
        try {
            return builder.POST(HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("请求体序列化失败", ex);
        }
    }

    /** 拼接基址与路径，容忍基址结尾多余的斜杠。 */
    static String joinUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private int normalizeTimeout(int timeoutMs) {
        if (timeoutMs < 1000) {
            return 60_000;
        }
        return Math.min(timeoutMs, 600_000);
    }

    private String extractReply(JsonNode root) {
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : content) {
                String text = part.path("text").asText("");
                if (!text.isEmpty()) {
                    builder.append(text);
                }
            }
            return builder.toString();
        }
        return "";
    }

    private String describeHttpError(int status, String body) {
        String detail = extractErrorMessage(body);
        return "HTTP " + status + (detail == null || detail.isBlank() ? "" : "：" + truncate(detail));
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (error.isObject() && error.hasNonNull("message")) {
                return error.get("message").asText();
            }
            if (error.isTextual()) {
                return error.asText();
            }
            if (root.hasNonNull("message")) {
                return root.get("message").asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 响应，直接回原文
        }
        return body;
    }

    private String describeException(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        if (ex instanceof java.net.http.HttpTimeoutException) {
            return "请求超时：" + message;
        }
        if (ex instanceof java.net.ConnectException) {
            return "无法连接接口地址：" + message;
        }
        if (ex instanceof java.net.UnknownHostException) {
            return "域名无法解析：" + message;
        }
        return truncate(message);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= ERROR_MAX_LENGTH ? trimmed : trimmed.substring(0, ERROR_MAX_LENGTH) + "…";
    }
}
