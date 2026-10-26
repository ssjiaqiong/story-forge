package com.storyforge.modules.novel.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 助手执行的一条修改操作摘要，供前端在气泡里展示「改了什么」。
 */
@Data
@Schema(description = "助手操作摘要")
public class AgentOperationVO {

    @Schema(description = "操作类型，如 UPDATE_CHARACTER / DELETE_ITEM")
    private String type;

    @Schema(description = "操作对象名，如角色姓名 / 物品名")
    private String target;

    @Schema(description = "操作后实体的 id（新增/修改时返回，便于模型下一轮直接引用）")
    private String id;

    @Schema(description = "是否执行成功")
    private boolean success;

    @Schema(description = "失败原因或补充说明")
    private String message;
}
