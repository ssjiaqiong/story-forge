package com.storyforge.modules.inspiration.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.inspiration.dto.SkillCreateRequest;
import com.storyforge.modules.inspiration.dto.SkillUpdateRequest;
import com.storyforge.modules.inspiration.dto.SkillVO;
import com.storyforge.modules.inspiration.entity.InspirationSkillEntity;
import com.storyforge.modules.inspiration.mapper.InspirationSkillMapper;
import com.storyforge.modules.inspiration.skill.InspirationSkillDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 灵感技能服务实现。
 *
 * <p>「更新只对本用户有效」的实现方式：内置技能不共享单行，而是在用户首次进入灵感模式时
 * 复制一份到该用户名下（{@code t_inspiration_skill.user_id}），此后所有修改都落在自己的副本上；
 * 其他用户的副本互不影响，恢复默认也只是把本行重置为平台默认文案。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspirationSkillServiceImpl implements InspirationSkillService {

    private static final int MAX_CUSTOM_SKILLS = 20;

    private final InspirationSkillMapper skillMapper;

    @Override
    public List<SkillVO> list() {
        ensureBuiltin(UserContextHolder.requireUserId());
        return skillMapper.selectList(Wrappers.<InspirationSkillEntity>lambdaQuery()
                        .orderByDesc(InspirationSkillEntity::getBuiltin)
                        .orderByAsc(InspirationSkillEntity::getSortNo)
                        .orderByAsc(InspirationSkillEntity::getCreatedAt))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillVO create(SkillCreateRequest request) {
        Long userId = UserContextHolder.requireUserId();
        ensureBuiltin(userId);

        long total = skillMapper.selectCount(Wrappers.<InspirationSkillEntity>lambdaQuery());
        if (total >= MAX_CUSTOM_SKILLS + 1) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "技能数量已达上限（" + MAX_CUSTOM_SKILLS + " 个自定义技能）");
        }
        String name = request.getName().trim();
        ensureNameUnique(name, null);

        InspirationSkillEntity entity = new InspirationSkillEntity();
        entity.setUserId(userId);
        entity.setCode("CUSTOM_" + System.currentTimeMillis());
        entity.setName(name);
        entity.setDescription(blankToNull(request.getDescription()));
        entity.setSystemPrompt(request.getSystemPrompt().trim());
        entity.setBuiltin(false);
        entity.setEnabled(true);
        entity.setSortNo(100 + (int) total);
        entity.setDeleted(0);
        entity.setVersion(0);
        skillMapper.insert(entity);
        log.info("[inspiration] skill created id={} userId={} name={}", entity.getId(), userId, name);
        return toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillVO update(Long id, SkillUpdateRequest request) {
        InspirationSkillEntity entity = requireSkill(id);

        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "技能名称不能为空");
            }
            ensureNameUnique(name, id);
            entity.setName(name);
        }
        if (request.getDescription() != null) {
            entity.setDescription(blankToNull(request.getDescription()));
        }
        if (request.getSystemPrompt() != null) {
            String prompt = request.getSystemPrompt().trim();
            if (prompt.isEmpty()) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "系统提示词不能为空");
            }
            entity.setSystemPrompt(prompt);
        }
        skillMapper.updateById(entity);
        log.info("[inspiration] skill updated id={} userId={}", id, entity.getUserId());
        return toVO(requireSkill(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillVO reset(Long id) {
        InspirationSkillEntity entity = requireSkill(id);
        if (!Boolean.TRUE.equals(entity.getBuiltin())
                || !InspirationSkillDefaults.NOVEL_CREATE_CODE.equals(entity.getCode())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "只有内置的「创建小说」技能支持恢复默认");
        }
        entity.setName(InspirationSkillDefaults.NOVEL_CREATE_NAME);
        entity.setDescription(InspirationSkillDefaults.NOVEL_CREATE_DESCRIPTION);
        entity.setSystemPrompt(InspirationSkillDefaults.NOVEL_CREATE_PROMPT);
        skillMapper.updateById(entity);
        log.info("[inspiration] skill reset to default id={} userId={}", id, entity.getUserId());
        return toVO(requireSkill(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        InspirationSkillEntity entity = requireSkill(id);
        if (Boolean.TRUE.equals(entity.getBuiltin())) {
            throw BizException.of(ErrorCode.SKILL_BUILTIN_READONLY);
        }
        skillMapper.deleteById(id);
        log.info("[inspiration] skill deleted id={} userId={}", id, entity.getUserId());
    }

    @Override
    public InspirationSkillEntity ensureBuiltin(Long userId) {
        InspirationSkillEntity existing = findByCode(userId, InspirationSkillDefaults.NOVEL_CREATE_CODE);
        if (existing != null) {
            upgradeDefaultPrompt(existing);
            return existing;
        }
        InspirationSkillEntity entity = new InspirationSkillEntity();
        entity.setUserId(userId);
        entity.setCode(InspirationSkillDefaults.NOVEL_CREATE_CODE);
        entity.setName(InspirationSkillDefaults.NOVEL_CREATE_NAME);
        entity.setDescription(InspirationSkillDefaults.NOVEL_CREATE_DESCRIPTION);
        entity.setSystemPrompt(InspirationSkillDefaults.NOVEL_CREATE_PROMPT);
        entity.setBuiltin(true);
        entity.setEnabled(true);
        entity.setSortNo(0);
        entity.setDeleted(0);
        entity.setVersion(0);
        try {
            skillMapper.insert(entity);
            log.info("[inspiration] builtin skill seeded userId={} id={}", userId, entity.getId());
            return entity;
        } catch (DuplicateKeyException ex) {
            // 并发首次进入：另一个请求已插入，直接复用
            InspirationSkillEntity found = findByCode(userId, InspirationSkillDefaults.NOVEL_CREATE_CODE);
            if (found != null) {
                return found;
            }
            throw ex;
        }
    }

    @Override
    public InspirationSkillEntity resolveForUser(Long userId, String code) {
        InspirationSkillEntity entity = findByCode(userId, code);
        if (entity != null) {
            upgradeDefaultPrompt(entity);
        }
        if (entity != null && Boolean.TRUE.equals(entity.getEnabled())) {
            return entity;
        }
        return ensureBuiltin(userId);
    }

    /**
     * 平台升级内置提示词时，自动同步「仍保持旧版默认内容」的用户副本；
     * 用户自己改过的版本不动（仍可用「恢复默认」手动升级）。
     */
    private void upgradeDefaultPrompt(InspirationSkillEntity entity) {
        if (!Boolean.TRUE.equals(entity.getBuiltin())
                || !InspirationSkillDefaults.NOVEL_CREATE_CODE.equals(entity.getCode())
                || !InspirationSkillDefaults.isLegacyNovelCreatePrompt(entity.getSystemPrompt())) {
            return;
        }
        entity.setName(InspirationSkillDefaults.NOVEL_CREATE_NAME);
        entity.setDescription(InspirationSkillDefaults.NOVEL_CREATE_DESCRIPTION);
        entity.setSystemPrompt(InspirationSkillDefaults.NOVEL_CREATE_PROMPT);
        skillMapper.updateById(entity);
        log.info("[inspiration] builtin prompt upgraded userId={} id={}", entity.getUserId(), entity.getId());
    }

    /* ------------------------------------------------------------------ */

    @Override
    public InspirationSkillEntity requireByCode(Long userId, String code) {
        InspirationSkillEntity entity = findByCode(userId, code);
        if (entity == null || Boolean.FALSE.equals(entity.getEnabled())) {
            throw BizException.of(ErrorCode.SKILL_NOT_FOUND);
        }
        return entity;
    }

    private InspirationSkillEntity findByCode(Long userId, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return skillMapper.selectOne(Wrappers.<InspirationSkillEntity>lambdaQuery()
                .eq(InspirationSkillEntity::getUserId, userId)
                .eq(InspirationSkillEntity::getCode, code.trim())
                .last("limit 1"));
    }

    private InspirationSkillEntity requireSkill(Long id) {
        InspirationSkillEntity entity = skillMapper.selectById(id);
        if (entity == null) {
            throw BizException.of(ErrorCode.SKILL_NOT_FOUND);
        }
        return entity;
    }

    private void ensureNameUnique(String name, Long excludeId) {
        var wrapper = Wrappers.<InspirationSkillEntity>lambdaQuery()
                .eq(InspirationSkillEntity::getName, name);
        if (excludeId != null) {
            wrapper.ne(InspirationSkillEntity::getId, excludeId);
        }
        if (skillMapper.selectCount(wrapper) > 0L) {
            throw BizException.of(ErrorCode.SKILL_NAME_EXISTS);
        }
    }

    private SkillVO toVO(InspirationSkillEntity entity) {
        SkillVO vo = new SkillVO();
        vo.setId(entity.getId());
        vo.setCode(entity.getCode());
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setSystemPrompt(entity.getSystemPrompt());
        vo.setBuiltin(entity.getBuiltin());
        vo.setEnabled(entity.getEnabled());
        vo.setSortNo(entity.getSortNo());
        return vo;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
