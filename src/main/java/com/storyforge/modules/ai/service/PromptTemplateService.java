package com.storyforge.modules.ai.service;

import com.storyforge.modules.ai.dto.PromptExportPackage;
import com.storyforge.modules.ai.dto.PromptImportResult;
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
     * 导出提示词模板包。
     *
     * @param onlyOverridden 只导出「用户改过」的场景（推荐）；false 则把全部可编辑场景都导出
     *                       （未改过的用系统默认内容填充）
     */
    PromptExportPackage exportPackage(boolean onlyOverridden);

    /**
     * 导入提示词模板包。
     *
     * @param mode {@code MERGE}（默认，覆盖同名） / {@code SKIP_EXISTING}（只填空缺） /
     *             {@code REPLACE}（先清空当前账号全部自定义，再按包写入）
     */
    PromptImportResult importPackage(PromptExportPackage pkg, String mode);

    /**
     * 生成时取最终 system prompt：用户模板（渲染变量）+ 系统锁定的输出格式段。
     *
     * <p>任何异常都回退到内置默认模板，保证生成流程不中断。</p>
     */
    String system(AiScene scene, Map<String, String> vars);

    /**
     * 当前生效模板里实际引用的变量名。
     *
     * <p>调用方据此把「已经写进 system 的上下文块」从 user 消息里剔除，
     * 避免同一份设定注入两次、白占 token。</p>
     *
     * <p>默认模板不引用上下文变量，因此返回空集合，行为与改造前完全一致。</p>
     */
    java.util.Set<String> usedVariables(AiScene scene);
}
