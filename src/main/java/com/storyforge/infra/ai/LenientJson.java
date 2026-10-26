package com.storyforge.infra.ai;

/**
 * 模型输出的 JSON 容错工具。
 *
 * <p>大模型（尤其写长篇中文设定时）经常把中文引号写成英文双引号，
 * 导致字符串提前闭合，例如：</p>
 *
 * <pre>
 * "synopsis": "他只想查清一件事：是谁在"查"他的底细",
 * </pre>
 *
 * <p>这类输出对 Jackson 来说是非法 JSON，但语义完全可还原。
 * {@link #repair(String)} 会按「引号后是否紧跟 , } ] :」判断引号是字符串结束符
 * 还是正文里的引号，后者自动转义为 {@code \"}；同时处理字符串内裸换行、
 * 非法转义、对象/数组尾逗号等常见瑕疵。</p>
 */
public final class LenientJson {

    private LenientJson() {
    }

    /**
     * 截取首个 '{' 到最后一个 '}' 之间的内容，剥离 markdown 围栏和前后解释文字。
     *
     * @return 候选 JSON 文本；找不到对象结构时返回空串
     */
    public static String extractObject(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return text.substring(start, end + 1);
    }

    /**
     * 修复常见 JSON 语法瑕疵。对本来就合法的 JSON 基本是幂等的。
     */
    public static String repair(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder out = new StringBuilder(json.length() + 64);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    out.append(c);
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    char next = i + 1 < json.length() ? json.charAt(i + 1) : 0;
                    if (next == '"' || next == '\\' || next == '/' || next == 'b'
                            || next == 'f' || next == 'n' || next == 'r' || next == 't'
                            || next == 'u') {
                        out.append(c);
                        escaped = true;
                    } else {
                        // 非法转义（如 Windows 路径 C:\Users）按字面量处理
                        out.append("\\\\");
                    }
                    continue;
                }
                if (c == '"') {
                    if (isStringEnd(json, i + 1)) {
                        out.append(c);
                        inString = false;
                    } else {
                        out.append("\\\"");
                    }
                    continue;
                }
                switch (c) {
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == ',') {
                int next = nextNonSpace(json, i + 1);
                if (next < json.length() && (json.charAt(next) == '}' || json.charAt(next) == ']')) {
                    // 尾逗号直接丢弃
                    continue;
                }
            }
            out.append(c);
        }
        if (inString) {
            out.append('"');
        }
        return out.toString();
    }

    /** 引号之后是否紧接结构符号（说明它是字符串结束符）。 */
    private static boolean isStringEnd(String json, int from) {
        int next = nextNonSpace(json, from);
        if (next >= json.length()) {
            return true;
        }
        char c = json.charAt(next);
        return c == ',' || c == '}' || c == ']' || c == ':';
    }

    private static int nextNonSpace(String json, int from) {
        int i = from;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }
}
