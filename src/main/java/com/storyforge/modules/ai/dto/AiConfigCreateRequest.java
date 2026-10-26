package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 AI 接口配置。
 */
@Data
@Schema(description = "新建 AI 接口配置")
public class AiConfigCreateRequest {

    @Schema(description = "配置名称，同一用户内唯一", example = "OpenAI 官方")
    @NotBlank(message = "请填写配置名称")
    @Size(max = 64, message = "配置名称最长 64 个字符")
    private String name;

    @Schema(description = "接口类型：TEXT 文本 / IMAGE 图像（文生图），默认 TEXT；创建后不可变更",
            example = "TEXT")
    private String kind;

    @Schema(description = "平台协议（仅图像接口生效），默认 OPENAI", example = "SILICONFLOW")
    private String provider;

    @Schema(description = "图像接口默认出图尺寸，形如 1024x1536", example = "1024x1536")
    @Size(max = 32, message = "尺寸格式过长")
    private String imageSize;

    @Schema(description = "接口基址（需含版本段）", example = "https://api.openai.com/v1")
    @NotBlank(message = "请填写接口地址")
    @Size(max = 500, message = "接口地址过长")
    @Pattern(regexp = "^https?://.+", message = "接口地址必须以 http:// 或 https:// 开头")
    private String baseUrl;

    @Schema(description = "API Key，仅入参，落库前加密")
    @NotBlank(message = "请填写 API Key")
    @Size(max = 512, message = "API Key 过长")
    private String apiKey;

    @Schema(description = "默认模型", example = "gpt-4o-mini")
    @NotBlank(message = "请填写默认模型")
    @Size(max = 128, message = "模型名称过长")
    private String defaultModel;

    @Schema(description = "单次请求超时（毫秒），默认 60000")
    @Min(value = 1000, message = "超时不能小于 1000 毫秒")
    @Max(value = 600000, message = "超时不能大于 600000 毫秒")
    private Integer timeoutMs;

    @Schema(description = "默认温度 0~2，默认 0.8")
    @DecimalMin(value = "0.0", message = "温度不能小于 0")
    @DecimalMax(value = "2.0", message = "温度不能大于 2")
    private Double temperature;

    @Schema(description = "默认 max_tokens，默认 4096")
    @Min(value = 1, message = "max_tokens 至少为 1")
    @Max(value = 200000, message = "max_tokens 过大")
    private Integer maxTokens;

    @Schema(description = "默认 top_p 0~1，默认 1.0")
    @DecimalMin(value = "0.0", message = "top_p 不能小于 0")
    @DecimalMax(value = "1.0", message = "top_p 不能大于 1")
    private Double topP;

    @Schema(description = "附加请求头（JSON 对象文本），如 {\"X-Org\":\"xxx\"}")
    @Size(max = 2000, message = "附加请求头过长")
    private String extraHeaders;

    @Schema(description = "是否启用，默认 true")
    private Boolean enabled;

    @Schema(description = "是否设为默认端点；首个配置会自动成为默认")
    private Boolean isDefault;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注最长 255 个字符")
    private String remark;
}
