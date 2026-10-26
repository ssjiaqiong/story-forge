package com.storyforge.modules.ai.service;

import com.storyforge.modules.ai.dto.PromptTemplateVO;
import com.storyforge.modules.ai.scene.AiScene;

import java.util.List;
import java.util.Map;

/**
 * 提示词模板：用户级覆盖 + 系统锁定的输出格式。
 */
public interface PromptTemplateService {

    List<PromptTemplateVO.Item> list();

    PromptTemplateVO.Detail detail(String scene);

    PromptTemplateVO.Detail save(String scene, String content);

    void reset(String scene);

    PromptTemplateVO.Preview preview(String scene);

    /**
     * 生成时取最终 system prompt：用户模板（渲染变量）+ 系统锁定的输出格式段。
     *
     * <p>任何异常都回退到内置默认模板，保证生成流程不中断。</p>
     */
    String system(AiScene scene, Map<String, String> vars);
}
