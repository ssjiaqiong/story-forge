package com.storyforge.modules.novel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 超管「小说管理」列表行：小说基本信息 + 创建账号 + 体量指标。
 *
 * <p>只读视图，不包含任何可写字段；超管只能看，不能改用户的创作内容。</p>
 */
@Data
@Schema(description = "超管视角的小说列表行")
public class AdminNovelVO {

    @Schema(description = "小说 ID")
    private Long id;

    private String title;

    @Schema(description = "笔名")
    private String penName;

    @Schema(description = "题材")
    private String genre;

    @Schema(description = "简介")
    private String intro;

    @Schema(description = "库中原值：DRAFT / WRITING / FINISHED / ARCHIVED")
    private String status;

    @Schema(description = "运营口径：CREATING 创作中 / COMPLETED 完结 / ABANDONED 废弃")
    private String state;

    @Schema(description = "创建用户 ID")
    private Long userId;

    @Schema(description = "创建用户登录名")
    private String username;

    @Schema(description = "创建用户昵称")
    private String nickname;

    @Schema(description = "逻辑删除标记：0 正常 / 1 已删除（仅超管可见，用户侧不可见）")
    private Integer deleted;

    @Schema(description = "章节数")
    private Integer chapterCount;

    @Schema(description = "字数（仅统计汉字）")
    private Integer wordCount;

    private LocalDateTime createdAt;

    @Schema(description = "最后一次更新时间")
    private LocalDateTime updatedAt;
}
