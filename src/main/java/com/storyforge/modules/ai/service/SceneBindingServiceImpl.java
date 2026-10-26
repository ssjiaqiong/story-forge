package com.storyforge.modules.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.ai.dto.SceneBindingSaveRequest;
import com.storyforge.modules.ai.dto.SceneBindingVO;
import com.storyforge.modules.ai.entity.AiConfigEntity;
import com.storyforge.modules.ai.entity.SceneBindingEntity;
import com.storyforge.modules.ai.mapper.AiConfigMapper;
import com.storyforge.modules.ai.mapper.SceneBindingMapper;
import com.storyforge.modules.ai.scene.AiConfigKind;
import com.storyforge.modules.ai.scene.AiScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 场景级模型绑定实现。
 *
 * <p>绑定只是「覆盖」：未绑定或字段为空时，回落到该用户默认 AI 配置的对应参数。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneBindingServiceImpl implements SceneBindingService {

    private final SceneBindingMapper sceneBindingMapper;
    private final AiConfigMapper aiConfigMapper;

    @Override
    public List<SceneBindingVO> list() {
        Map<String, SceneBindingEntity> bindings = sceneBindingMapper
                .selectList(Wrappers.<SceneBindingEntity>lambdaQuery())
                .stream()
                .collect(Collectors.toMap(SceneBindingEntity::getSceneCode, Function.identity(), (a, b) -> a));

        Map<Long, String> configNames = aiConfigMapper.selectList(Wrappers.<AiConfigEntity>lambdaQuery())
                .stream()
                .collect(Collectors.toMap(AiConfigEntity::getId, AiConfigEntity::getName, (a, b) -> a));

        List<SceneBindingVO> result = new ArrayList<>();
        for (AiScene scene : AiScene.ordered()) {
            SceneBindingVO vo = new SceneBindingVO();
            vo.setCode(scene.name());
            vo.setLabel(scene.getLabel());
            vo.setGroup(scene.getGroup());
            vo.setKind(scene.getKind().name());

            SceneBindingEntity binding = bindings.get(scene.name());
            if (binding != null) {
                vo.setAiConfigId(binding.getAiConfigId());
                vo.setAiConfigName(configNames.get(binding.getAiConfigId()));
                vo.setModel(binding.getModel());
                vo.setTemperature(binding.getTemperature());
                vo.setMaxTokens(binding.getMaxTokens());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(SceneBindingSaveRequest request) {
        Long userId = UserContextHolder.requireUserId();

        Map<String, SceneBindingEntity> existing = new HashMap<>();
        for (SceneBindingEntity binding : sceneBindingMapper.selectList(Wrappers.<SceneBindingEntity>lambdaQuery())) {
            existing.put(binding.getSceneCode(), binding);
        }

        for (SceneBindingSaveRequest.Item item : request.getItems()) {
            AiScene scene = AiScene.of(item.getCode());
            if (scene == null) {
                throw BizException.of(ErrorCode.AI_SCENE_INVALID, "AI 场景编码非法：" + item.getCode());
            }

            SceneBindingEntity current = existing.get(scene.name());

            // 清除绑定：回落到默认端点
            if (item.getAiConfigId() == null) {
                if (current != null) {
                    sceneBindingMapper.deleteById(current.getId());
                    log.info("[scene-binding] cleared scene={} userId={}", scene.name(), userId);
                }
                continue;
            }

            AiConfigEntity config = aiConfigMapper.selectById(item.getAiConfigId());
            if (config == null) {
                throw BizException.of(ErrorCode.AI_CONFIG_NOT_FOUND);
            }
            if (!Boolean.TRUE.equals(config.getEnabled())) {
                throw BizException.of(ErrorCode.AI_CONFIG_DISABLED,
                        "接口「" + config.getName() + "」已停用，无法绑定");
            }
            // 文本场景只能绑文本接口，封面场景只能绑图像接口：绑错类型运行时必然报错，就地挡住
            AiConfigKind configKind = AiConfigKind.of(config.getKind());
            if (scene.getKind() != configKind) {
                throw BizException.of(ErrorCode.AI_CONFIG_KIND_MISMATCH,
                        "场景「" + scene.getLabel() + "」需要" + scene.getKind().getLabel()
                                + "，而「" + config.getName() + "」是"
                                + (configKind == null ? "未知类型" : configKind.getLabel()));
            }

            if (current != null) {
                // 用 UpdateWrapper 显式 set，才能把温度 / max_tokens 清空（回落到端点默认值）
                sceneBindingMapper.update(null, Wrappers.<SceneBindingEntity>lambdaUpdate()
                        .eq(SceneBindingEntity::getId, current.getId())
                        .eq(SceneBindingEntity::getVersion, current.getVersion())
                        .set(SceneBindingEntity::getAiConfigId, item.getAiConfigId())
                        .set(SceneBindingEntity::getModel, blankToNull(item.getModel()))
                        .set(SceneBindingEntity::getTemperature, item.getTemperature())
                        .set(SceneBindingEntity::getMaxTokens, item.getMaxTokens())
                        .set(SceneBindingEntity::getVersion, current.getVersion() + 1));
            } else {
                SceneBindingEntity entity = new SceneBindingEntity();
                entity.setUserId(userId);
                entity.setSceneCode(scene.name());
                entity.setAiConfigId(item.getAiConfigId());
                entity.setModel(blankToNull(item.getModel()));
                entity.setTemperature(item.getTemperature());
                entity.setMaxTokens(item.getMaxTokens());
                entity.setDeleted(0);
                entity.setVersion(0);
                sceneBindingMapper.insert(entity);
                existing.put(scene.name(), entity);
            }
        }
        log.info("[scene-binding] saved {} scenes userId={}", request.getItems().size(), userId);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
