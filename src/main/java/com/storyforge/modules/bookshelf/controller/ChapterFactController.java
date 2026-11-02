package com.storyforge.modules.bookshelf.controller;

import com.storyforge.common.api.R;
import com.storyforge.modules.bookshelf.dto.ChapterFactsVO;
import com.storyforge.modules.bookshelf.dto.ForeshadowRequest;
import com.storyforge.modules.bookshelf.dto.ForeshadowVO;
import com.storyforge.modules.bookshelf.service.ChapterFactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 事实层：章节事实摘要、角色状态、伏笔台账。
 *
 * <p>这一层的意义是「让下一章读到真正发生过的事」，而不是只读大纲。
 * 自动写书正在跑时这些写入口会被只读锁拦下（{@code 40073}）。</p>
 */
@RestController
@RequestMapping("/api/bookshelf")
@RequiredArgsConstructor
@Tag(name = "事实层", description = "章节事实摘要、角色状态快照与伏笔台账")
public class ChapterFactController {

    private final ChapterFactService factService;

    @GetMapping("/chapters/{chapterId}/facts")
    @Operation(summary = "本章事实层", description = "事实摘要 + 本章埋/收的伏笔 + 本章有状态变化的角色")
    public R<ChapterFactsVO> facts(@PathVariable Long chapterId) {
        return R.ok(factService.detail(chapterId));
    }

    @PutMapping("/chapters/{chapterId}/fact-summary")
    @Operation(summary = "手工编辑事实摘要", description = "来源置为 MANUAL，之后重新分析不会覆盖")
    public R<ChapterFactsVO> updateFactSummary(@PathVariable Long chapterId,
                                               @RequestBody FactSummaryRequest request) {
        return R.ok(factService.updateFactSummary(chapterId,
                request == null ? null : request.factSummary()));
    }

    @GetMapping("/novels/{novelId}/foreshadows")
    @Operation(summary = "伏笔列表", description = "按状态筛选；overdue 为实时算出的超期标记")
    public R<List<ForeshadowVO>> foreshadows(@PathVariable Long novelId,
                                             @RequestParam(required = false) String status) {
        return R.ok(factService.list(novelId, status));
    }

    @PostMapping("/novels/{novelId}/foreshadows")
    @Operation(summary = "新增伏笔", description = "埋设章留空时取当前最新章")
    public R<ForeshadowVO> createForeshadow(@PathVariable Long novelId,
                                            @RequestBody ForeshadowRequest request) {
        return R.ok(factService.create(novelId, request));
    }

    @PutMapping("/foreshadows/{id}")
    @Operation(summary = "编辑伏笔", description = "含标记已回收 / 放弃；已回收的不能重复标记")
    public R<ForeshadowVO> updateForeshadow(@PathVariable Long id,
                                            @RequestBody ForeshadowRequest request) {
        return R.ok(factService.update(id, request));
    }

    @DeleteMapping("/foreshadows/{id}")
    @Operation(summary = "删除伏笔", description = "逻辑删")
    public R<Void> deleteForeshadow(@PathVariable Long id) {
        factService.delete(id);
        return R.ok(null);
    }

    /** 手工编辑事实摘要的入参 */
    public record FactSummaryRequest(String factSummary) {
    }
}
