package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 一条 AI 接口配置的导出形态（导入的入参也是它）。
 *
 * <p>与 {@link AiConfigVO} 的区别：不含雪花 id / 时间戳 / 测试结果，密钥是**明文**
 * （密文依赖站点主密钥 {@code STORYFORGE_SECRET_KEY}，跨站点无法解密，因此必须搬明文再重新加密）。</p>
 */
@Data
@Schema(description = "AI 接口配置导出项")
public class AiConfigExportItem {

    private String name;

    /** TEXT 文本接口 / IMAGE 图像（文生图） */
    private String kind;

    /** 平台协议（文生图）；文本接口固定 OPENAI */
    private String provider;

    private String imageSize;

    private String baseUrl;

    /** 明文 API Key；导出时选择不含密钥则为 null */
    private String apiKey;

    private String apiKeyMask;

    private String defaultModel;

    /** 已拉取到的模型列表缓存（便于导入后直接选模型） */
    private java.util.List<String> modelList;

    private Integer timeoutMs;

    private Double temperature;

    private Integer maxTokens;

    private Double topP;

    /** 附加请求头（JSON 对象文本） */
    private String extraHeaders;

    private Boolean enabled;

    private Boolean isDefault;

    private String remark;
}
