package com.storyforge.modules.bookshelf.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.bookshelf.dto.BlueprintQuestionsVO;
import com.storyforge.modules.bookshelf.dto.BlueprintRequest;
import com.storyforge.modules.bookshelf.dto.BlueprintResultVO;
import com.storyforge.modules.bookshelf.dto.BlueprintTaskVO;
import com.storyforge.modules.bookshelf.dto.ConstraintFormRequest;
import com.storyforge.modules.bookshelf.dto.ConstraintTextVO;
import com.storyforge.modules.bookshelf.dto.NovelCreateRequest;
import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.dto.NovelEditRequest;
import com.storyforge.modules.bookshelf.dto.NovelExportPackage;
import com.storyforge.modules.bookshelf.dto.TxtExportOptions;
import com.storyforge.modules.bookshelf.service.BlueprintQuestionService;
import com.storyforge.modules.bookshelf.service.BlueprintTaskService;
import com.storyforge.modules.bookshelf.service.BookshelfService;
import com.storyforge.modules.bookshelf.service.NovelTransferService;
import com.storyforge.modules.bookshelf.service.NovelTxtExportService;
import com.storyforge.modules.novel.dto.NovelVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 书架：AI 生成小说设定并创建小说。
 *
 * <p>列表复用 {@code GET /api/novels}，本控制器只负责创建链路与设定维护。</p>
 */
@Tag(name = "书架", description = "AI 生成世界观 / 背景 / 主角并创建小说")
@RestController
@RequestMapping("/api/bookshelf")
@RequiredArgsConstructor
public class BookshelfController {

