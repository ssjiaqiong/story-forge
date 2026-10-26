package com.storyforge.modules.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.prompt.PromptSceneRegistry;
import com.storyforge.infra.ai.prompt.PromptSceneSpec;
import com.storyforge.infra.ai.prompt.PromptTemplateRenderer;
import com.storyforge.modules.ai.dto.PromptTemplateVO;
import com.storyforge.modules.ai.entity.PromptTemplateEntity;
import com.storyforge.modules.ai.mapper.PromptTemplateMapper;
import com.storyforge.modules.ai.scene.AiScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词模板服务实现。
 *
 * <p>关键约定：数据库里只存「用户可编辑的指令段」，输出格式契约始终由
 * {@link PromptSceneRegistry} 提供并追加，所以用户改不到解析层。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateServiceImpl implements PromptTemplateService {

    private static final int MAX_CONTENT = 8000;

    private final PromptTemplateMapper templateMapper;
    private final PromptSceneRegistry registry;
    private final PromptTemplateRenderer renderer;

    @Override
    public List<PromptTemplateVO.Item> list() {
        Map<String, PromptTemplateEntity> overrides = overrides();
        List<PromptTemplateVO.Item> items = new ArrayList<>();
        for (PromptSceneSpec spec : registry.list()) {
            PromptTemplateEntity entity = overrides.get(spec.scene());
            items.add(new PromptTemplateVO.Item(
                    spec.scene(),
                    spec.displayName(),
                    spec.group(),
                    spec.editable(),
                    entity != null,
                    spec.variables().size(),
                    entity == null ? null : entity.getUpdatedAt()));
        }
        return items;
    }

    @Override
    public PromptTemplateVO.Detail detail(String scene) {
        PromptSceneSpec spec = requireScene(scene);
        PromptTemplateEntity entity = override(scene);
        return new PromptTemplateVO.Detail(
                spec.scene(),
                spec.displayName(),
                spec.group(),
                spec.editable(),
                entity != null,
                entity == null ? spec.defaultContent() : entity.getContent(),
                spec.defaultContent(),
                spec.outputContract(),
                variables(spec),
                spec.note(),
                entity == null ? null : entity.getUpdatedAt());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplateVO.Detail save(String scene, String content) {
        PromptSceneSpec spec = requireEditable(scene);
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw BizException.of(ErrorCode.PROMPT_CONTENT_INVALID, "模板内容不能为空，想恢复原文请点「恢复默认」");
        }
        if (text.length() > MAX_CONTENT) {
            throw BizException.of(ErrorCode.PROMPT_CONTENT_INVALID,
                    "模板最长 " + MAX_CONTENT + " 个字符，当前 " + text.length() + " 个");
        }
        List<String> unknown = renderer.unknownVariables(text, spec);
        if (!unknown.isEmpty()) {
            throw BizException.of(ErrorCode.PROMPT_VARIABLE_INVALID,
                    "模板里有不认识的变量：" + String.join("、", unknown) + "（可用变量见右侧说明）");
        }

        PromptTemplateEntity entity = override(scene);
        if (entity == null) {
            entity = new PromptTemplateEntity();
            entity.setUserId(UserContextHolder.requireUserId());
            entity.setScene(spec.scene());
            entity.setContent(text);
            entity.setRevision(1);
            entity.setDeleted(0);
            entity.setVersion(0);
            templateMapper.insert(entity);
        } else {
            entity.setContent(text);
            entity.setRevision((entity.getRevision() == null ? 0 : entity.getRevision()) + 1);
            templateMapper.updateById(entity);
        }
        log.info("[prompt] saved scene={} length={} revision={}", scene, text.length(), entity.getRevision());
        return detail(scene);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reset(String scene) {
        PromptSceneSpec spec = requireEditable(scene);
        templateMapper.delete(Wrappers.<PromptTemplateEntity>lambdaQuery()
                .eq(PromptTemplateEntity::getScene, spec.scene()));
        log.info("[prompt] reset scene={} userId={}", scene, UserContextHolder.requireUserId());
    }

    @Override
    public PromptTemplateVO.Preview preview(String scene) {
        PromptSceneSpec spec = requireScene(scene);
        PromptTemplateEntity entity = override(scene);
        String content = entity == null ? spec.defaultContent() : entity.getContent();
        Map<String, String> samples = new LinkedHashMap<>();
        spec.variables().forEach(item -> samples.put(item.name(), item.sample()));
        return new PromptTemplateVO.Preview(
                spec.scene(),
                renderer.renderContent(content, samples),
                spec.outputContract(),
                renderer.compose(spec, content, samples));
    }

    @Override
    public String system(AiScene scene, Map<String, String> vars) {
        PromptSceneSpec spec = registry.get(scene == null ? null : scene.name());
        if (spec == null || !spec.editable()) {
            // 未接入模板的场景：调用方自己传默认提示词，这里返回空串表示"不要覆盖"
            return "";
        }
        try {
            PromptTemplateEntity entity = override(spec.scene());
            String content = entity == null ? spec.defaultContent() : entity.getContent();
            return renderer.compose(spec, content, vars);
        } catch (Exception ex) {
            // 模板坏掉不能拖垮生成：回退默认并记日志
            log.warn("[prompt] render failed scene={}, fallback to default: {}", spec.scene(), ex.getMessage());
            return renderer.compose(spec, spec.defaultContent(), vars);
        }
    }

    /* ------------------------------------------------------------------ */

    private PromptSceneSpec requireScene(String scene) {
        AiScene target = AiScene.of(scene);
        if (target == null) {
            throw BizException.of(ErrorCode.AI_SCENE_INVALID, "场景「" + scene + "」不存在");
        }
        PromptSceneSpec spec = registry.get(target.name());
        if (spec == null) {
            // 场景真实存在，但提示词不归这里管：说清去哪儿改，别让用户以为要等更新
            String hint = target == AiScene.INSPIRATION_CHAT
                    ? "的提示词请到「灵感」页的技能管理中修改"
                    : target == AiScene.DECONSTRUCT
                    ? "使用系统内置提示词，暂不支持自定义"
                    : "暂未接入提示词模板";
            throw BizException.of(ErrorCode.PROMPT_SCENE_NOT_EDITABLE,
                    "「" + target.getLabel() + "」" + hint);
        }
        return spec;
    }

    private PromptSceneSpec requireEditable(String scene) {
        PromptSceneSpec spec = requireScene(scene);
        if (!spec.editable()) {
            throw BizException.of(ErrorCode.PROMPT_SCENE_NOT_EDITABLE,
                    "「" + spec.displayName() + "」暂未开放模板编辑，仍使用系统内置提示词");
        }
        return spec;
    }

    private PromptTemplateEntity override(String scene) {
        return templateMapper.selectOne(Wrappers.<PromptTemplateEntity>lambdaQuery()
                .eq(PromptTemplateEntity::getScene, scene)
                .last("LIMIT 1"));
    }

    private Map<String, PromptTemplateEntity> overrides() {
        Map<String, PromptTemplateEntity> map = new LinkedHashMap<>();
        for (PromptTemplateEntity entity : templateMapper.selectList(
                Wrappers.<PromptTemplateEntity>lambdaQuery().orderByAsc(PromptTemplateEntity::getScene))) {
            map.put(entity.getScene(), entity);
        }
        return map;
    }

    private List<PromptTemplateVO.Variable> variables(PromptSceneSpec spec) {
        return spec.variables().stream()
                .map(item -> new PromptTemplateVO.Variable(item.name(), item.label(), item.sample()))
                .toList();
    }
}
