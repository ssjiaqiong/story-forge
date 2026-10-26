package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 把章节移动到某一卷。
 *
 * <p>只改归属，<b>不改章号</b>：卷内仍按 {@code chapter_no} 升序排，
 * 不做「卷内独立序号」，否则同一章会有两套序号，导出与前后章判断都要选边站。</p>
 */
@Data
@Schema(description = "移动章节到其他卷")
public class ChapterVolumeRequest {

    @Schema(description = "目标卷序号，从 1 开始", example = "2")
    @NotNull(message = "请选择要移动到哪一卷")
    @Min(value = 1, message = "卷序号至少为 1")
    private Integer volumeNo;
}
