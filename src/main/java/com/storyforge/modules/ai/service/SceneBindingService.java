package com.storyforge.modules.ai.service;

import com.storyforge.modules.ai.dto.SceneBindingSaveRequest;
import com.storyforge.modules.ai.dto.SceneBindingVO;

import java.util.List;

/**
 * 场景级模型绑定服务（按用户隔离）。
 */
public interface SceneBindingService {

    /** 全部场景及其绑定状态（未绑定的返回空字段）。 */
    List<SceneBindingVO> list();

    /** 批量保存；{@code aiConfigId} 为空表示清除该场景绑定。 */
    void save(SceneBindingSaveRequest request);
}
