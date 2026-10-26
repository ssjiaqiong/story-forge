package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 「按分析修改」入参。
 *
 * <p>前端把分析报告里勾选的问题原样传回来，服务端只做拼接与截断，不重新解释语义：
 * 审稿意见已经由分析场景产出，这里再"理解"一遍只会引入偏差。</p>
 */
@Data
@Schema(description = "按分析修改入参")
public class ChapterReviseRequest {

    @Schema(description = "所依据的分析报告 ID，仅作记录与展示，留空取本章最近一次分析")
    private Long analysisId;

    @Schema(description = "勾选的要修正的问题")
    private List<ReviseItem> items;

    @Schema(description = "作者自己的修改要求，可为空")
    @Size(max = 2000, message = "修改要求最长 2000 个字符")
    private String instruction;

    @Schema(description = "修改幅度：LIGHT 只改点出的地方 / MEDIUM 允许补写过渡 / HEAVY 允许重排场景")
    private String intensity;

    @Schema(description = "修改后的目标汉字数，留空沿用分析时的目标字数")
    private Integer targetWords;

    /**
     * 一条待修正的问题。
     *
     * @param type   问题类型：OUTLINE_MISS / OUTLINE_SUGGESTION / AI_SIGNAL / AI_EXAMPLE / LENGTH
     * @param text   问题本身（分析报告里的原句）
     * @param detail 补充说明（AI 味信号的细节），可为空
     */
    @Schema(description = "待修正的问题")
    public record ReviseItem(String type, String text, String detail) {
    }
}
