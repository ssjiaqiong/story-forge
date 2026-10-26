package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 批量连写入参。
 *
 * <p>{@code fromChapterId} 与 {@code fromChapterNo} 二选一，优先 id（前端通常手上有当前选中章节的 id）；
 * 都不给时从第一章开始。<b>起始章本身会被算进去</b>——所以在第 1 章点「连写 3 章」，
 * 配合默认的 {@code skipDone = true} 正好跳过已完成的第 1 章去写第 2~4 章。</p>
 */
@Data
@Schema(description = "批量连写入参")
public class WriteTaskRequest {

    @Schema(description = "从第几章开始（章节 ID），与 fromChapterNo 二选一，优先用这个")
    private String fromChapterId;

    @Schema(description = "从第几章开始（章号），fromChapterId 为空时生效")
    @Min(value = 1, message = "起始章号至少为 1")
    private Integer fromChapterNo;

    @Schema(description = "要写几章，1~10", example = "3")
    @Min(value = 1, message = "连写章数至少为 1")
    @Max(value = 10, message = "单次最多连写 10 章")
    private Integer count = 1;

    @Schema(description = "每章目标字数（按汉字计），300~6000；留空取本书近期章节的平均字数")
    @Min(value = 300, message = "目标字数至少 300")
    @Max(value = 6000, message = "目标字数最多 6000")
    private Integer targetWords;

    @Schema(description = "统一附加要求，会加进每一章的 prompt，如「保持冷硬文风，每章结尾留钩子」")
    @Size(max = 2000, message = "附加要求最长 2000 个字符")
    private String requirement;

    @Schema(description = "某一章失败时是否立即停止整批，默认 true")
    private Boolean stopOnError = true;

    @Schema(description = "是否跳过「已完成」章节，默认 true；跳过的章不计入 count")
    private Boolean skipDone = true;

    @Schema(description = "是否允许联网检索资料，默认 true")
    private Boolean useSearch = true;
}
