package com.storyforge.modules.novel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 小说视图。
 */
@Data
@Schema(description = "小说")
public class NovelVO {

    private Long id;

    private String title;

    @Schema(description = "笔名")
    private String penName;

    @Schema(description = "题材")
    private String genre;

    @Schema(description = "标签")
    private List<String> tags;

    @Schema(description = "简介")
    private String intro;

    @Schema(description = "DRAFT 草稿 / WRITING 连载中 / FINISHED 已完结 / ARCHIVED 已归档")
    private String status;

    @Schema(description = "MANUAL 手动创建 / INSPIRATION 灵感模式创建")
    private String source;

    @Schema(description = "来源灵感会话 ID")
    private Long inspirationSessionId;

    private Integer chapterCount;

    private Integer wordCount;

    @Schema(description = "创作约束：建书时补充的读者画像、禁忌等，会注入所有 AI 创作场景")
    private String constraintText;

    @Schema(description = "预计完结字数；0 表示未设置")
    private Integer plannedWords;

    @Schema(description = "本书是否处于自动生成中（自动写书任务运行态），前端据此禁用编辑")
    private Boolean autoWriting;

    @Schema(description = "封面列表，主封面在前（书架按此顺序左右切换，最多 5 张）")
    private List<NovelCoverVO> covers = new ArrayList<>();

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
