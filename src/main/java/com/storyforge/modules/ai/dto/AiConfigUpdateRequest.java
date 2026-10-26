package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑 AI 接口配置。
 *
 * <p>字段为 {@code null} 表示不修改；{@code apiKey} 留空表示保留原密钥。</p>
 */
@Data
@Schema(description = "编辑 AI 接口配置（null 表示不修改）")
public class AiConfigUpdateRequest {

    @Schema(description = "配置名称")
    @Size(max = 64, message = "配置名称最长 64 个字符")
    private String name;

    @Schema(description = "接口基址（需含版本段）")
    @Size(max = 500, message = "接口地址过长")
    @Pattern(regexp = "^https?://.+", message = "接口地址必须以 http:// 或 https:// 开头")
    private String baseUrl;

    @Schema(description = "平台协议（仅图像接口生效）：OPENAI / SILICONFLOW / VOLCENGINE / ZHIPU / DASHSCOPE / MODELSCOPE")
    @Size(max = 32, message = "平台编码过长")
    private String provider;

    @Schema(description = "图像接口默认出图尺寸，形如 1024x1536")
    @Size(max = 32, message = "尺寸格式过长")
    private String imageSize;

    @Schema(description = "API Key；留空表示保留原密钥")
    @Size(max = 512, message = "API Key 过长")
    private String apiKey;

    @Schema(description = "默认模型")
    @Size(max = 128, message = "模型名称过长")
    private String defaultModel;

    @Schema(description = "单次请求超时（毫秒）")
    @Min(value = 1000, message = "超时不能小于 1000 毫秒")
    @Max(value = 600000, message = "超时不能大于 600000 毫秒")
    private Integer timeoutMs;

    @Schema(description = "默认温度 0~2")
    @DecimalMin(value = "0.0", message = "温度不能小于 0")
    @DecimalMax(value = "2.0", message = "温度不能大于 2")
    private Double temperature;

    @Schema(description = "默认 max_tokens")
    @Min(value = 1, message = "max_tokens 至少为 1")
    @Max(value = 200000, message = "max_tokens 过大")
    private Integer maxTokens;

    @Schema(description = "默认 top_p 0~1")
    @DecimalMin(value = "0.0", message = "top_p 不能小于 0")
    @DecimalMax(value = "1.0", message = "top_p 不能大于 1")
    private Double topP;

    @Schema(description = "附加请求头（JSON 对象文本）")
    @Size(max = 2000, message = "附加请求头过长")
    private String extraHeaders;

    @Schema(description = "是否启用；停用默认端点会自动取消其默认标记")
    private Boolean enabled;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注最长 255 个字符")
    private String remark;
}
