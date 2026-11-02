package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 伏笔的新增 / 编辑入参。
 *
 * <p>{@code plannedPayoffChapter} 允许留空——作者也不知道什么时候收，
 * 空着就按「埋设章 + 30 章」判超期，比硬填一个假数字诚实。</p>
 */
@Data
@Schema(description = "伏笔入参")
public class ForeshadowRequest {

    @Schema(description = "一句话标题，如「母亲留下的铜钥匙」")
    @Size(max = 200, message = "标题最长 200 个字符")
    private String title;

    @Schema(description = "埋了什么、读者应该记住什么")
    @Size(max = 2000, message = "内容最长 2000 个字符")
    private String content;

    @Schema(description = "埋设章号；留空取当前最新章")
    private Integer plantedChapterNo;

    @Schema(description = "计划第几章回收；0 或留空表示未规划")
    private Integer plannedPayoffChapter;

    @Schema(description = "OPEN / PAID / DROPPED；编辑时留空表示不改")
    private String status;

    @Schema(description = "实际回收章号；状态为 PAID 时留空取当前最新章。补录历史书时用它指到真正回收的那一章")
    private Integer payoffChapterNo;

    @Size(max = 500, message = "备注最长 500 个字符")
    private String note;
}
