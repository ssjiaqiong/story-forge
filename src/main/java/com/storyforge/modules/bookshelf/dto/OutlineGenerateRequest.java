package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * AI 生成大纲入参。
 */
@Data
@Schema(description = "AI 生成大纲入参")
public class OutlineGenerateRequest {

    @Schema(description = "本次大纲的要求，如「第二卷开始进入宗门斗争，节奏加快」；留空则按现有设定推进")
    @Size(max = 2000, message = "要求最长 2000 个字符")
    private String prompt;

    @Schema(description = "卷序号，默认沿用最后一章所在卷")
    @Min(value = 1, message = "卷序号至少为 1")
    @Max(value = 999, message = "卷序号最大 999")
    private Integer volumeNo;

    @Schema(description = "卷名；留空时沿用该卷已有卷名")
    @Size(max = 120, message = "卷名最长 120 个字符")
    private String volumeTitle;

    @Schema(description = "生成章节数，默认 5，最多 20")
    @Min(value = 1, message = "至少生成 1 章")
    @Max(value = 20, message = "一次最多生成 20 章")
    private Integer chapterCount;

    @Schema(description = "起始章节号；留空时接在现有最大章节号之后")
    @Min(value = 1, message = "章节号至少为 1")
    private Integer startChapterNo;

    @Schema(description = "是否联网检索资料，默认 true")
    private Boolean useSearch;

    @Schema(description = "自定义检索词；留空时按作品与要求自动生成")
    @Size(max = 200, message = "检索词最长 200 个字符")
    private String searchQuery;

    @Schema(description = "指定出场人物 ID：本批每章都会包含这些角色，留空则由 AI 按设定挑选")
    private List<Long> participantIds;

    @Schema(description = "关联的总纲 id：指定后这批章节大纲必须落在该总纲的剧情范围内，并自动关联到它")
    private Long masterOutlineId;
}
