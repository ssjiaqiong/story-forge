package com.storyforge.modules.novel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 超管「小说管理」详情：基本信息 + 章节目录（不含正文）。
 */
@Data
@Schema(description = "超管视角的小说详情（含目录）")
public class AdminNovelDetailVO {

    @Schema(description = "小说基本信息与创建账号")
    private AdminNovelVO novel;

    @Schema(description = "章节目录，按卷、章排序")
    private List<AdminChapterVO> chapters = new ArrayList<>();
}
