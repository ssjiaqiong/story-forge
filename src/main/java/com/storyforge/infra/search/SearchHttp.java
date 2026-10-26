package com.storyforge.infra.search;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜索源共用的 HTTP / HTML 工具。
 *
 * <p>只做三件事：发请求、按响应头解码、把网页片段洗成纯文本。
 * 任何非 2xx 都抛 {@link IllegalStateException}，由上层转成可读的检索失败信息。</p>
 */
final class SearchHttp {

    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT = Pattern.compile("<(script|style)[^>]*>.*?</\\1>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");
    private static final Pattern BLANK = Pattern.compile("[\\s\\u00a0]+");

    private static final Map<String, String> NAMED_ENTITIES = createEntities();

    private SearchHttp() {
    }

    static String get(String url, Map<String, String> headers, int timeoutMs) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(3000, timeoutMs)))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                .GET();
        headers.forEach(builder::header);
        return send(builder.build());
    }

    static String postJson(String url, String body, Map<String, String> headers, int timeoutMs) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(3000, timeoutMs)))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return send(builder.build());
    }

    private static String send(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String text = decode(response);
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("搜索服务返回 HTTP " + status + "："
                        + abbreviate(text));
            }
            return text;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("搜索请求被中断");
        } catch (Exception ex) {
            throw new IllegalStateException(friendly(ex));
        }
    }

    private static String decode(HttpResponse<byte[]> response) {
        byte[] body = response.body();
        Charset charset = StandardCharsets.UTF_8;
        String contentType = response.headers().firstValue("content-type").orElse("");
        Matcher matcher = CHARSET.matcher(contentType);
        if (matcher.find()) {
            try {
                charset = Charset.forName(matcher.group(1));
            } catch (Exception ignored) {
                // 未知字符集就按 UTF-8 处理
            }
        }
        return new String(body == null ? new byte[0] : body, charset);
    }

    private static String friendly(Exception ex) {
        String message = ex.getMessage();
        if (message != null && (message.contains("timed out") || message.contains("timeout")
                || ex instanceof java.net.http.HttpTimeoutException)) {
            return "搜索请求超时";
        }
        if (ex instanceof java.net.UnknownHostException) {
            return "无法解析搜索服务域名，请检查网络或换一个搜索源";
        }
        if (ex instanceof java.net.ConnectException) {
            return "无法连接搜索服务，请检查网络或换一个搜索源";
        }
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static String decodeUrl(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return value;
        }
    }

    /** 去掉标签、解实体、压缩空白。 */
    static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = SCRIPT.matcher(raw).replaceAll(" ");
        text = TAG.matcher(text).replaceAll(" ");
        text = decodeEntities(text);
        return BLANK.matcher(text).replaceAll(" ").trim();
    }

    static String decodeEntities(String raw) {
        Matcher matcher = ENTITY.matcher(raw);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement;
            if (token.charAt(0) == '#') {
                replacement = codePoint(token);
            } else {
                replacement = NAMED_ENTITIES.getOrDefault(token.toLowerCase(Locale.ROOT), matcher.group());
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private static String codePoint(String token) {
        try {
            int code = token.length() > 1 && (token.charAt(1) == 'x' || token.charAt(1) == 'X')
                    ? Integer.parseInt(token.substring(2), 16)
                    : Integer.parseInt(token.substring(1));
            if (code <= 0 || code > 0x10FFFF) {
                return "";
            }
            return new String(Character.toChars(code));
        } catch (Exception ex) {
            return "";
        }
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception ex) {
            return "";
        }
    }

    static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String text = BLANK.matcher(value).replaceAll(" ").trim();
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }

    private static Map<String, String> createEntities() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("amp", "&");
        map.put("lt", "<");
        map.put("gt", ">");
        map.put("quot", "\"");
        map.put("apos", "'");
        map.put("nbsp", " ");
        map.put("hellip", "…");
        map.put("mdash", "—");
        map.put("ndash", "–");
        map.put("middot", "·");
        map.put("ldquo", "“");
        map.put("rdquo", "”");
        map.put("lsquo", "‘");
        map.put("rsquo", "’");
        map.put("laquo", "«");
        map.put("raquo", "»");
        map.put("copy", "©");
        map.put("reg", "®");
        map.put("times", "×");
        map.put("divide", "÷");
        return map;
    }
}
