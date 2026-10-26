package com.storyforge.modules.inspiration.service;

import com.storyforge.modules.inspiration.dto.SkillCreateRequest;
import com.storyforge.modules.inspiration.dto.SkillUpdateRequest;
import com.storyforge.modules.inspiration.dto.SkillVO;
import com.storyforge.modules.inspiration.entity.InspirationSkillEntity;

import java.util.List;

/**
 * 灵感技能服务（按用户隔离，修改只影响本人）。
 */
public interface InspirationSkillService {

    /** 当前用户可用技能；首次调用会把内置「创建小说」技能复制到该用户名下。 */
    List<SkillVO> list();

    SkillVO create(SkillCreateRequest request);

    SkillVO update(Long id, SkillUpdateRequest request);

    /** 内置技能恢复为平台默认提示词。 */
    SkillVO reset(Long id);

    void delete(Long id);

    /** 确保内置技能存在并返回（幂等）。 */
    InspirationSkillEntity ensureBuiltin(Long userId);

    /** 按编码取可用技能，找不到时回落到内置技能。 */
    InspirationSkillEntity resolveForUser(Long userId, String code);

    /** 按编码取本人技能，不存在或已停用直接抛 40012。 */
    InspirationSkillEntity requireByCode(Long userId, String code);
}
