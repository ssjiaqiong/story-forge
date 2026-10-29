package com.storyforge.modules.bookshelf.controller;

import com.storyforge.common.api.PageQuery;
import com.storyforge.common.api.PageResult;
import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.bookshelf.dto.ImportSummaryVO;
import com.storyforge.modules.bookshelf.dto.ImportTaskVO;
import com.storyforge.modules.bookshelf.service.NovelImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 整本导入：把只有正文的 TXT 成稿导入成一本可续写的书。
 *
 * <p>与「拆书改编」的区别：拆书读原文后丢弃、产出改编大纲；导入把原文按章完整落库，
 * 再用 AI 逆推出这本书自己的设定、人物、物品、势力、总纲与逐章大纲。</p>
 */
@Tag(name = "整本导入", description = "TXT 成稿 → 原文入库 → AI 逆推设定/人物/物品/总纲/逐章大纲")
@RestController
@RequestMapping("/api/bookshelf/import")
@RequiredArgsConstructor
public class NovelImportController {

    private final NovelImportService importService;

    @Operation(summary = "上传 TXT 并开始导入",
            description = "接收 txt / md 纯文本，按章切分后完整入库，并异步逆推全书档案；返回 taskId 供轮询进度")
    @PostMapping(value = "/tasks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 6, windowSeconds = 60)
    public R<ImportTaskVO> start(@RequestPart("file") MultipartFile file,
                                 @RequestParam(required = false) String title,
                                 @RequestParam(required = false) String penName) {
        return R.ok(importService.start(file, title, penName));
    }

    @Operation(summary = "查询导入进度", description = "返回百分比、当前阶段、已入库章节数与逆推摘要")
    @GetMapping("/tasks/{taskId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 900, windowSeconds = 60)
    public R<ImportTaskVO> status(@PathVariable String taskId) {
        return R.ok(importService.status(taskId));
    }

    @Operation(summary = "继续分析", description = "正文已入库但 AI 逆推失败时重跑分析，不重建书、不重插章节")
    @PostMapping("/tasks/{taskId}/retry")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 12, windowSeconds = 60)
    public R<ImportTaskVO> retry(@PathVariable String taskId) {
        return R.ok(importService.retry(taskId));
    }

    @Operation(summary = "导入记录列表", description = "当前用户的历史导入记录，按创建时间倒序")
    @GetMapping("/tasks")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<PageResult<ImportSummaryVO>> list(PageQuery query) {
        return R.ok(importService.list(query));
    }

    @Operation(summary = "删除导入记录", description = "只删除导入记录，已生成的小说保留")
    @DeleteMapping("/tasks/{taskId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public R<Void> delete(@PathVariable String taskId) {
        importService.delete(taskId);
        return R.ok();
    }
}
