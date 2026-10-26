package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 生成事件入参。
 */
@Data
@Schema(description = "AI 生成事件入参")
public class EventGenerateRequest {

    @Schema(description = "事件需求描述，如「围绕旧街大火补一批线索事件，越往后越接近真相」")
    @NotBlank(message = "请先描述你想要的事件")
    @Size(max = 2000, message = "描述最长 2000 个字符")
    private String prompt;

    @Schema(description = "生成数量，默认 1，最多 10")
    @Min(value = 1, message = "至少生成 1 个事件")
    @Max(value = 10, message = "一次最多生成 10 个事件")
    private Integer count;

    @Schema(description = "是否联网检索资料补充细节，默认 true")
    private Boolean useSearch;

    @Schema(description = "自定义检索词；留空时按小说与需求自动生成")
    @Size(max = 200, message = "检索词最长 200 个字符")
    private String searchQuery;
}
