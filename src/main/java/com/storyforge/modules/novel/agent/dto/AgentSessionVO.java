package com.storyforge.modules.novel.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 小说助手会话视图。
 */
@Data
@Schema(description = "助手会话")
public class AgentSessionVO {

    private Long id;

    private Long novelId;

    private String title;

    private Integer messageCount;

    private LocalDateTime lastMessageAt;

    private List<AgentMessageVO> messages;
}
