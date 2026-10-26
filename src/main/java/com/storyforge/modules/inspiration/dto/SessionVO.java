package com.storyforge.modules.inspiration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 灵感会话视图（含当前上下文消息）。
 */
@Data
@Schema(description = "灵感会话")
public class SessionVO {

    private Long id;

    @Schema(description = "会话标题，取首条用户消息；上下文清空后为空")
    private String title;

    @Schema(description = "当前选中的技能编码")
    private String skillCode;

    @Schema(description = "当前选中的技能名称")
    private String skillName;

    @Schema(description = "当前上下文消息条数")
    private Integer messageCount;

    private LocalDateTime lastMessageAt;

    private LocalDateTime createdAt;

    @Schema(description = "上下文消息，按时间升序")
    private List<MessageVO> messages = new ArrayList<>();
}
