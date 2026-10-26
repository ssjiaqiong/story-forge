package com.storyforge.modules.novel.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.audit.Audit;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.novel.dto.CoverGenerateRequest;
import com.storyforge.modules.novel.dto.NovelCoverVO;
import com.storyforge.modules.novel.service.NovelCoverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 小说封面：生成、切换、删除与取图。
 *
 * <p>取图接口刻意做成需要登录态的 {@code /api} 接口而不是静态目录：封面上传/生成的都是用户私有素材，
 * 做成 {@code /media/**} 公开路径等于把所有人的封面暴露成可枚举资源。前端按需拉 blob 展示即可。</p>
 */
@Tag(name = "小说封面", description = "文生图封面：生成、设为主封面、删除、取图")
@RestController
@RequestMapping("/api/novels")
@RequiredArgsConstructor
public class NovelCoverController {

    private final NovelCoverService coverService;

    @Operation(summary = "封面列表", description = "主封面在前，其余按书架切换顺序")
    @GetMapping("/{novelId}/covers")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<NovelCoverVO>> list(@PathVariable Long novelId) {
        return R.ok(coverService.list(novelId));
    }

    @Operation(summary = "生成封面", description = "调用文生图接口生成一张并落盘；一部小说最多 5 张")
    @PostMapping("/{novelId}/covers/generate")
    @Audit(action = "NOVEL_COVER_GENERATE", targetType = "NOVEL", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 6, windowSeconds = 60)
    public R<NovelCoverVO> generate(@PathVariable Long novelId,
                                    @Valid @RequestBody(required = false) CoverGenerateRequest request) {
        return R.ok(coverService.generate(novelId, request));
    }

    @Operation(summary = "设为主封面")
    @PutMapping("/covers/{coverId}/primary")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<NovelCoverVO> setPrimary(@PathVariable Long coverId) {
        return R.ok(coverService.setPrimary(coverId));
    }

    @Operation(summary = "删除封面")
    @DeleteMapping("/covers/{coverId}")
    @Audit(action = "NOVEL_COVER_DELETE", targetType = "NOVEL_COVER", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> delete(@PathVariable Long coverId) {
        coverService.delete(coverId);
        return R.ok();
    }

    @Operation(summary = "取封面图片", description = "需带登录态；返回图片二进制")
    @GetMapping("/covers/{coverId}/image")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 1200, windowSeconds = 60)
    public ResponseEntity<Resource> image(@PathVariable Long coverId) {
        NovelCoverService.CoverImage image = coverService.loadImage(coverId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.mimeType()))
                // 同一 coverId 的内容不会变，允许浏览器长期缓存；private 防止中间代理缓存
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate().immutable())
                .body(new ByteArrayResource(image.data()));
    }
}
