package com.storyforge.modules.novel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.ImageEndpoint;
import com.storyforge.infra.ai.ImageGenClient;
import com.storyforge.infra.ai.ImageGenRequest;
import com.storyforge.infra.ai.ImageGenResult;
import com.storyforge.infra.image.CoverImageNormalizer;
import com.storyforge.infra.image.CoverTextOverlay;
import com.storyforge.infra.storage.CoverStorage;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.ai.service.ImageEndpointResolver;
import com.storyforge.modules.ai.service.PromptTemplateService;
import com.storyforge.modules.novel.dto.CoverGenerateRequest;
import com.storyforge.modules.novel.dto.NovelCoverVO;
import com.storyforge.modules.novel.entity.NovelCoverEntity;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelCoverMapper;
import com.storyforge.modules.novel.mapper.NovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 小说封面服务实现。
 *
 * <p>不变量：</p>
 * <ol>
 *   <li>一部小说最多 5 张封面，{@code sort_no} 始终保持 0..n-1 连续，书架按此顺序左右切换；</li>
 *   <li>每部小说至多一张 {@code is_primary}，删除主封面后自动把第一张补位；</li>
 *   <li>图片一律存本地目录（{@code user_id/novel_id/{uuid}.jpg}），表里只存相对路径；</li>
 *   <li>取图必须带登录态：封面是用户私有资源，不做公开静态目录。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelCoverServiceImpl implements NovelCoverService {

    /** 一部小说最多保留的封面数 */
    private static final int MAX_COVERS = 5;

    private final NovelCoverMapper coverMapper;
    private final NovelMapper novelMapper;
    private final ImageEndpointResolver imageEndpointResolver;
    private final ImageGenClient imageGenClient;
    private final CoverImageNormalizer imageNormalizer;
    private final CoverStorage coverStorage;
    private final CoverPromptBuilder promptBuilder;
    private final CoverTitleService coverTitleService;
    private final CoverTextOverlay coverTextOverlay;
    private final AiUsageService aiUsageService;
    private final PromptTemplateService promptTemplateService;

    @Override
    public List<NovelCoverVO> list(Long novelId) {
        requireNovel(novelId);
        return coverMapper.selectList(orderedQuery(novelId)).stream().map(NovelCoverVO::from).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NovelCoverVO generate(Long novelId, CoverGenerateRequest request) {
        NovelEntity novel = requireNovel(novelId);
        Long userId = UserContextHolder.requireUserId();

        List<NovelCoverEntity> existing = coverMapper.selectList(orderedQuery(novelId));
        if (existing.size() >= MAX_COVERS) {
            throw BizException.of(ErrorCode.COVER_LIMIT_REACHED);
        }

        // 先解析端点：没有可用的图像接口时直接给配置引导，不浪费一次下载
        ImageEndpoint endpoint = imageEndpointResolver.resolve(AiScene.COVER_IMAGE);

        String style = request == null ? null : request.getStyle();
        boolean withTitle = request != null && Boolean.TRUE.equals(request.getWithTitle());
        boolean withAuthor = request != null && Boolean.TRUE.equals(request.getWithAuthor());
        boolean withText = withTitle || withAuthor;
        // 文字渲染方式：AI_NATIVE = 模型直接画出书名/作者；默认 OVERLAY = 本地字体后期叠加
        boolean aiNative = withText && request != null && "AI_NATIVE".equalsIgnoreCase(request.getTextRenderMode());

        // 书名 / 作者名文案：用户填的优先 → 小说现有 → AI 起名兜底（AI 直出模式要先定文案才能注入提示词）
        String titleText = null;
        String authorText = null;
        if (withText) {
            String userTitle = request == null ? null : request.getTitleText();
            String userAuthor = request == null ? null : request.getAuthorText();
            String novelTitle = hasText(novel.getTitle()) ? novel.getTitle().trim() : null;
            String novelAuthor = hasText(novel.getPenName()) ? novel.getPenName().trim() : null;

            titleText = withTitle ? (hasText(userTitle) ? userTitle.trim() : novelTitle) : null;
            authorText = withAuthor ? (hasText(userAuthor) ? userAuthor.trim() : novelAuthor) : null;

            // 仍有缺口的（选了但没现成文案），尝试 AI 起名
            boolean needTitle = withTitle && !hasText(titleText);
            boolean needAuthor = withAuthor && !hasText(authorText);
            if (needTitle || needAuthor) {
                CoverTitleService.CoverTitle generated2 = coverTitleService.generate(novel, !needTitle, !needAuthor);
                if (generated2 != null) {
                    if (needTitle && hasText(generated2.title())) {
                        titleText = generated2.title().trim();
                    }
                    if (needAuthor && hasText(generated2.author())) {
                        authorText = generated2.author().trim();
                    }
                }
            }
        }

        // 正向提示词：优先用「提示词模板」里用户可编辑的封面模板（渲染变量），渲染为空则回退内置拼装
        String prompt = buildPrompt(novel, request == null ? null : request.getPrompt(), style,
                withText, aiNative, titleText, authorText);
        String negative = promptBuilder.negative(request == null ? null : request.getNegativePrompt(), aiNative);
        String size = request == null ? null : request.getSize();

        ImageGenResult generated;
        long startedAt = System.currentTimeMillis();
        try {
            generated = imageGenClient.generate(endpoint, new ImageGenRequest(prompt, negative, size));
        } catch (RuntimeException ex) {
            aiUsageService.recordImage(AiScene.COVER_IMAGE, endpoint, endpoint.model(),
                    (int) Math.min(System.currentTimeMillis() - startedAt, Integer.MAX_VALUE), ex.getMessage());
            throw BizException.of(ErrorCode.COVER_GENERATE_FAILED, ex.getMessage());
        }
        aiUsageService.recordImage(AiScene.COVER_IMAGE, endpoint, generated.model(),
                (int) Math.min(System.currentTimeMillis() - startedAt, Integer.MAX_VALUE), null);

        CoverImageNormalizer.NormalizedImage image = imageNormalizer.normalize(generated.data(), generated.mimeType());

        // 后期叠加模式：书名/作者用本地字体清晰渲染（AI 直出模式不叠加，文字由模型画在图里）
        if (withText && !aiNative && (hasText(titleText) || hasText(authorText))) {
            byte[] overlaid = coverTextOverlay.overlay(image.data(), titleText, authorText);
            image = new CoverImageNormalizer.NormalizedImage(overlaid, "jpg", "image/jpeg", image.dimension());
        }

        String relativePath = userId + "/" + novelId + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + image.extension();
        coverStorage.save(relativePath, image.data());

        NovelCoverEntity entity = new NovelCoverEntity();
        entity.setUserId(userId);
        entity.setNovelId(novelId);
        entity.setFilePath(relativePath);
        entity.setMimeType(image.mimeType());
        entity.setFileSize(image.size());
        entity.setWidth(image.width());
        entity.setHeight(image.height());
        entity.setPrompt(prompt);
        entity.setNegativePrompt(negative);
        entity.setStyle(style);
        entity.setHasText(withText ? 1 : 0);
        entity.setTitleText(titleText);
        entity.setAuthorText(authorText);
        entity.setTextMode(aiNative ? "AI_NATIVE" : "OVERLAY");
        entity.setProvider(endpoint.provider().name());
        entity.setModel(generated.model());
        entity.setSortNo(existing.size());
        entity.setIsPrimary(existing.isEmpty() ? 1 : 0);
        entity.setDeleted(0);
        entity.setVersion(0);
        coverMapper.insert(entity);

        log.info("[cover] generated novelId={} coverId={} provider={} model={} style={} hasText={} size={}x{} bytes={}",
                novelId, entity.getId(), entity.getProvider(), entity.getModel(), style, entity.getHasText(),
                entity.getWidth(), entity.getHeight(), entity.getFileSize());
        return NovelCoverVO.from(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NovelCoverVO setPrimary(Long coverId) {
        NovelCoverEntity entity = requireCover(coverId);
        coverMapper.update(null, Wrappers.<NovelCoverEntity>lambdaUpdate()
                .eq(NovelCoverEntity::getNovelId, entity.getNovelId())
                .eq(NovelCoverEntity::getIsPrimary, 1)
                .ne(NovelCoverEntity::getId, coverId)
                .set(NovelCoverEntity::getIsPrimary, 0));
        if (!Integer.valueOf(1).equals(entity.getIsPrimary())) {
            NovelCoverEntity patch = new NovelCoverEntity();
            patch.setId(coverId);
            patch.setIsPrimary(1);
            patch.setVersion(entity.getVersion());
            coverMapper.updateById(patch);
        }
        return NovelCoverVO.from(requireCover(coverId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long coverId) {
        NovelCoverEntity entity = requireCover(coverId);
        coverMapper.deleteById(coverId);
        coverStorage.delete(entity.getFilePath());
        renumber(entity.getNovelId());
        log.info("[cover] deleted coverId={} novelId={}", coverId, entity.getNovelId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAllForNovel(Long userId, Long novelId) {
        if (novelId == null) {
            return;
        }
        coverMapper.delete(Wrappers.<NovelCoverEntity>lambdaQuery()
                .eq(NovelCoverEntity::getNovelId, novelId));
        coverStorage.deleteNovelDirectory(userId, novelId);
        log.info("[cover] cleared all covers novelId={} userId={}", novelId, userId);
    }

    @Override
    public CoverImage loadImage(Long coverId) {
        NovelCoverEntity entity = requireCover(coverId);
        if (!coverStorage.exists(entity.getFilePath())) {
            throw BizException.of(ErrorCode.COVER_FILE_MISSING);
        }
        byte[] data;
        try (var stream = coverStorage.load(entity.getFilePath()).getInputStream()) {
            data = stream.readAllBytes();
        } catch (Exception ex) {
            log.warn("[cover] read image failed coverId={}: {}", coverId, ex.getMessage());
            throw BizException.of(ErrorCode.COVER_FILE_MISSING);
        }
        return new CoverImage(data, entity.getMimeType());
    }

    @Override
    public Map<Long, List<NovelCoverVO>> groupByNovel(List<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) {
            return Map.of();
        }
        List<NovelCoverEntity> entities = coverMapper.selectList(Wrappers.<NovelCoverEntity>lambdaQuery()
                .in(NovelCoverEntity::getNovelId, novelIds)
                .orderByDesc(NovelCoverEntity::getIsPrimary)
                .orderByAsc(NovelCoverEntity::getSortNo));
        Map<Long, List<NovelCoverVO>> grouped = new LinkedHashMap<>();
        for (NovelCoverEntity entity : entities) {
            grouped.computeIfAbsent(entity.getNovelId(), key -> new ArrayList<>())
                    .add(NovelCoverVO.from(entity));
        }
        return grouped;
    }

    /* ------------------------------------------------------------------ */

    /** 默认展示的主封面在前，其余按切换顺序。 */
    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NovelCoverEntity> orderedQuery(Long novelId) {
        return Wrappers.<NovelCoverEntity>lambdaQuery()
                .eq(NovelCoverEntity::getNovelId, novelId)
                .orderByDesc(NovelCoverEntity::getIsPrimary)
                .orderByAsc(NovelCoverEntity::getSortNo);
    }

    /**
     * 删除后重排：把 sort_no 收紧成 0..n-1。
     *
     * <p>必须按 sort_no 升序遍历——每张只会把序号往小改，而更小的序号要么已空、要么其持有者已经先一步
     * 让位，因此不会撞上 {@code (user_id, novel_id, sort_no)} 唯一索引。反过来若按「主封面优先」的顺序
     * 编号，主封面从中间序号被改到 0 时就必然和已有的 0 冲突。</p>
     */
    private void renumber(Long novelId) {
        List<NovelCoverEntity> remaining = coverMapper.selectList(Wrappers.<NovelCoverEntity>lambdaQuery()
                .eq(NovelCoverEntity::getNovelId, novelId)
                .orderByAsc(NovelCoverEntity::getSortNo));
        int index = 0;
        for (NovelCoverEntity item : remaining) {
            if (!Integer.valueOf(index).equals(item.getSortNo())) {
                NovelCoverEntity patch = new NovelCoverEntity();
                patch.setId(item.getId());
                patch.setSortNo(index);
                patch.setVersion(item.getVersion());
                coverMapper.updateById(patch);
            }
            index++;
        }
        // 主封面被删掉时用第一张补位：走条件更新而不是 updateById，
        // 因为上面可能刚改过这行的 sort_no，手里这份 version 已经过期
        boolean hasPrimary = remaining.stream().anyMatch(item -> Integer.valueOf(1).equals(item.getIsPrimary()));
        if (!hasPrimary && !remaining.isEmpty()) {
            coverMapper.update(null, Wrappers.<NovelCoverEntity>lambdaUpdate()
                    .eq(NovelCoverEntity::getId, remaining.get(0).getId())
                    .set(NovelCoverEntity::getIsPrimary, 1));
        }
    }

    private NovelEntity requireNovel(Long novelId) {
        NovelEntity novel = novelId == null ? null : novelMapper.selectById(novelId);
        if (novel == null) {
            // 跨用户访问与真正不存在返回同一结果，不泄露资源存在性
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        return novel;
    }

    private NovelCoverEntity requireCover(Long coverId) {
        NovelCoverEntity entity = coverId == null ? null : coverMapper.selectById(coverId);
        if (entity == null) {
            throw BizException.of(ErrorCode.COVER_NOT_FOUND);
        }
        return entity;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 组装封面正向提示词。
     *
     * <p>优先走「提示词模板」（设置 → 提示词模板 → 封面生成），把书名、题材、标签、风格、
     * 文字要求等作为变量渲染；用户改过模板就按用户的来，禁字/去水印等硬性约束由模板的
     * 输出格式契约锁定。渲染为空（理论上不会）时回退到内置拼装，保证生成流程不中断。</p>
     */
    private String buildPrompt(NovelEntity novel, String extra, String style,
                               boolean withText, boolean aiNative, String titleText, String authorText) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("novelTitle", hasText(novel.getTitle()) ? novel.getTitle().trim() : "未命名");
        vars.put("genre", hasText(novel.getGenre()) ? novel.getGenre().trim() : "");
        vars.put("tags", promptBuilder.tagsString(novel));
        vars.put("stylePrompt", promptBuilder.stylePrompt(style));
        vars.put("textRequirement", promptBuilder.textRequirement(withText, aiNative, titleText, authorText));
        vars.put("titleText", hasText(titleText) ? titleText : "");
        vars.put("authorText", hasText(authorText) ? authorText : "");

        String templated = promptTemplateService.system(AiScene.COVER_IMAGE, vars);
        if (hasText(templated)) {
            // 作者补充（画面描述）仍由用户在此次生成时额外提供，追加到模板之后
            if (hasText(extra)) {
                templated = templated + "\n【作者补充】" + extra.trim();
            }
            return templated.trim();
        }
        // 回退：模板未接入或渲染失败时用内置拼装（含作者补充）
        return promptBuilder.build(novel, extra, style, withText, aiNative, titleText, authorText);
    }
}
