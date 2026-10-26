package com.storyforge.modules.ai.dto;

import com.storyforge.modules.ai.scene.ImageProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文生图平台预设：新增图像接口时一键填入地址 / 模型 / 尺寸。
 *
 * <p>预设只是「填表建议」，落库后仍以用户填写的值为准。</p>
 */
@Data
@Schema(description = "文生图平台预设")
public class ImageProviderPresetVO {

    @Schema(description = "平台编码，与 AiConfig.provider 对应")
    private String provider;

    @Schema(description = "平台名称")
    private String label;

    @Schema(description = "默认接口基址（含版本段）")
    private String baseUrl;

    @Schema(description = "推荐模型")
    private String model;

    @Schema(description = "推荐出图尺寸")
    private String imageSize;

    @Schema(description = "填写提示")
    private String hint;

    @Schema(description = "是否支持负面提示词")
    private Boolean supportsNegativePrompt;

    @Schema(description = "是否提供 /models 模型列表（阿里云百炼没有）")
    private Boolean supportsModelList;

    public static ImageProviderPresetVO from(ImageProvider provider) {
        ImageProviderPresetVO vo = new ImageProviderPresetVO();
        vo.setProvider(provider.name());
        vo.setLabel(provider.getLabel());
        vo.setBaseUrl(provider.getDefaultBaseUrl());
        vo.setModel(provider.getDefaultModel());
        vo.setImageSize(provider.getDefaultImageSize());
        vo.setHint(provider.getHint());
        vo.setSupportsNegativePrompt(provider.isSupportsNegativePrompt());
        vo.setSupportsModelList(provider.isSupportsModelList());
        return vo;
    }
}
