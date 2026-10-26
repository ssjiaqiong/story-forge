package com.storyforge.modules.inspiration.service;

import com.storyforge.modules.inspiration.dto.CreateNovelRequest;
import com.storyforge.modules.inspiration.dto.CreateNovelResult;
import com.storyforge.modules.inspiration.dto.SendMessageResult;
import com.storyforge.modules.inspiration.dto.SessionVO;

/**
 * 灵感模式服务：每个用户一个会话，保留上下文，可清空上下文或整体删除。
 */
public interface InspirationService {

    /** 当前会话（不存在则自动创建），含上下文消息。 */
    SessionVO session();

    /** 发送一条消息并等待 AI 回复，用户消息与助手消息都会落库。 */
    SendMessageResult send(String content);

    /** 切换当前技能。 */
    SessionVO switchSkill(String skillCode);

    /** 清空上下文：删除全部消息，保留会话与技能选择。 */
    void clearContext();

    /** 删除整个会话（下次进入自动重建空会话）。 */
    void deleteSession();

    /** 根据当前会话内容一键创建小说。 */
    CreateNovelResult createNovel(CreateNovelRequest request);
}
