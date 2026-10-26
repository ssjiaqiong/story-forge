package com.storyforge.modules.ai.scene;

/**
 * AI 接口类型。
 *
 * <p>文本接口走 OpenAI 协议的 {@code /chat/completions}；图像接口走各平台自己的文生图协议
 * （见 {@link ImageProvider}）。两类接口的「默认端点」各自独立，互不覆盖。</p>
 */
public enum AiConfigKind {

    TEXT("文本接口"),
    IMAGE("图像接口");

    private final String label;

    AiConfigKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static AiConfigKind of(String code) {
        if (code == null || code.isBlank()) {
            return TEXT;
        }
        for (AiConfigKind kind : values()) {
            if (kind.name().equalsIgnoreCase(code.trim())) {
                return kind;
            }
        }
        return null;
    }
}
