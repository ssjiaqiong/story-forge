package com.storyforge.modules.search.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.search.dto.SearchSceneSaveRequest;
import com.storyforge.modules.search.dto.SearchSceneVO;
import com.storyforge.modules.search.entity.SearchConfigEntity;
import com.storyforge.modules.search.entity.SearchSceneEntity;
import com.storyforge.modules.search.mapper.SearchConfigMapper;
import com.storyforge.modules.search.mapper.SearchSceneMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索场景绑定实现。
 *
 * <p>只有「事件生成 / 大纲生成 / 正文生成」三个场景支持联网检索；
 * 其余 AI 场景属于内部结构化任务，不需要外部资料。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchSceneServiceImpl implements SearchSceneService {

    /** 允许联网检索的场景 */
    private static final List<AiScene> SCENES = List.of(
            AiScene.EVENT_SUPPLY,
            AiScene.OUTLINE_GEN,
            AiScene.BODY_GEN);

    private final SearchSceneMapper sceneMapper;
    private final SearchConfigMapper configMapper;
    private final SearchConfigService configService;

    @Override
    public List<SearchSceneVO> list() {
        configService.ensureDefaults();
        Map<String, SearchSceneEntity> bindings = new LinkedHashMap<>();
        for (SearchSceneEntity entity : sceneMapper.selectList(Wrappers.<SearchSceneEntity>lambdaQuery())) {
            bindings.put(entity.getSceneCode(), entity);
        }
        Map<Long, String> names = new LinkedHashMap<>();
        String defaultName = null;
        for (SearchConfigEntity config : configMapper.selectList(Wrappers.<SearchConfigEntity>lambdaQuery())) {
            names.put(config.getId(), config.getName());
            if (Boolean.TRUE.equals(config.getIsDefault()) && Boolean.TRUE.equals(config.getEnabled())) {
                defaultName = config.getName();
            }
        }

        List<SearchSceneVO> result = new ArrayList<>();
        for (AiScene scene : SCENES) {
            SearchSceneEntity binding = bindings.get(scene.name());
            Long configId = binding == null ? null : binding.getConfigId();
            String configName = configId == null ? null : names.get(configId);
            result.add(new SearchSceneVO(
                    scene.name(),
                    scene.getLabel(),
                    scene.getGroup(),
                    binding == null || binding.getEnabled() == null ? Boolean.TRUE : binding.getEnabled(),
                    configId,
                    configName,
                    binding == null ? null : binding.getTopK(),
                    configName != null ? configName : defaultName));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(SearchSceneSaveRequest request) {
        Long userId = UserContextHolder.requireUserId();
        Map<String, SearchSceneEntity> existing = new LinkedHashMap<>();
        for (SearchSceneEntity entity : sceneMapper.selectList(Wrappers.<SearchSceneEntity>lambdaQuery())) {
            existing.put(entity.getSceneCode(), entity);
        }

        for (SearchSceneSaveRequest.Item item : request.getItems()) {
            AiScene scene = AiScene.of(item.getCode());
            if (scene == null || !SCENES.contains(scene)) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "不支持联网检索的场景：" + item.getCode());
            }
            boolean enabled = item.getEnabled() == null || item.getEnabled();
            if (item.getConfigId() != null) {
                SearchConfigEntity config = configMapper.selectById(item.getConfigId());
                if (config == null) {
                    throw BizException.of(ErrorCode.SEARCH_CONFIG_NOT_FOUND);
                }
                if (!Boolean.TRUE.equals(config.getEnabled())) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "搜索源「" + config.getName() + "」已停用，无法绑定");
                }
            }

            SearchSceneEntity current = existing.get(scene.name());
            boolean pureDefault = enabled && item.getConfigId() == null && item.getTopK() == null;
            if (pureDefault) {
                // 全部继承默认，删除绑定行保持数据干净
                if (current != null) {
                    sceneMapper.deleteById(current.getId());
                }
                continue;
            }

            if (current == null) {
                SearchSceneEntity entity = new SearchSceneEntity();
                entity.setUserId(userId);
                entity.setSceneCode(scene.name());
                entity.setConfigId(item.getConfigId());
                entity.setEnabled(enabled);
                entity.setTopK(item.getTopK());
                entity.setDeleted(0);
                entity.setVersion(0);
                sceneMapper.insert(entity);
            } else {
                sceneMapper.update(null, Wrappers.<SearchSceneEntity>lambdaUpdate()
                        .eq(SearchSceneEntity::getId, current.getId())
                        .set(SearchSceneEntity::getConfigId, item.getConfigId())
                        .set(SearchSceneEntity::getEnabled, enabled)
                        .set(SearchSceneEntity::getTopK, item.getTopK()));
            }
        }
        log.info("[search] scene bindings saved userId={} items={}", userId, request.getItems().size());
    }
}
