package com.storyforge.modules.bookshelf.controller;

import com.storyforge.common.api.PageQuery;
import com.storyforge.common.api.PageResult;
import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.bookshelf.dto.DeconstructSummaryVO;
import com.storyforge.modules.bookshelf.dto.DeconstructTaskVO;
import com.storyforge.modules.bookshelf.service.BookDeconstructService;
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
 * 拆书：上传成稿小说，AI 拆出主线并按改编幅度重建一部新作（不含正文）。
 */
@Tag(name = "拆书改编", description = "上传小说 → 主线分析 → 脱敏改编 → 生成新书设定与大纲")
@RestController
@RequestMapping("/api/bookshelf/deconstruct")
@RequiredArgsConstructor
public class BookDeconstructController {

    private final BookDeconstructService deconstructService;

    @Operation(summary = "上传并开始拆书",
            description = "接收 txt / md 纯文本，按改编程度（LIGHT/MEDIUM/HEAVY/REBUILD）重建世界观、人物、势力、事件与章节大纲；"
                    + "返回 taskId 供轮询进度，原文不落库")
    @PostMapping(value = "/tasks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 12, windowSeconds = 60)
    public R<DeconstructTaskVO> start(@RequestPart("file") MultipartFile file,
                                      @RequestParam(required = false) String adaptationLevel,
                                      @RequestParam(required = false) String backgroundDirection,
                                      @RequestParam(required = false) Integer chapterTarget) {
        return R.ok(deconstructService.start(file, adaptationLevel, backgroundDirection, chapterTarget));
    }

    @Operation(summary = "查询拆书进度", description = "返回百分比、当前阶段、主线脉络与改编结果")
    @GetMapping("/tasks/{taskId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 900, windowSeconds = 60)
    public R<DeconstructTaskVO> status(@PathVariable String taskId) {
        return R.ok(deconstructService.status(taskId));
    }

    @Operation(summary = "拆书记录列表", description = "当前用户的历史拆书记录，按创建时间倒序")
    @GetMapping("/tasks")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<PageResult<DeconstructSummaryVO>> list(PageQuery query) {
        return R.ok(deconstructService.list(query));
    }

    @Operation(summary = "保存为新小说", description = "把拆书结果落库为小说 + 设定 + 人物 + 关系 + 势力 + 事件 + 章节大纲；幂等")
    @PostMapping("/tasks/{taskId}/save")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public R<Long> save(@PathVariable String taskId) {
        return R.ok(deconstructService.save(taskId));
    }

    @Operation(summary = "删除拆书记录", description = "只删除分析记录，已生成的小说保留")
    @DeleteMapping("/tasks/{taskId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public R<Void> delete(@PathVariable String taskId) {
        deconstructService.delete(taskId);
        return R.ok();
    }
}
