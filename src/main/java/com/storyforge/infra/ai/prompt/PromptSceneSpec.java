package com.storyforge.infra.ai.prompt;

import java.util.List;

/**
 * 一个 AI 场景的提示词规格。
 *
 * <p>完整提示词 = {@code discipline}（系统纪律，只读）+ {@code defaultContent}（用户可改的指令段）
 * + {@code outputContract}（输出格式契约，只读）。后两段都由这里提供，
 * 用户改的只是中间那段，输出格式永远由系统追加，代码解析才稳定。</p>
 *
 * @param scene          场景编码（AiScene.name()）
 * @param displayName    中文名
 * @param group          分组，用于设置页左侧导航
 * @param editable       是否已接入模板（false 时页面显示"暂未开放编辑"）
 * @param defaultContent 默认指令段，支持 {{变量}}
 * @param outputContract 输出格式契约（只读）
 * @param variables      可用变量
 * @param note           说明 / 未开放原因
 */
public record PromptSceneSpec(
        String scene,
        String displayName,
        String group,
        boolean editable,
        String defaultContent,
        String outputContract,
        List<PromptVariable> variables,
        String note) {

    /** 一个可用变量：名称、说明、示例值 */
    public record PromptVariable(String name, String label, String sample) {
    }
}
