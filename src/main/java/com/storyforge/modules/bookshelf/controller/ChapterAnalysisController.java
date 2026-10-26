package com.storyforge.modules.bookshelf.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisOverviewVO;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisRequest;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisSummaryVO;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisVO;
import com.storyforge.modules.bookshelf.dto.ChapterReviseRequest;
import com.storyforge.modules.bookshelf.dto.ChapterReviseVO;
import com.storyforge.modules.bookshelf.dto.ChangeHistoryVO;
import com.storyforge.modules.bookshelf.service.ChapterAnalysisService;
import com.storyforge.modules.bookshelf.service.ChapterReviseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 章节分析与要素变更履历。
 *
 * <p>分析按章节顺序进行：前面的章节都分析完成后，后面的章节才能开始，
 * 否则「新出现的人物」「刚结束的事件」都无从判断。</p>
 */
@RestController
@RequestMapping("/api/bookshelf")
@RequiredArgsConstructor
@Tag(name = "章节分析", description = "最后一章的字数、大纲贴合度、AI 味检测与设定同步")
public class ChapterAnalysisController {

    private final ChapterAnalysisService analysisService;
    private final ChapterReviseService reviseService;

    @PostMapping("/chapters/{chapterId}/analysis")
    @Operation(summary = "分析章节", description = "按章节顺序分析；同步新人物、变更履历与事件状态")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 8, windowSeconds = 60)
    public R<ChapterAnalysisVO> analyze(@PathVariable Long chapterId,
                                        @RequestBody(required = false) ChapterAnalysisRequest request) {
        return R.ok(analysisService.analyze(chapterId, request));
    }

    @PostMapping("/chapters/{chapterId}/revise")
    @Operation(summary = "按分析修改正文", description = "按勾选的审稿问题与作者要求改写本章，只返回新稿不落库")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 6, windowSeconds = 60)
    public R<ChapterReviseVO> revise(@PathVariable Long chapterId,
                                     @RequestBody(required = false) ChapterReviseRequest request) {
        return R.ok(reviseService.revise(chapterId, request));
    }

    @GetMapping("/novels/{novelId}/analysis-overview")
    @Operation(summary = "分析面板章节列表", description = "每章最近一次分析结果、是否可分析及锁定原因")
    public R<List<ChapterAnalysisOverviewVO>> overview(@PathVariable Long novelId) {
        return R.ok(analysisService.overview(novelId));
    }

    @GetMapping("/chapters/{chapterId}/analyses")
    @Operation(summary = "章节分析历史")
    public R<List<ChapterAnalysisSummaryVO>> history(@PathVariable Long chapterId) {
        return R.ok(analysisService.listByChapter(chapterId));
    }

    @GetMapping("/analyses/{id}")
    @Operation(summary = "分析报告详情")
    public R<ChapterAnalysisVO> detail(@PathVariable Long id) {
        return R.ok(analysisService.get(id));
    }

    @GetMapping("/novels/{novelId}/change-histories")
    @Operation(summary = "要素变更履历", description = "按类型 / 实体过滤，默认返回全书最近 200 条")
    public R<List<ChangeHistoryVO>> histories(@PathVariable Long novelId,
                                              @RequestParam(required = false) String entityType,
                                              @RequestParam(required = false) Long entityId) {
        return R.ok(analysisService.listHistories(novelId, entityType, entityId));
    }
}
