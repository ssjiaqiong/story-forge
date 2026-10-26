package com.storyforge.modules.inspiration.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.audit.Audit;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.inspiration.dto.CreateNovelRequest;
import com.storyforge.modules.inspiration.dto.CreateNovelResult;
import com.storyforge.modules.inspiration.dto.NovelCreationTaskVO;
import com.storyforge.modules.inspiration.dto.SendMessageRequest;
import com.storyforge.modules.inspiration.dto.SendMessageResult;
import com.storyforge.modules.inspiration.dto.SessionVO;
import com.storyforge.modules.inspiration.dto.SwitchSkillRequest;
import com.storyforge.modules.inspiration.service.InspirationService;
import com.storyforge.modules.inspiration.service.NovelCreationTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 灵感模式：单会话多轮对话 + 一键创建小说。
 *
 * <p>每个用户只有一个会话，首次进入自动创建；上下文存库，可整体清空或删除。</p>
 */
@Tag(name = "灵感模式", description = "与 AI 反复对话打磨小说方案，并一键生成作品")
@RestController
@RequestMapping("/api/inspiration")
@RequiredArgsConstructor
public class InspirationController {

    private final InspirationService inspirationService;
    private final NovelCreationTaskService novelCreationTaskService;

    @Operation(summary = "获取当前会话", description = "不存在时自动创建；返回上下文消息与当前技能")
    @GetMapping("/session")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<SessionVO> session() {
        return R.ok(inspirationService.session());
    }

    @Operation(summary = "发送消息", description = "携带当前会话上下文调用 AI，用户消息与回复都会落库")
    @PostMapping("/messages")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<SendMessageResult> send(@Valid @RequestBody SendMessageRequest request) {
        return R.ok(inspirationService.send(request.getContent()));
    }

    @Operation(summary = "切换技能", description = "切换后新一轮对话立即使用新技能的系统提示词")
    @PutMapping("/session/skill")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<SessionVO> switchSkill(@Valid @RequestBody SwitchSkillRequest request) {
        return R.ok(inspirationService.switchSkill(request.getSkillCode()));
    }

    @Operation(summary = "清空上下文", description = "删除会话内全部消息，保留会话与技能选择")
    @PostMapping("/session/clear")
    @Audit(action = "INSPIRATION_CLEAR_CONTEXT", targetType = "INSPIRATION_SESSION")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<Void> clearContext() {
        inspirationService.clearContext();
        return R.ok();
    }

    @Operation(summary = "删除会话", description = "彻底删除会话与消息，下次进入重新开始")
    @DeleteMapping("/session")
    @Audit(action = "INSPIRATION_DELETE_SESSION", targetType = "INSPIRATION_SESSION")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<Void> deleteSession() {
        inspirationService.deleteSession();
        return R.ok();
    }

    @Operation(summary = "一键创建小说", description = "按当前会话内容抽取要素并落库")
    @PostMapping("/novels")
    @Audit(action = "NOVEL_CREATE_FROM_INSPIRATION", targetType = "NOVEL")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 12, windowSeconds = 60)
    public R<CreateNovelResult> createNovel(@Valid @RequestBody(required = false) CreateNovelRequest request) {
        return R.ok(inspirationService.createNovel(request));
    }

    @Operation(summary = "启动一键建书任务",
            description = "异步完整开书：提炼要素 → 世界观 → 背景 → 冲突 → 人物关系 → 事件 → 大纲 → 物品 → 势力，返回 taskId 供轮询进度")
    @PostMapping("/novels/tasks")
    @Audit(action = "NOVEL_CREATE_FROM_INSPIRATION", targetType = "NOVEL")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 6, windowSeconds = 60)
    public R<NovelCreationTaskVO> startNovelTask() {
        return R.ok(novelCreationTaskService.start(UserContextHolder.requireUserId()));
    }

    @Operation(summary = "查询一键建书进度", description = "返回百分比、当前阶段文案与最终小说信息")
    @GetMapping("/novels/tasks/{taskId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<NovelCreationTaskVO> novelTaskStatus(@PathVariable String taskId) {
        return R.ok(novelCreationTaskService.status(taskId, UserContextHolder.requireUserId()));
    }
}
