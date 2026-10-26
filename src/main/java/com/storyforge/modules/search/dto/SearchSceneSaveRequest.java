package com.storyforge.modules.search.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索场景绑定保存入参。
 */
@Data
public class SearchSceneSaveRequest {

    @Valid
    @NotNull(message = "请提交场景绑定")
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {

        @NotBlank(message = "场景编码不能为空")
        private String code;

        @NotNull(message = "请选择是否启用")
        private Boolean enabled;

        /** 为空表示继承默认搜索源 */
        private Long configId;

        @Min(value = 1, message = "返回条数至少 1 条")
        @Max(value = 10, message = "返回条数最多 10 条")
        private Integer topK;
    }
}
