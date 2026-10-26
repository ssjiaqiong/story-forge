package com.storyforge.infra.ai.prompt;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词渲染器：把用户模板里的 {@code {{变量}}} 替换成实际值，再追加系统锁定的输出格式段。
 *
 * <p>渲染失败（变量名非法、模板为空）时由调用方兜底用默认模板，本类不抛业务异常。</p>
 */
@Component
public class PromptTemplateRenderer {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    /** 找出模板里用到的变量名（去重保序） */
    public Set<String> variablesIn(String content) {
        Set<String> names = new LinkedHashSet<>();
        if (content == null) {
            return names;
        }
        Matcher matcher = VARIABLE.matcher(content);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** 校验：返回不在白名单里的变量名，空表示通过 */
    public List<String> unknownVariables(String content, PromptSceneSpec spec) {
        Set<String> allowed = new LinkedHashSet<>();
        spec.variables().forEach(item -> allowed.add(item.name()));
        List<String> unknown = new ArrayList<>();
        for (String name : variablesIn(content)) {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    /** 只渲染用户模板正文（不含输出格式段），用于预览里的"你的模板"一块 */
    public String renderContent(String content, Map<String, String> vars) {
        if (content == null) {
            return "";
        }
        StringBuffer buffer = new StringBuffer();
        Matcher matcher = VARIABLE.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = vars == null ? null : vars.get(name);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(buffer);
        return buffer.toString().trim();
    }

    /**
     * 拼出最终 system prompt：用户模板（渲染后）+ 锁定的输出格式契约。
     *
     * @param content 用户当前生效的模板正文（DB 覆盖或默认）
     */
    public String compose(PromptSceneSpec spec, String content, Map<String, String> vars) {
        String body = renderContent(content, vars);
        if (body.isBlank()) {
            body = renderContent(spec.defaultContent(), vars);
        }
        return body + "\n\n" + spec.outputContract();
    }
}
