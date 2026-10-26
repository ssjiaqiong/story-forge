package com.storyforge.infra.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.modules.ai.scene.ImageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文生图客户端：一套入参，多家平台协议。
 *
 * <p>平台差异集中在两处：</p>
 * <ol>
 *   <li><b>请求体</b>：OpenAI 用 {@code size}，硅基流动用 {@code image_size}/{@code batch_size}，
 *       百炼用 {@code input.prompt} + {@code parameters.size}（且 {@code *} 号分隔）；</li>
 *   <li><b>响应</b>：OpenAI/方舟/智谱在 {@code data[]}，硅基流动在 {@code images[]}；
 *       百炼与魔搭是异步任务，需要先拿 task_id 再轮询。</li>
 * </ol>
 *
 * <p>无论哪种平台，最后都会把图片字节下载到内存再交给调用方落盘——平台给的链接多为 1~24 小时有效，
 * 直接存链接等于把封面交给第三方保管。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageGenClient {

    /** 建连超时 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 下载单张图片的超时与体积上限 */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final int DOWNLOAD_MAX_BYTES = 20 * 1024 * 1024;
    /** 单次 HTTP 请求最短/最长超时 */
    private static final int MIN_REQUEST_TIMEOUT_MS = 60_000;
    private static final int MAX_REQUEST_TIMEOUT_MS = 300_000;
    /** 异步任务轮询间隔与总预算 */
    private static final long POLL_INTERVAL_MS = 3_000L;
    private static final long POLL_TIMEOUT_MS = 240_000L;
    private static final int ERROR_MAX_LENGTH = 400;

    private static final Set<String> RESERVED_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "authorization");

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 生成一张图片。
     *
     * <p>失败统一抛 {@link IllegalStateException}，消息里带平台返回的原因，由上层转成业务错误码。</p>
     */
    public ImageGenResult generate(ImageEndpoint endpoint, ImageGenRequest request) {
        if (endpoint == null || endpoint.ai() == null) {
            throw new IllegalStateException("未配置图像接口");
        }
        if (endpoint.apiKey() == null || endpoint.apiKey().isBlank()) {
            throw new IllegalStateException("未配置 API Key");
        }
        if (endpoint.model() == null || endpoint.model().isBlank()) {
            throw new IllegalStateException("未配置默认模型");
        }
        String prompt = request == null || request.prompt() == null ? "" : request.prompt().trim();
        if (prompt.isEmpty()) {
            throw new IllegalStateException("提示词不能为空");
        }
        ImageProvider provider = endpoint.provider() == null ? ImageProvider.OPENAI : endpoint.provider();
        String size = normalizeSize(request.size() == null || request.size().isBlank()
                ? endpoint.defaultImageSize() : request.size(), provider);
        String negative = request.negativePrompt() == null ? "" : request.negativePrompt().trim();

        log.info("[image] 请求开始 provider={} model={} size={} promptChars={}",
                provider, endpoint.model(), size, prompt.length());

        long startedAt = System.currentTimeMillis();
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Downloaded downloaded = switch (provider) {
                    case DASHSCOPE -> dashscope(endpoint, prompt, negative, size);
                    case MODELSCOPE -> modelscope(endpoint, prompt, negative, size);
                    default -> openAiLike(endpoint, provider, prompt, negative, size);
                };
                log.info("[image] 请求完成 provider={} model={} size={} bytes={} latency={}ms 第{}次成功",
                        provider, endpoint.model(), size, downloaded.data().length,
                        System.currentTimeMillis() - startedAt, attempt);
                return new ImageGenResult(downloaded.data(), detectMime(downloaded.data()),
                        downloaded.model() == null ? endpoint.model() : downloaded.model(), provider);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("请求被中断", ex);
            } catch (IllegalStateException ex) {
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                if (attempt >= maxAttempts) {
                    log.error("[image] 生成失败 provider={} model={} 原因={} 已尝试{}次",
                            provider, endpoint.model(), reason, attempt);
                    throw ex;
                }
                log.warn("[image] 生成失败，自动重试 provider={} model={} 第{}次失败 原因={} 剩余重试{}次",
                        provider, endpoint.model(), attempt, reason, maxAttempts - attempt);
                sleepQuietly(attempt * 1000L);
            } catch (Exception ex) {
                String reason = describeException(ex);
                if (attempt >= maxAttempts) {
                    log.error("[image] 生成失败 provider={} model={} 原因={} 已尝试{}次",
                            provider, endpoint.model(), reason, attempt);
                    throw new IllegalStateException(reason, ex);
                }
                log.warn("[image] 生成失败，自动重试 provider={} model={} 第{}次失败 原因={} 剩余重试{}次",
                        provider, endpoint.model(), attempt, reason, maxAttempts - attempt);
                sleepQuietly(attempt * 1000L);
            }
        }
        throw new IllegalStateException("图片生成失败");
    }

    /* ================================================================== */
    /* 同步协议：OpenAI / 硅基流动 / 火山方舟 / 智谱                        */
    /* ================================================================== */

    private Downloaded openAiLike(ImageEndpoint endpoint, ImageProvider provider,
                                 String prompt, String negative, String size) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model());
        body.put("prompt", prompt);
        body.put("n", 1);

        switch (provider) {
            case SILICONFLOW -> {
                // 硅基流动用 image_size / batch_size，且部分模型不支持尺寸以外的采样参数
                body.put("image_size", size);
                body.put("batch_size", 1);
                // Kolors / Qwen-Image 等模型默认带可见水印，显式关掉
                body.put("watermark", false);
                if (!negative.isEmpty()) {
                    body.put("negative_prompt", negative);
                }
            }
            case VOLCENGINE -> {
                // 方舟：size 直接给 "1024x1536" 或 "2K"；关闭平台水印
                body.put("size", size);
                body.put("watermark", false);
            }
            case ZHIPU -> {
                // 智谱 CogView 默认带平台水印，关掉
                body.put("size", size);
                body.put("with_watermark", false);
            }
            default -> {
                body.put("size", size);
                // gpt-image-1 只返回 base64 且不接受 response_format；dall-e 系列需要显式指定 url
                if (endpoint.model().toLowerCase(Locale.ROOT).startsWith("dall-e")) {
                    body.put("response_format", "url");
                }
                if (!negative.isEmpty()) {
                    // OpenAI 官方没有负面提示词字段，把否定内容并入正向提示词
                    body.put("prompt", prompt + "\n避免出现：" + negative);
                }
            }
        }

        JsonNode root = postJson(endpoint, "/images/generations", body, Map.of());
        String model = root.path("model").asText(null);
        Downloaded image = extractImage(root);
        return new Downloaded(image.data(), model == null ? endpoint.model() : model);
    }

    /* ================================================================== */
    /* 异步协议：阿里云百炼（万相）                                          */
    /* ================================================================== */

    private Downloaded dashscope(ImageEndpoint endpoint, String prompt, String negative, String size)
            throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", prompt);
        if (!negative.isEmpty()) {
            input.put("negative_prompt", negative);
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        // 百炼的尺寸格式是「宽*高」
        parameters.put("size", size.replace('x', '*').replace('X', '*'));
        parameters.put("n", 1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model());
        body.put("input", input);
        body.put("parameters", parameters);

        JsonNode submitted = postJson(endpoint, "/services/aigc/text2image/image-synthesis", body,
                Map.of("X-DashScope-Async", "enable"));
        String taskId = submitted.path("output").path("task_id").asText("");
        if (taskId.isBlank()) {
            // 少数模型会同步返回结果
            Downloaded inline = extractImageQuietly(submitted);
            if (inline != null) {
                return new Downloaded(inline.data(), endpoint.model());
            }
            throw new IllegalStateException("平台未返回任务 ID：" + describeBody(submitted));
        }

        JsonNode finished = pollTask(endpoint, "/tasks/" + taskId, Map.of(), endpoint.model());
        return new Downloaded(extractFirstImage(finished), endpoint.model());
    }

    /* ================================================================== */
    /* 异步协议：魔搭 ModelScope                                            */
    /* ================================================================== */

    private Downloaded modelscope(ImageEndpoint endpoint, String prompt, String negative, String size)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model());
        body.put("prompt", prompt);
        int[] wh = parseWidthHeight(size);
        if (wh != null) {
            body.put("width", wh[0]);
            body.put("height", wh[1]);
        }
        if (!negative.isEmpty()) {
            body.put("negative_prompt", negative);
        }

        JsonNode submitted = postJson(endpoint, "/images/generations", body,
                Map.of("X-ModelScope-Async-Mode", "true"));
        String taskId = submitted.path("task_id").asText("");
        if (taskId.isBlank()) {
            Downloaded inline = extractImageQuietly(submitted);
            if (inline != null) {
                return new Downloaded(inline.data(), endpoint.model());
            }
            throw new IllegalStateException("平台未返回任务 ID：" + describeBody(submitted));
        }

        JsonNode finished = pollTask(endpoint, "/tasks/" + taskId,
                Map.of("X-ModelScope-Task-Type", "image_generation"), endpoint.model());
        return new Downloaded(extractFirstImage(finished), endpoint.model());
    }

    /**
     * 轮询异步任务直到成功 / 失败 / 超时。
     *
     * <p>不同平台的完成态写法不同（{@code SUCCEEDED} 与 {@code SUCCEED}），统一按前缀判定。</p>
     */
    private JsonNode pollTask(ImageEndpoint endpoint, String path, Map<String, String> headers, String model)
            throws Exception {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        String lastStatus = "";
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);
            JsonNode root = getJson(endpoint, path, headers);
            String status = firstText(root,
                    "task_status", "status", "output.task_status", "output.status");
            if (status == null) {
                status = "";
            }
            lastStatus = status;
            String upper = status.toUpperCase(Locale.ROOT);
            if (upper.startsWith("SUCCEED") || upper.equals("SUCCESS") || upper.equals("DONE")) {
                return root;
            }
            if (upper.startsWith("FAIL") || upper.startsWith("CANCEL") || upper.equals("EXPIRED")
                    || upper.equals("UNKNOWN")) {
                String detail = firstText(root, "message", "output.message", "code", "output.code");
                throw new IllegalStateException("平台任务失败（" + status + "）"
                        + (detail == null || detail.isBlank() ? "" : "：" + truncate(detail)));
            }
        }
        throw new IllegalStateException("图片生成超时（已等待 " + (POLL_TIMEOUT_MS / 1000)
                + " 秒，最后状态：" + (lastStatus.isBlank() ? "未知" : lastStatus) + "）");
    }

    /* ================================================================== */
    /* HTTP 基础能力                                                       */
    /* ================================================================== */

    private JsonNode postJson(ImageEndpoint endpoint, String path, Object body, Map<String, String> extra)
            throws Exception {
        HttpRequest request = requestBuilder(endpoint, path, extra)
                .timeout(Duration.ofMillis(requestTimeout(endpoint)))
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return send(request, endpoint, path);
    }

    private JsonNode getJson(ImageEndpoint endpoint, String path, Map<String, String> extra) throws Exception {
        HttpRequest request = requestBuilder(endpoint, path, extra)
                .timeout(Duration.ofMillis(requestTimeout(endpoint)))
                .GET()
                .build();
        return send(request, endpoint, path);
    }

    private JsonNode send(HttpRequest request, ImageEndpoint endpoint, String path) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(describeHttpError(response.statusCode(), response.body()));
        }
        return objectMapper.readTree(response.body());
    }

    private HttpRequest.Builder requestBuilder(ImageEndpoint endpoint, String path, Map<String, String> extra) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(joinUrl(endpoint.baseUrl(), path)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + endpoint.apiKey());
        }
        if (extra != null) {
            extra.forEach(builder::header);
        }
        Map<String, String> configured = endpoint.ai().extraHeaders();
        if (configured != null) {
            configured.forEach((name, value) -> {
                if (name == null || name.isBlank() || value == null) {
                    return;
                }
                if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    return;
                }
                builder.header(name.trim(), value);
            });
        }
        return builder;
    }

    /* ================================================================== */
    /* 响应解析                                                            */
    /* ================================================================== */

    /** 从同步响应里取图：先看 {@code images[]}（硅基流动），再看 {@code data[]}（其余平台）。 */
    private Downloaded extractImage(JsonNode root) throws Exception {
        Downloaded image = extractImageQuietly(root);
        if (image == null) {
            throw new IllegalStateException("平台未返回图片：" + describeBody(root));
        }
        return image;
    }

    private Downloaded extractImageQuietly(JsonNode root) {
        if (root == null) {
            return null;
        }
        for (String field : List.of("images", "data", "output_images", "output.results")) {
            JsonNode array = path(root, field);
            if (array != null && array.isArray() && !array.isEmpty()) {
                for (JsonNode item : array) {
                    Downloaded image = downloadFromNode(item);
                    if (image != null) {
                        return image;
                    }
                }
            }
        }
        return downloadFromNode(root.path("output"));
    }

    /** 异步任务结果只有链接，取第一个可下载的图。 */
    private byte[] extractFirstImage(JsonNode root) throws Exception {
        Downloaded image = extractImageQuietly(root);
        if (image == null) {
            throw new IllegalStateException("平台任务已完成但没有图片：" + describeBody(root));
        }
        return image.data();
    }

    /** 兼容 {@code url} / {@code image_url} / {@code b64_json} / {@code data:image/...} 几种形态。 */
    private Downloaded downloadFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return downloadFromUrlOrBase64(node.asText());
        }
        for (String field : List.of("url", "image_url", "b64_json", "b64", "base64", "image")) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                Downloaded image = downloadFromUrlOrBase64(value.asText());
                if (image != null) {
                    return image;
                }
            }
        }
        return null;
    }

    private Downloaded downloadFromUrlOrBase64(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma < 0) {
                return null;
            }
            return new Downloaded(Base64.getDecoder().decode(value.substring(comma + 1)), null);
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                // 平台返回的通常是预签名/公开链接，绝不能再把 API Key 发到第三方 CDN
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(value))
                        .timeout(DOWNLOAD_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("下载图片失败：HTTP " + response.statusCode());
                }
                byte[] data = response.body();
                if (data == null || data.length == 0) {
                    throw new IllegalStateException("下载图片失败：内容为空");
                }
                if (data.length > DOWNLOAD_MAX_BYTES) {
                    throw new IllegalStateException("图片体积超过 " + (DOWNLOAD_MAX_BYTES / 1024 / 1024) + "MB");
                }
                return new Downloaded(data, null);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("下载图片被中断", ex);
            } catch (IllegalStateException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException("下载图片失败：" + describeException(ex), ex);
            }
        }
        // 既不是链接也不是 data URI：当作裸 base64
        if (value.length() > 512) {
            try {
                return new Downloaded(Base64.getDecoder().decode(value), null);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    /* ================================================================== */
    /* 工具方法                                                            */
    /* ================================================================== */

    /** 规整尺寸：容忍 {@code 1024*1536}、{@code 1024X1536}，非法值回落到平台默认。 */
    private String normalizeSize(String raw, ImageProvider provider) {
        String fallback = provider == null ? ImageProvider.OPENAI.getDefaultImageSize()
                : provider.getDefaultImageSize();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('*', 'x');
        if (!value.matches("\\d{3,4}x\\d{3,4}")) {
            return fallback;
        }
        return value;
    }

    private int[] parseWidthHeight(String size) {
        if (size == null) {
            return null;
        }
        String[] parts = size.toLowerCase(Locale.ROOT).split("x");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 按文件头识别真实类型，不信任响应头。 */
    static String detectMime(byte[] data) {
        if (data == null || data.length < 12) {
            return "application/octet-stream";
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    private JsonNode path(JsonNode root, String dotted) {
        JsonNode current = root;
        for (String segment : dotted.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }

    private String firstText(JsonNode root, String... paths) {
        for (String dotted : paths) {
            JsonNode node = path(root, dotted);
            if (node != null && node.isValueNode() && !node.isNull()) {
                String text = node.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    /** 错误响应里往往只有 message / error.message / code，尽量拼出可读原因。 */
    private String describeHttpError(int status, String body) {
        String detail = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                detail = firstText(root, "error.message", "message", "error", "code", "output.message");
            } catch (Exception ignored) {
                detail = body;
            }
        }
        return "HTTP " + status + (detail == null || detail.isBlank() ? "" : "：" + truncate(detail));
    }

    private String describeBody(JsonNode root) {
        if (root == null) {
            return "响应为空";
        }
        String text = root.toString();
        return truncate(text);
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

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private int requestTimeout(ImageEndpoint endpoint) {
        int configured = endpoint.timeoutMs();
        if (configured < MIN_REQUEST_TIMEOUT_MS) {
            return MIN_REQUEST_TIMEOUT_MS;
        }
        return Math.min(configured, MAX_REQUEST_TIMEOUT_MS);
    }

    static String joinUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    /** 已下载的图片；model 为空表示用请求里的模型名。 */
    private record Downloaded(byte[] data, String model) {
    }
}
