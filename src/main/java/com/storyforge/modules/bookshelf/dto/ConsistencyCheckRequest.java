package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 一致性检查（AI 层）的入参。
 *
 * <p>规则层不需要入参——它永远跑全书，秒级完成。这里只描述 AI 层要读多少正文：
 * 全书 AI 检查要把设定、档案与几十章摘要一起塞进上下文，token 成本随章数线性涨，
 * 所以默认是「最近十章」，全书检查要显式指定。</p>
 */
@Data
@Schema(description = "一致性检查入参")
public class ConsistencyCheckRequest {

    public static final String SCOPE_RECENT = "RECENT";
    public static final String SCOPE_CHAPTER = "CHAPTER";
    public static final String SCOPE_ALL = "ALL";

    @Schema(description = "检查范围：RECENT 最近若干章（默认）/ CHAPTER 单章 / ALL 全书", example = "RECENT")
    private String scope = SCOPE_RECENT;

    @Schema(description = "scope=CHAPTER 时的章节 ID")
    private String chapterId;

    @Schema(description = "scope=RECENT 时往前看几章，5~30", example = "10")
    @Min(value = 5, message = "最近章数至少为 5")
    @Max(value = 30, message = "最近章数最多为 30")
    private Integer recentCount = 10;

    /** 归一化后的范围；非法值退回 RECENT，不报错（这个字段错了不值得打断用户）。 */
    public String safeScope() {
        if (SCOPE_CHAPTER.equalsIgnoreCase(scope)) {
            return SCOPE_CHAPTER;
        }
        if (SCOPE_ALL.equalsIgnoreCase(scope)) {
            return SCOPE_ALL;
        }
        return SCOPE_RECENT;
    }

    public int safeRecentCount() {
        if (recentCount == null) {
            return 10;
        }
        return Math.max(5, Math.min(30, recentCount));
    }
}
