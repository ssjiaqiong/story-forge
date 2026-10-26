package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * TXT 导出选项。
 *
 * <p>TXT 是给读者/投稿平台看的成品格式，与 JSON 备份包是两件不同的事：
 * 不导出任何 ID、设定、大纲摘要，只导出「卷名 + 章节标题 + 正文」。</p>
 */
@Data
@Schema(description = "TXT 导出选项")
public class TxtExportOptions {

    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_VOLUME = "volume";
    public static final String SCOPE_RANGE = "range";

    @Schema(description = "导出范围：all 全书 / volume 指定卷 / range 章节号闭区间", example = "all")
    private String scope = SCOPE_ALL;

    @Schema(description = "scope=volume 时的卷序号，从 1 开始", example = "2")
    private Integer volumeNo;

    @Schema(description = "scope=range 的起始章号（含）", example = "2")
    private Integer from;

    @Schema(description = "scope=range 的结束章号（含）", example = "3")
    private Integer to;

    @Schema(description = "是否写入 UTF-8 BOM（Windows 记事本与多数网文平台需要）", example = "true")
    private boolean bom = true;

    @Schema(description = "无正文章节是否保留标题行", example = "false")
    private boolean includeEmpty = false;

    @Schema(description = "是否输出卷名分节行", example = "true")
    private boolean withVolume = true;
}
