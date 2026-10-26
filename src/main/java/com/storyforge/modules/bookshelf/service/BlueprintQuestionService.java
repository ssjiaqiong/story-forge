package com.storyforge.modules.bookshelf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.ai.service.PromptTemplateService;
import com.storyforge.modules.bookshelf.dto.BlueprintQuestion;
import com.storyforge.modules.bookshelf.dto.BlueprintQuestionsVO;
import com.storyforge.modules.bookshelf.dto.BlueprintRequest;
import com.storyforge.modules.bookshelf.dto.ConstraintFormRequest;
import com.storyforge.modules.bookshelf.dto.NovelConstraintField;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 建书前的「AI 追问」与「创作约束」组装。
 *
 * <p>解决的问题：作者在表单里只填了创意、题材、风格这些粗粒度字段，AI 就得靠猜去设计世界观与人物，
 * 猜错了作者只能整段重生成。这里先让 AI 判断「要写好这个故事，还缺哪些关键信息」，
 * 问清后再把答案拼成 {@code 中文名：答案} 的多行文本，落库到 {@code t_novel.constraint_text}。</p>
 *
 * <p>这段文本此后作为模板变量 {@code {{constraints}}} 出现在所有创作场景里（蓝图四段、总纲、大纲、
 * 正文、角色、物品、事件），是「作者到底要写什么」的唯一答案；作者在小说设置里改一次，
 * 后面所有生成都跟着变。</p>
 *
 * <p>问题字段受 {@link NovelConstraintField} 白名单约束——模型自创的 key 一律丢弃，
 * 免得换个说法就把落库结构写乱。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlueprintQuestionService {

    /** 一次最多问几个：超过 4 个，作者就开始瞎填了。 */
    private static final int MAX_QUESTIONS = 4;

    /** 单个答案的保留长度 */
    private static final int MAX_ANSWER_CHARS = 300;

    /** 约束文本总长上限，与 {@code BlueprintRequest.constraintText} 的校验保持一致 */
    private static final int MAX_CONSTRAINT_CHARS = 2000;

    /** 模板被清空时的兜底提示词，保证这个功能不会因为用户清空模板而失效 */
    private static final String FALLBACK_PROMPT = """
            你是网络小说策划编辑。作者刚给了一个创意，你要判断他还缺哪些关键信息才能开始设计设定。
            只问「不问就没法往下设计」的信息，最多 4 个；创意里已经写清的方向不要重复问。
            问题必须能用一句话答完，并配一句示例答案。key 只能取 protagonist / goal / antagonist /
            power / world / opening / ending / tone / taboo 之一。
            """;

    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;
    private final PromptTemplateService promptTemplateService;

    /* ------------------------------------------------------------------ */
    /* 1. 追问                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 根据作者已填的信息，让 AI 判断还缺什么。
     *
     * <p>创意已写清时可能返回空列表，调用方应当照常进入下一步，不要拿「没问到问题」当失败。</p>
     */
    public BlueprintQuestionsVO questions(BlueprintRequest request) {
        if (request == null || !hasText(request.getIdea())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先填写核心创意");
        }
        AiEndpoint base = endpointResolver.resolve(AiScene.BLUEPRINT_QUESTIONS);
        Map<String, String> vars = vars(request);
        String system = promptTemplateService.system(AiScene.BLUEPRINT_QUESTIONS, PromptVars.clean(vars));
        if (!hasText(system)) {
            system = FALLBACK_PROMPT;
        }
        String user = buildUserPrompt(request, promptTemplateService.usedVariables(AiScene.BLUEPRINT_QUESTIONS));

        ChatResult reply = chat(base, system, user);
        List<BlueprintQuestion> questions = parseQuestions(reply.content());

        BlueprintQuestionsVO vo = new BlueprintQuestionsVO();
        vo.setQuestions(questions);
        vo.setModel(base.model());
        log.info("[blueprint-questions] userId={} asked={} model={}",
                UserContextHolder.requireUserId(), questions.size(), base.model());
        return vo;
    }

    private List<BlueprintQuestion> parseQuestions(String content) {
        List<BlueprintQuestion> result = new ArrayList<>();
        if (!hasText(content)) {
            return result;
        }
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            log.warn("[blueprint-questions] 模型没有返回 JSON，原始输出：{}", abbreviate(content));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "AI 没有返回结构化的问题，请重试");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                root = objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[blueprint-questions] 解析失败：{} | 原始输出：{}",
                        strictFailure.getMessage(), abbreviate(candidate));
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, "AI 返回的内容无法解析，请重试");
            }
        }

        JsonNode list = root.path("questions");
        if (!list.isArray() && root.isArray()) {
            list = root;
        }
        if (!list.isArray()) {
            return result;
        }
        Set<NovelConstraintField> seen = EnumSet.noneOf(NovelConstraintField.class);
        for (JsonNode node : list) {
            if (result.size() >= MAX_QUESTIONS) {
                break;
            }
            NovelConstraintField field = NovelConstraintField.of(text(node, "key"));
            if (field == null || !seen.add(field)) {
                continue;
            }
            String question = text(node, "question");
            if (!hasText(question)) {
                continue;
            }
            BlueprintQuestion item = new BlueprintQuestion();
            item.setKey(field.key());
            item.setLabel(field.label());
            item.setQuestion(truncate(question, 120));
            item.setHint(truncate(text(node, "hint"), 120));
            result.add(item);
        }
        return result;
    }

    /**
     * user 消息：只写模板里没引用的字段。
     *
     * <p>与创作场景同一套裁剪逻辑——模板写了 {@code {{idea}}}，这里就不再重复贴一遍创意。</p>
     */
    private String buildUserPrompt(BlueprintRequest request, Set<String> used) {
        StringBuilder builder = new StringBuilder();
        StringBuilder body = new StringBuilder();
        if (PromptVars.emit(used, "novelTitle") && hasText(request.getTitle())) {
            body.append("书名：").append(request.getTitle().trim()).append("\n");
        }
        if (PromptVars.emit(used, "genre") && hasText(request.getGenre())) {
            body.append("题材：").append(request.getGenre().trim()).append("\n");
        }
        if (PromptVars.emit(used, "tags") && request.getTags() != null && !request.getTags().isEmpty()) {
            body.append("标签：").append(String.join("、", request.getTags())).append("\n");
        }
        if (PromptVars.emit(used, "idea") && hasText(request.getIdea())) {
            body.append("核心创意：").append(request.getIdea().trim()).append("\n");
        }
        if (PromptVars.emit(used, "style") && hasText(request.getStyle())) {
            body.append("风格偏好：").append(request.getStyle().trim()).append("\n");
        }
        if (PromptVars.emit(used, "targetLength") && hasText(request.getTargetLength())) {
            body.append("目标篇幅：").append(request.getTargetLength().trim()).append("\n");
        }
        if (PromptVars.emit(used, "protagonistHint") && hasText(request.getProtagonistHint())) {
            body.append("主角偏好：").append(request.getProtagonistHint().trim()).append("\n");
        }
        if (!body.isEmpty()) {
            builder.append("【作者已填写】\n").append(body);
        }
        builder.append("\n请判断还缺哪些关键信息，按约定的 JSON 输出。");
        return builder.toString();
    }

    private Map<String, String> vars(BlueprintRequest request) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("novelTitle", hasText(request.getTitle()) ? request.getTitle().trim() : "这本书");
        vars.put("genre", hasText(request.getGenre()) ? request.getGenre().trim() : "通用");
        vars.put("tags", request.getTags() == null ? "" : String.join("、", request.getTags()));
        vars.put("idea", request.getIdea());
        vars.put("style", request.getStyle());
        vars.put("targetLength", request.getTargetLength());
        vars.put("protagonistHint", request.getProtagonistHint());
        return vars;
    }

    /* ------------------------------------------------------------------ */
    /* 2. 约束组装                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * 把作者的逐项作答拼成落库文本。
     *
     * <p>规则：key 必须命中白名单、同一项只取第一次出现的值、空答案直接丢弃
     * （不写「主角设定：」这种空行，否则后续提示词里会多出一堆无意义的标题）。
     * 返回空串表示作者什么都没答，调用方据此跳过注入。</p>
     */
    public String assemble(ConstraintFormRequest form) {
        if (form == null || form.getAnswers() == null || form.getAnswers().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        Set<NovelConstraintField> seen = EnumSet.noneOf(NovelConstraintField.class);
        for (ConstraintFormRequest.ConstraintAnswer answer : form.getAnswers()) {
            if (answer == null) {
                continue;
            }
            NovelConstraintField field = NovelConstraintField.of(answer.getKey());
            if (field == null || !seen.add(field)) {
                continue;
            }
            // 约束是一行一条，答案里的换行必须抹平，否则拼出来的文本会被下游当成多条
            String value = answer.getValue() == null ? "" : answer.getValue().trim().replaceAll("\\s+", " ");
            if (value.isEmpty()) {
                continue;
            }
            builder.append(field.label()).append("：").append(truncate(value, MAX_ANSWER_CHARS)).append("\n");
            if (builder.length() >= MAX_CONSTRAINT_CHARS) {
                break;
            }
        }
        String text = builder.toString().trim();
        return text.length() <= MAX_CONSTRAINT_CHARS ? text : text.substring(0, MAX_CONSTRAINT_CHARS);
    }

    /* ------------------------------------------------------------------ */

    private ChatResult chat(AiEndpoint base, String systemPrompt, String userPrompt) {
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                base.timeoutMs(),
                base.extraHeaders(),
                0.6D,
                800);
        try {
            return aiUsageService.chat(AiScene.BLUEPRINT_QUESTIONS, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw BizException.of(ErrorCode.AI_CALL_FAILED,
                    hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private String truncate(String value, int max) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
