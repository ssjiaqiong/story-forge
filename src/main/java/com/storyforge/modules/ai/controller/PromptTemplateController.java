package com.storyforge.modules.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.R;
import com.storyforge.common.audit.Audit;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.ai.dto.PromptExportPackage;
import com.storyforge.modules.ai.dto.PromptImportResult;
import com.storyforge.modules.ai.dto.PromptTemplateSaveRequest;
import com.storyforge.modules.ai.dto.PromptTemplateVO;
import com.storyforge.modules.ai.service.PromptTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 设置中心 · 提示词模板。
 *
 * <p>用户只能改「指令段」；输出格式契约由系统追加，接口里只读返回。</p>
 */
@Tag(name = "设置 - 提示词模板", description = "按 AI 场景定制提示词；输出格式由系统统一封装")
@RestController
@RequestMapping("/api/prompt-templates")
@RequiredArgsConstructor
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Operation(summary = "场景模板列表")
    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<PromptTemplateVO.Item>> list() {
        return R.ok(promptTemplateService.list());
    }

    @Operation(summary = "场景模板详情", description = "含默认模板、当前生效模板、可用变量与锁定的输出格式")
    @GetMapping("/{scene}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<PromptTemplateVO.Detail> detail(@PathVariable String scene) {
        return R.ok(promptTemplateService.detail(scene));
    }

    @Operation(summary = "保存模板")
    @PutMapping("/{scene}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<PromptTemplateVO.Detail> save(@PathVariable String scene,
                                           @Valid @RequestBody PromptTemplateSaveRequest request) {
        return R.ok(promptTemplateService.save(scene, request.getContent()));
    }

    @Operation(summary = "恢复默认")
    @DeleteMapping("/{scene}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> reset(@PathVariable String scene) {
        promptTemplateService.reset(scene);
        return R.ok();
    }

    @Operation(summary = "预览渲染结果", description = "用示例变量渲染，不调用 AI")
    @PostMapping("/{scene}/preview")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<PromptTemplateVO.Preview> preview(@PathVariable String scene) {
        return R.ok(promptTemplateService.preview(scene));
    }

    @Operation(summary = "导出提示词模板",
            description = "导出为 JSON 包；默认只导出用户改过的场景（默认模板随版本升级会变，回灌无意义）")
    @GetMapping("/export")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public ResponseEntity<byte[]> export(
            @Parameter(description = "是否只导出「用户改过」的场景，默认 true")
            @RequestParam(defaultValue = "true") boolean onlyOverridden) throws Exception {
        PromptExportPackage pkg = promptTemplateService.exportPackage(onlyOverridden);
        byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pkg);
        String stamp = LocalDateTime.now().format(FILE_STAMP);
        // 纯 ASCII 文件名避免响应头编码问题，中文名放 filename*
        String asciiName = "storyforge-prompts-" + stamp + ".json";
        String encodedName = URLEncoder.encode("提示词模板-" + stamp + ".json", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @Operation(summary = "导入提示词模板",
            description = "把导出的 JSON 写入当前账号：逐条校验、单条失败只跳过该条；"
                    + "mode=MERGE 覆盖同名 / SKIP_EXISTING 只填空缺 / REPLACE 先清空再写")
    @PostMapping("/import")
    @Audit(action = "PROMPT_TEMPLATE_IMPORT", targetType = "PROMPT_TEMPLATE")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public R<PromptImportResult> importTemplates(@RequestBody PromptExportPackage pkg,
                                                 @Parameter(description = "MERGE / SKIP_EXISTING / REPLACE")
                                                 @RequestParam(defaultValue = "MERGE") String mode) {
        return R.ok(promptTemplateService.importPackage(pkg, mode));
    }
}
