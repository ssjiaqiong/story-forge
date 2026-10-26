package com.storyforge.modules.bookshelf.controller;

import com.storyforge.common.api.PageResult;
import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.bookshelf.dto.ChapterRequest;
import com.storyforge.modules.bookshelf.dto.ChapterVersionDetailVO;
import com.storyforge.modules.bookshelf.dto.ChapterVersionVO;
import com.storyforge.modules.bookshelf.dto.ChapterVO;
import com.storyforge.modules.bookshelf.dto.CharacterGenerateRequest;
import com.storyforge.modules.bookshelf.dto.CharacterGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.CharacterRelationVO;
import com.storyforge.modules.bookshelf.dto.CharacterSaveRequest;
import com.storyforge.modules.bookshelf.dto.CharacterTreeNode;
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.ContentGenerateRequest;
import com.storyforge.modules.bookshelf.dto.ContentGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.EventGenerateRequest;
import com.storyforge.modules.bookshelf.dto.EventGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.ItemGenerateRequest;
import com.storyforge.modules.bookshelf.dto.ItemGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.ItemRequest;
import com.storyforge.modules.bookshelf.dto.ItemVO;
import com.storyforge.modules.bookshelf.dto.OutlineGenerateRequest;
import com.storyforge.modules.bookshelf.dto.OutlineGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.RelationRequest;
import com.storyforge.modules.bookshelf.dto.TimelineRequest;
import com.storyforge.modules.bookshelf.dto.TimelineVO;
import com.storyforge.modules.bookshelf.service.AiCharacterService;
import com.storyforge.modules.bookshelf.service.AiContentService;
import com.storyforge.modules.bookshelf.service.AiEventService;
import com.storyforge.modules.bookshelf.service.AiItemService;
import com.storyforge.modules.bookshelf.service.AiOutlineService;
import com.storyforge.modules.bookshelf.service.ChapterVersionService;
import com.storyforge.modules.bookshelf.service.ContentStreamService;
import com.storyforge.modules.bookshelf.service.NovelAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 小说工作台：章节（大纲 / 正文）、时间线、物品、角色。
 */
@Tag(name = "小说工作台", description = "章节、时间线、物品与角色的增删改查")
@RestController
@RequestMapping("/api/bookshelf")
@RequiredArgsConstructor
public class NovelAssetController {

    private final NovelAssetService assetService;
    private final AiCharacterService aiCharacterService;
    private final AiEventService aiEventService;
    private final AiOutlineService aiOutlineService;
    private final AiContentService aiContentService;
    private final AiItemService aiItemService;
    private final ChapterVersionService chapterVersionService;
    private final ContentStreamService contentStreamService;

    /* ---------------- 章节 ---------------- */

    @Operation(summary = "章节列表", description = "返回摘要与字数，不含正文")
    @GetMapping("/novels/{novelId}/chapters")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<ChapterVO>> listChapters(@PathVariable Long novelId) {
        return R.ok(assetService.listChapters(novelId));
    }

    @Operation(summary = "章节详情", description = "含正文，用于正文编辑器")
    @GetMapping("/chapters/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<ChapterVO> getChapter(@PathVariable Long id) {
        return R.ok(assetService.getChapter(id));
    }

    @Operation(summary = "新增章节")
    @PostMapping("/novels/{novelId}/chapters")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<ChapterVO> createChapter(@PathVariable Long novelId, @Valid @RequestBody ChapterRequest request) {
        return R.ok(assetService.createChapter(novelId, request));
    }

    @Operation(summary = "修改章节", description = "可改标题、所属卷、摘要、正文与状态")
    @PutMapping("/chapters/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<ChapterVO> updateChapter(@PathVariable Long id, @Valid @RequestBody ChapterRequest request) {
        return R.ok(assetService.updateChapter(id, request));
    }

    @Operation(summary = "AI 生成大纲", description = "按作品设定与要求一次生成多章标题与摘要，默认联网检索资料")
    @PostMapping("/novels/{novelId}/outline/generate")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public R<OutlineGenerateResultVO> generateOutline(@PathVariable Long novelId,
                                                      @Valid @RequestBody OutlineGenerateRequest request) {
        return R.ok(aiOutlineService.generate(novelId, request));
    }

    @Operation(summary = "AI 写本章正文", description = "按设定、本章大纲与前情生成正文，默认联网检索资料")
    @PostMapping("/chapters/{id}/generate")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public R<ContentGenerateResultVO> generateContent(@PathVariable Long id,
                                                      @Valid @RequestBody ContentGenerateRequest request) {
        return R.ok(aiContentService.generate(id, request));
    }

