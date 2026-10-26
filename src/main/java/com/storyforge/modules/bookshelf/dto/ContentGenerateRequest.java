package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 写本章正文入参。
 */
@Data
@Schema(description = "AI 写正文入参")
public class ContentGenerateRequest {

    @Schema(description = "本章额外要求，如「多写主角与师父的对手戏，结尾留一个反转」")
    @Size(max = 2000, message = "要求最长 2000 个字符")
    private String prompt;

    @Schema(description = "预计字数（按汉字计），默认 2500，范围 300~6000")
    @Min(value = 300, message = "目标字数至少 300")
    @Max(value = 6000, message = "目标字数最多 6000")
    private Integer targetWords;

    @Schema(description = "写入方式：REPLACE 覆盖本章 / APPEND 接在本章末尾")
    @Pattern(regexp = "REPLACE|APPEND", message = "写入方式非法")
    private String mode;

    @Schema(description = "是否联网检索资料，默认 true")
    private Boolean useSearch;

    @Schema(description = "自定义检索词；留空时按作品、章节与要求自动生成")
    @Size(max = 200, message = "检索词最长 200 个字符")
    private String searchQuery;
}
