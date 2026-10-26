package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 试拉模型列表（未保存的表单也能探测）。
 *
 * <p>{@code apiKey} 留空且传了 {@code id} 时，使用该配置已存的密钥；
 * 两者都没有则无法访问 {@code /models}，直接返回参数错误。</p>
 */
@Data
@Schema(description = "试拉模型列表入参")
public class AiModelProbeRequest {

    @Schema(description = "已保存的配置 ID；新增场景为空")
    private Long id;

    @Schema(description = "接口类型 TEXT / IMAGE，影响模型列表的筛选参数", example = "IMAGE")
    private String kind;

    @Schema(description = "平台协议（仅图像接口生效）", example = "SILICONFLOW")
    private String provider;

    @NotBlank(message = "请填写接口地址")
    @Size(max = 500, message = "接口地址过长")
    @Pattern(regexp = "^https?://.+", message = "接口地址必须以 http:// 或 https:// 开头")
    private String baseUrl;

    @Schema(description = "API Key；留空时按 id 使用已保存的密钥")
    @Size(max = 512, message = "API Key 过长")
    private String apiKey;

    @Schema(description = "附加请求头（JSON 对象文本）")
    @Size(max = 2000, message = "附加请求头过长")
    private String extraHeaders;

    @Schema(description = "超时（毫秒）")
    @Min(value = 1000, message = "超时不能小于 1000 毫秒")
    @Max(value = 600000, message = "超时不能大于 600000 毫秒")
    private Integer timeoutMs;
}