    @Operation(summary = "删除章节")
    @DeleteMapping("/chapters/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> deleteChapter(@PathVariable Long id) {
        assetService.deleteChapter(id);
        return R.ok();
    }

    /**
     * 流式写正文。
     *
     * <p>返回 {@code text/event-stream}：{@code meta} → 多个 {@code delta} → {@code done}（或 {@code error}）。
     * 业务异常发生在「建流之前」（本章已有生成在跑、线程池满）时仍按普通 JSON 返回，
     * 因此前端要先看响应头再决定按 SSE 读还是按 JSON 解析。</p>
     *
     * <p>这里刻意<b>不写</b> {@code produces = text/event-stream}：一旦写了，之后任何同步错误
     * （参数校验、404、建流失败）在内容协商时都会要求以 {@code text/event-stream} 输出 JSON，
     * 结果全部变成 500「No acceptable representation」。响应头由 {@link SseEmitter} 自己带上。</p>
     */
    @Operation(summary = "AI 写本章正文（流式）",
            description = "SSE 逐字推送；事件为 meta / delta / done / error。旧的非流式接口仍然保留")
    @PostMapping("/chapters/{id}/generate/stream")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public SseEmitter generateContentStream(@PathVariable Long id,
                                           @Valid @RequestBody ContentGenerateRequest request,
                                           HttpServletResponse response) {
        // nginx 侧已配 proxy_buffering off；这里再声明一次，兼容没改过配置的部署
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        return contentStreamService.start(id, request);
    }

    /* ---------------- 章节正文历史版本 ---------------- */

