package com.storyforge.modules.novel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 超管「小说管理」的章节目录项。
 *
 * <p>目录接口不返回 {@code content}，只有点开某一章时才单独拉正文，避免一次传输整本书。</p>
 */
@Data
@Schema(description = "超管视角的章节目录项")
public class AdminChapterVO {

    private Long id;

    private Long novelId;

    private Integer volumeNo;

    @Schema(description = "卷名")
    private String volumeTitle;

    private Integer chapterNo;

    private String title;

    @Schema(description = "大纲摘要")
    private String summary;

    @Schema(description = "正文，仅单章详情接口返回")
    private String content;

    @Schema(description = "字数（仅统计汉字）")
    private Integer wordCount;

    @Schema(description = "DRAFT 草稿 / WRITING 写作中 / DONE 已完成")
    private String status;

    @Schema(description = "章节自身的逻辑删除标记：0 正常 / 1 已删除（小说被删时章节会一并逻辑删除）")
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
