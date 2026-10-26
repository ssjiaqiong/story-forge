package com.storyforge.modules.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "搜索场景绑定")
public record SearchSceneVO(
        String code,
        String label,
        String group,
        @Schema(description = "是否在该场景启用联网检索") Boolean enabled,
        @Schema(description = "绑定的搜索源；为空表示继承默认搜索源") Long configId,
        String configName,
        @Schema(description = "该场景的返回条数；为空表示用搜索源的默认条数") Integer topK,
        @Schema(description = "实际生效的搜索源名称（含继承）") String effectiveConfigName) {
}