    @Operation(summary = "历史版本列表",
            description = "按版本号倒序返回，**不含正文**；正文变化时才会产生版本（保存前的旧内容）")
    @GetMapping("/chapters/{id}/versions")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<PageResult<ChapterVersionVO>> listVersions(@PathVariable Long id,
                                                        @RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "20") long size) {
        return R.ok(chapterVersionService.list(id, page, size));
    }

    @Operation(summary = "历史版本数量", description = "前端角标用，避免为了显示条数拉整页数据")
    @GetMapping("/chapters/{id}/versions/count")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<Long> countVersions(@PathVariable Long id) {
        return R.ok(chapterVersionService.count(id));
    }

    @Operation(summary = "历史版本全文", description = "用于查看与对比当前正文")
    @GetMapping("/chapters/{id}/versions/{versionId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<ChapterVersionDetailVO> getVersion(@PathVariable Long id, @PathVariable Long versionId) {
        return R.ok(chapterVersionService.detail(id, versionId));
    }

    @Operation(summary = "回滚到历史版本",
            description = "回滚前的当前稿会先存为新版本，可再回滚回来；已标记「已完成」的章节也允许回滚")
    @PostMapping("/chapters/{id}/versions/{versionId}/restore")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<ChapterVO> restoreVersion(@PathVariable Long id, @PathVariable Long versionId) {
        return R.ok(assetService.restoreVersion(id, versionId));
    }

    @Operation(summary = "删除历史版本", description = "只删历史，不影响当前正文")
    @DeleteMapping("/chapters/{id}/versions/{versionId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> deleteVersion(@PathVariable Long id, @PathVariable Long versionId) {
        chapterVersionService.delete(id, versionId);
        return R.ok();
    }

    /* ---------------- 时间线 ---------------- */

    @Operation(summary = "时间线列表")
    @GetMapping("/novels/{novelId}/timeline")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<TimelineVO>> listTimeline(@PathVariable Long novelId) {
        return R.ok(assetService.listTimeline(novelId));
    }

    @Operation(summary = "AI 生成事件", description = "事件只能由 AI 创建；按提示语一次生成 1~10 个")
    @PostMapping("/novels/{novelId}/timeline/generate")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public R<EventGenerateResultVO> generateEvents(@PathVariable Long novelId,
                                                   @Valid @RequestBody EventGenerateRequest request) {
        return R.ok(aiEventService.generate(novelId, request));
    }

    @Operation(summary = "修改时间线事件")
    @PutMapping("/timeline/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<TimelineVO> updateTimeline(@PathVariable Long id, @Valid @RequestBody TimelineRequest request) {
        return R.ok(assetService.updateTimeline(id, request));
    }

    @Operation(summary = "删除时间线事件")
    @DeleteMapping("/timeline/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> deleteTimeline(@PathVariable Long id) {
        assetService.deleteTimeline(id);
        return R.ok();
    }

    /* ---------------- 物品 ---------------- */

    @Operation(summary = "物品列表")
    @GetMapping("/novels/{novelId}/items")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<ItemVO>> listItems(@PathVariable Long novelId) {
        return R.ok(assetService.listItems(novelId));
    }

    @Operation(summary = "AI 创建物品", description = "按提示语结合作品设定、角色与已有物品生成道具并落库")
    @PostMapping("/novels/{novelId}/items/generate")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public R<ItemGenerateResultVO> generateItems(@PathVariable Long novelId,
                                                 @Valid @RequestBody ItemGenerateRequest request) {
        return R.ok(aiItemService.generate(novelId, request));
    }

    @Operation(summary = "新增物品")
    @PostMapping("/novels/{novelId}/items")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<ItemVO> createItem(@PathVariable Long novelId, @Valid @RequestBody ItemRequest request) {
        return R.ok(assetService.createItem(novelId, request));
    }

    @Operation(summary = "修改物品")
    @PutMapping("/items/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<ItemVO> updateItem(@PathVariable Long id, @Valid @RequestBody ItemRequest request) {
        return R.ok(assetService.updateItem(id, request));
    }

    @Operation(summary = "删除物品")
    @DeleteMapping("/items/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> deleteItem(@PathVariable Long id) {
        assetService.deleteItem(id);
        return R.ok();
    }

    /* ---------------- 角色 ---------------- */

    @Operation(summary = "角色列表")
    @GetMapping("/novels/{novelId}/characters")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<CharacterVO>> listCharacters(@PathVariable Long novelId) {
        return R.ok(assetService.listCharacters(novelId));
    }

    @Operation(summary = "AI 创建角色", description = "按一段提示语生成角色档案与角色关系，单次最长 5 分钟")
    @PostMapping("/novels/{novelId}/characters/generate")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public R<CharacterGenerateResultVO> generateCharacters(@PathVariable Long novelId,
                                                          @Valid @RequestBody CharacterGenerateRequest request) {
        return R.ok(aiCharacterService.generate(novelId, request));
    }

    @Operation(summary = "新增角色")
    @PostMapping("/novels/{novelId}/characters")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<CharacterVO> createCharacter(@PathVariable Long novelId, @Valid @RequestBody CharacterSaveRequest request) {
        return R.ok(assetService.createCharacter(novelId, request));
    }

    @Operation(summary = "修改角色")
    @PutMapping("/characters/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<CharacterVO> updateCharacter(@PathVariable Long id, @Valid @RequestBody CharacterSaveRequest request) {
        return R.ok(assetService.updateCharacter(id, request));
    }

    @Operation(summary = "删除角色", description = "同时删除与该角色相关的所有关系")
    @DeleteMapping("/characters/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> deleteCharacter(@PathVariable Long id) {
        assetService.deleteCharacter(id);
        return R.ok();
    }

    /* ---------------- 角色关系 ---------------- */

    @Operation(summary = "关系列表", description = "对称关系，两端顺序不影响语义")
    @GetMapping("/novels/{novelId}/relations")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<CharacterRelationVO>> listRelations(@PathVariable Long novelId) {
        return R.ok(assetService.listRelations(novelId));
    }

    @Operation(summary = "人物关系树", description = "以主角为根，返回森林；孤立角色作为单节点树")
    @GetMapping("/novels/{novelId}/relations/tree")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<CharacterTreeNode>> relationTree(@PathVariable Long novelId) {
        return R.ok(assetService.relationTree(novelId));
    }

    @Operation(summary = "新增人物关系")
    @PostMapping("/novels/{novelId}/relations")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<CharacterRelationVO> createRelation(@PathVariable Long novelId, @Valid @RequestBody RelationRequest request) {
        return R.ok(assetService.createRelation(novelId, request));
    }

    @Operation(summary = "修改人物关系")
    @PutMapping("/relations/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<CharacterRelationVO> updateRelation(@PathVariable Long id, @Valid @RequestBody RelationRequest request) {
        return R.ok(assetService.updateRelation(id, request));
    }

    @Operation(summary = "删除人物关系")
    @DeleteMapping("/relations/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> deleteRelation(@PathVariable Long id) {
        assetService.deleteRelation(id);
        return R.ok();
    }
}
