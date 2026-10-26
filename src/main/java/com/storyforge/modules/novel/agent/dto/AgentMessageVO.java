package com.storyforge.modules.novel.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 小说助手消息视图。
 */
@Data
@Schema(description = "助手消息")
public class AgentMessageVO {

    private Long id;

    private String role;

    private String content;

    @Schema(description = "助手消息执行的操作摘要（用户消息为空）")
    private List<AgentOperationVO> operations;

    private String status;

    private String errorMessage;

    private String model;

    private LocalDateTime createdAt;
}
