package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "新增卷入参")
public class VolumeCreateRequest {

    @Schema(description = "卷名；留空时用「第 N 卷」兜底", example = "第一卷 旧书")
    @Size(max = 120, message = "卷名最长 120 个字符")
    private String title;

    @Schema(description = "卷简介")
    @Size(max = 4000, message = "卷简介最长 4000 个字符")
    private String intro;
}