    private final BookshelfService bookshelfService;
    private final BlueprintTaskService blueprintTaskService;
    private final BlueprintQuestionService blueprintQuestionService;
    private final NovelTransferService novelTransferService;
    private final NovelTxtExportService novelTxtExportService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "建书前的信息追问",
            description = "让 AI 判断这个创意还缺哪些关键信息（最多 4 个，字段受固定白名单约束）；"
                    + "创意已经写清时可能返回空列表，前端照常进入下一步")
    @PostMapping("/blueprint/questions")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public R<BlueprintQuestionsVO> blueprintQuestions(@Valid @RequestBody BlueprintRequest request) {
        return R.ok(blueprintQuestionService.questions(request));
    }

    @Operation(summary = "组装创作约束",
            description = "把追问的作答拼成「中文名：答案」的多行文本，随蓝图生成请求一起提交并落库")
    @PostMapping("/blueprint/constraints")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<ConstraintTextVO> assembleConstraints(@RequestBody ConstraintFormRequest request) {
        ConstraintTextVO vo = new ConstraintTextVO();
        vo.setConstraintText(blueprintQuestionService.assemble(request));
        return R.ok(vo);
    }

    @Operation(summary = "创建蓝图生成任务",
            description = "分段生成世界观 / 背景 / 核心冲突 / 人物，每段超时 5 分钟；返回 taskId 供轮询进度")
    @PostMapping("/blueprint/tasks")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public R<BlueprintTaskVO> startBlueprintTask(@Valid @RequestBody BlueprintRequest request) {
        return R.ok(blueprintTaskService.start(request, UserContextHolder.requireUserId()));
    }

    @Operation(summary = "查询蓝图生成进度", description = "返回百分比、当前阶段文案与最终结果")
    @GetMapping("/blueprint/tasks/{taskId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<BlueprintTaskVO> blueprintTaskStatus(@PathVariable String taskId) {
        return R.ok(blueprintTaskService.status(taskId, UserContextHolder.requireUserId()));
    }

    @Operation(summary = "生成小说蓝图",
            description = "根据用户填写的核心创意等信息调用 AI，返回世界观、背景、简介、主角与配角设定")
    @PostMapping("/blueprint")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 20, windowSeconds = 60)
    public R<BlueprintResultVO> generateBlueprint(@Valid @RequestBody BlueprintRequest request) {
        return R.ok(bookshelfService.generateBlueprint(request));
    }

    @Operation(summary = "保存到书架", description = "创建小说；携带 blueprint 时一并写入设定与角色")
    @PostMapping("/novels")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<NovelVO> create(@Valid @RequestBody NovelCreateRequest request) {
        return R.ok(bookshelfService.create(request));
    }

    @Operation(summary = "小说详情", description = "返回基本信息、设定与角色")
    @GetMapping("/novels/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<NovelDetailVO> detail(@PathVariable Long id) {
        return R.ok(bookshelfService.detail(id));
    }

    @Operation(summary = "编辑小说", description = "修改基本信息；携带 blueprint 时整体覆盖设定与角色")
    @PutMapping("/novels/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<NovelVO> update(@PathVariable Long id, @Valid @RequestBody NovelEditRequest request) {
        return R.ok(bookshelfService.update(id, request));
    }

    @Operation(summary = "删除小说", description = "逻辑删除小说及其设定与角色")
    @DeleteMapping("/novels/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<Void> delete(@PathVariable Long id) {
        bookshelfService.delete(id);
        return R.ok();
    }

    @Operation(summary = "导出小说",
            description = "把整本小说的全部内容导出为 JSON 文件：基本信息、设定、总纲、人物、势力、"
                    + "物品、事件、章节（共图与正文）、分析、履历、助手会话、封面（Base64 内嵌）")
    @GetMapping("/novels/{id}/export")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public ResponseEntity<byte[]> export(@PathVariable Long id) throws Exception {
        NovelExportPackage pkg = novelTransferService.export(id);
        byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pkg);

        String title = pkg.getNovel().getTitle() == null ? "novel" : pkg.getNovel().getTitle();
        String asciiName = "novel-" + id + "-export.json";
        String encodedName = URLEncoder.encode(safeFilename(title) + "-export.json", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @Operation(summary = "导出小说正文为 TXT",
            description = "给读者/投稿平台看的成品格式：只有卷名、章节标题与正文，"
                    + "不含设定、大纲摘要与任何 ID。可按全书、指定卷或章节区间导出，默认写 UTF-8 BOM")
    @GetMapping("/novels/{id}/export/txt")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public ResponseEntity<byte[]> exportTxt(@PathVariable Long id,
                                            @RequestParam(defaultValue = "all") String scope,
                                            @RequestParam(required = false) Integer volumeNo,
                                            @RequestParam(required = false) Integer from,
                                            @RequestParam(required = false) Integer to,
                                            @RequestParam(defaultValue = "true") boolean bom,
                                            @RequestParam(defaultValue = "false") boolean includeEmpty,
                                            @RequestParam(defaultValue = "true") boolean withVolume) {
        TxtExportOptions options = new TxtExportOptions();
        options.setScope(scope);
        options.setVolumeNo(volumeNo);
        options.setFrom(from);
        options.setTo(to);
        options.setBom(bom);
        options.setIncludeEmpty(includeEmpty);
        options.setWithVolume(withVolume);

        NovelTxtExportService.TxtFile file = novelTxtExportService.exportTxt(id, options);
        byte[] body = file.text().getBytes(StandardCharsets.UTF_8);

        String title = file.title() == null ? "novel" : file.title();
        String asciiName = "novel-" + id + "-export.txt";
        String encodedName = URLEncoder.encode(safeFilename(title) + ".txt", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType("text/plain; charset=utf-8"))
                .body(body);
    }

    @Operation(summary = "导入小说",
            description = "把导出的 JSON 重建为当前账号下的一本新书；原 id 全部重映射，封面重新落盘，书名冲突自动追加序号")
    @PostMapping("/novels/import")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public R<NovelVO> importNovel(@RequestBody NovelExportPackage pkg) {
        return R.ok(novelTransferService.importPackage(pkg));
    }

    /** 文件名去掉路径分隔符与控制字符，避免响应头注入。 */
    private String safeFilename(String raw) {
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (cleaned.isEmpty()) {
            cleaned = "novel";
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}
