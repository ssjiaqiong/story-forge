package com.storyforge.modules.inspiration.dto;

import com.storyforge.modules.novel.dto.NovelVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 一键创建小说的结果。
 */
@Data
@Schema(description = "创建小说结果")
public class CreateNovelResult {

    @Schema(description = "已创建的小说")
    private NovelVO novel;

    @Schema(description = "要素来源：AI 表示由模型抽取，FALLBACK 表示抽取失败后按会话内容兜底")
    private String extraction;

    @Schema(description = "补充说明，例如兜底原因；正常为空")
    private String note;
}
