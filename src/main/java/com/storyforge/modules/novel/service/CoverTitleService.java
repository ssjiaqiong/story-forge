package com.storyforge.modules.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.novel.entity.NovelEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 封面书名 / 作者名起名。
 *
 * <p>当用户勾选「生成标题 / 作者」但没自己填文案、而小说也没有现成书名或笔名时，
 * 由文本模型按小说题材与简介起一个。走 {@link AiScene#COVER_TITLE} 场景，用量计入仪表盘。</p>
 *
 * <p>起名是尽力而为的增强：模型失败或没配文本接口时回退到小说现有信息，不阻断封面生成。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverTitleService {

    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;

    /** 起名结果。 */
    public record CoverTitle(String title, String author) {
    }

    /**
     * 尝试起名。
     *
     * @param novel        小说
     * @param hasTitle     是否已经有可用书名（有则不再起书名）
     * @param hasAuthor    是否已经有可用作者名（有则不再起作者）
     * @return 起名结果；完全不需要起名时返回 {@code null}
     */
    public CoverTitle generate(NovelEntity novel, boolean hasTitle, boolean hasAuthor) {
        if (hasTitle && hasAuthor) {
            return null;
        }
        String prompt = buildPrompt(novel, hasTitle, hasAuthor);
        AiEndpoint endpoint;
        try {
            endpoint = endpointResolver.resolve(AiScene.COVER_TITLE);
        } catch (RuntimeException ex) {
            // 没配文本接口：起名是可选增强，静默跳过
            log.info("[cover-title] no text endpoint, skip: {}", ex.getMessage());
            return null;
        }

        ChatResult result;
        try {
            result = aiUsageService.chat(AiScene.COVER_TITLE, endpoint,
                    List.of(ChatMessage.user(prompt)));
        } catch (RuntimeException ex) {
            log.warn("[cover-title] chat failed: {}", ex.getMessage());
            return null;
        }

        try {
            String candidate = LenientJson.extractObject(result.content());
            if (candidate.isEmpty()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(LenientJson.repair(candidate));
            String title = str(node.get("title"));
            String author = str(node.get("author"));
            if (!hasText(title) && !hasText(author)) {
                return null;
            }
            return new CoverTitle(title, author);
        } catch (Exception ex) {
            log.warn("[cover-title] parse failed: {}", ex.getMessage());
            return null;
        }
    }

    private String buildPrompt(NovelEntity novel, boolean hasTitle, boolean hasAuthor) {
        StringBuilder builder = new StringBuilder(256);
        builder.append("你是中文网络小说的书名策划。根据下面的作品信息，")
                .append(hasTitle ? "起一个作者笔名" : "起一个书名和作者笔名")
                .append("。要求：朗朗上口、贴合题材、避免低俗与既有爆款雷同，控制在 2~8 个字。\n\n");
        builder.append("题材：").append(hasText(novel.getGenre()) ? novel.getGenre().trim() : "未知").append('\n');
        if (hasText(novel.getIntro())) {
            String intro = novel.getIntro().trim().replaceAll("\\s+", " ");
            builder.append("简介：").append(intro.length() > 200 ? intro.substring(0, 200) + "…" : intro).append('\n');
        }
        builder.append("\n只输出 JSON，不要任何解释，字段：");
        if (hasTitle) {
            builder.append("{\"author\":\"笔名\"}");
        } else {
            builder.append("{\"title\":\"书名\",\"author\":\"笔名\"}");
        }
        builder.append("。值内不要用英文双引号或换行。");
        return builder.toString();
    }

    private String str(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText("").trim();
        return hasText(text) ? text : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
