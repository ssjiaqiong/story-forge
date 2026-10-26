package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词模板导入结果。
 *
 * <p>导入逐条进行：单条不合法（未知场景、内容为空、含非法变量）只跳过该条并记录原因，
 * 不影响其余条目。</p>
 */
@Data
@Schema(description = "提示词模板导入结果")
public class PromptImportResult {

    /** 实际使用的模式：MERGE / SKIP_EXISTING / REPLACE */
    private String mode;

    /** 包内条数 */
    private int total;

    /** 实际写入的条数 */
    private int imported;

    /** 跳过的条数 */
    private int skipped;

    /** 逐条明细（顺序与包内一致） */
    private List<ItemResult> items = new ArrayList<>();

    @Data
    @Schema(description = "单条导入结果")
    public static class ItemResult {

        private String scene;

        private String displayName;

        /** IMPORTED / SKIPPED */
        private String status;

        /** 跳过原因；成功时为 null */
        private String message;

        public ItemResult() {
        }

        public ItemResult(String scene, String displayName, String status, String message) {
            this.scene = scene;
            this.displayName = displayName;
            this.status = status;
            this.message = message;
        }
    }
}
