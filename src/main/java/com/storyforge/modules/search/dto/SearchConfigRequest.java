package com.storyforge.modules.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 搜索源新增 / 编辑入参。
 *
 * <p>编辑时字段为 {@code null} 表示不修改；{@code apiKey} 留空表示保留原密钥。</p>
 */
@Data
@Schema(description = "搜索源配置入参")
public class SearchConfigRequest {

    @Size(max = 100, message = "名称不能超过 100 字")
    private String name;

    @Schema(description = "BING / DUCKDUCKGO / WIKIPEDIA / SEARXNG / TAVILY / SERPER")
    private String provider;

    @Size(max = 500, message = "接口地址过长")
    private String baseUrl;

    private String apiKey;

    @Min(value = 1, message = "返回条数至少 1 条")
    @Max(value = 10, message = "返回条数最多 10 条")
    private Integer defaultTopK;

    @Size(max = 32)
    private String language;

    @Size(max = 32)
    private String region;

    @Min(value = 3000, message = "超时不能低于 3 秒")
    @Max(value = 60000, message = "超时不能超过 60 秒")
    private Integer timeoutMs;

    private Boolean enabled;

    private Boolean isDefault;

    @Size(max = 500, message = "备注不能超过 500 字")
    private String remark;
}
