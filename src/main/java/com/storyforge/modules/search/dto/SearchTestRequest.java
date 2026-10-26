package com.storyforge.modules.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 连通性测试入参：{@code id} 存在时测已保存配置；否则测表单里尚未保存的内容。
 */
@Data
@Schema(description = "搜索源测试入参")
public class SearchTestRequest {

    private Long id;

    private String provider;

    private String baseUrl;

    private String apiKey;

    @Min(value = 1, message = "返回条数至少 1 条")
    @Max(value = 10, message = "返回条数最多 10 条")
    private Integer topK;

    @Size(max = 200, message = "测试关键词不能超过 200 字")
    private String query;
}
