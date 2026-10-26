package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 改卷名 / 卷简介。
 *
 * <p>两个字段都可以只传一个：置空用空串（{@code updateById} 会跳过 null，
 * 「清空简介」必须显式传空串）。卷名给空串则退回系统兜底「第 N 卷」。</p>
 */
@Data
@Schema(description = "修改卷入参")
public class VolumeUpdateRequest {

    @Schema(description = "卷名；留空表示退回「第 N 卷」")
    @Size(max = 120, message = "卷名最长 120 个字符")
    private String title;

    @Schema(description = "卷简介；空串表示清空")
    @Size(max = 4000, message = "卷简介最长 4000 个字符")
    private String intro;
}
