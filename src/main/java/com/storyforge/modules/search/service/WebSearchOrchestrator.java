package com.storyforge.modules.search.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.search.dto.SearchBundleVO;
import com.storyforge.modules.search.dto.SearchResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 联网检索编排器：由 AI 自动判断「是否需要联网、需要搜什么」，再决定是否真正检索。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>把用户输入 + 小说上下文交给模型，要求输出结构化 JSON
 *       {@code {"needSearch": bool, "queries": [...], "reason": "..."}}；</li>
 *   <li>{@code needSearch=false} 或未配搜索源时直接跳过，返回 {@code used=false}；</li>
 *   <li>{@code needSearch=true} 时对每个 {@code queries} 依次调用 {@link WebSearchService}，
 *       合并命中结果（去重，上限由场景/搜索源决定）；</li>
 *   <li>判断这一 AI 调用失败时<b>不阻断主流程</b>，降级为「不联网」，让生成继续。</li>
 * </ol>
 *
 * <p>多次调用 AI 用于「判断」与「生成」是两个独立请求，都走 {@link AiUsageService} 计数。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchOrchestrator {

    /** 单次判断最多允许模型给出的检索词数量，防止一次搜十几路。 */
    private static final int MAX_QUERIES = 5;

    private static final String SYSTEM_PROMPT = """
            你是「联网搜索意图识别器」。判断给出一段创作任务是否需要联网搜索补充真实背景资料，并给出最值得搜索的关键词。
            只在「确实需要真实世界事实/细节」时才判断需要搜索，例如：历史事件与年代、真实地名/地理、行业流程与专业术语、科技设定、现实机构或人物、特定年代的风俗器物。
            纯虚构创作（幻想设定、人物关系、架空情节、文风修辞）不需要搜索。
            你的输出必须是一个 JSON 对象，不要解释文字，不要使用 markdown 代码块：
            {
              "needSearch": true 或 false,
              "queries": ["搜索词1", "搜索词2"],
              "reason": "一句话说明为何需要或不需要"
            }
            需要搜索时 queries 给出 1~5 个具体、可检索的独立关键词（不要重复、不要整句、不要书名或人物名本身），按重要程度排序；
            不需要搜索时 queries 给空数组 []。
            """;

    private final WebSearchService webSearchService;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;

    /**
     * 判断并（如需）执行联网检索。
     *
     * @param scene      所属场景（决定检索用哪个搜索源、AI 计数归属）
     * @param endpoint   已解析好的 AI 端点（复用主流程的端点，避免重复解析）
     * @param context    给判断模型的小说上下文，如「书名/题材/本章要点/已有设定」
     * @param userInput  用户本次的输入（事件要求 / 大纲要求 / 正文要求）
     * @param topK       期望覆盖条数，可为 null（取场景默认）
     */
    public SearchBundleVO decideAndSearch(AiScene scene,
                                          AiEndpoint endpoint,
                                          String context,
                                          String userInput,
                                          Integer topK) {
        Decision decision;
        try {
            decision = judge(scene, endpoint, context, userInput);
        } catch (RuntimeException ex) {
            // 判断失败不阻断生成：降级为不联网
            log.warn("[web-orchestrator] judge failed scene={}: {}", scene, ex.toString());
            return SearchBundleVO.skipped("联网判断失败，已按不联网继续生成");
        }

        if (!decision.needSearch() || decision.queries() == null || decision.queries().isEmpty()) {
            log.info("[web-orchestrator] skip search scene={} reason={}", scene, decision.reason());
            return SearchBundleVO.skipped("AI 判断本次无需联网：" + (decision.reason() == null ? "" : decision.reason()));
        }

        return execute(scene, decision.queries(), topK);
    }

    /** 用户已显式指定检索词：跳过 AI 判断，直接检索（保持原有「指定词即检索」语义）。 */
    public SearchBundleVO searchDirect(AiScene scene, String query, Integer topK) {
        if (query == null || query.isBlank()) {
            return SearchBundleVO.skipped("检索词为空");
        }
        try {
            return webSearchService.search(scene, query.trim(), topK);
        } catch (RuntimeException ex) {
            log.warn("[web-orchestrator] direct search failed scene={} query={}: {}", scene, query, ex.toString());
            return new SearchBundleVO(false, null, null, query.trim(), topK, List.of(),
                    "联网检索失败：" + (ex.getMessage() == null ? "未知原因" : ex.getMessage()), 0);
        }
    }

    /** 调用 AI 判断是否联网及搜索词。 */
    private Decision judge(AiScene scene, AiEndpoint endpoint, String context, String userInput) {
        String user = buildJudgePrompt(context, userInput);
        var reply = aiUsageService.chat(scene, endpoint, List.of(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user(user)));
        JsonNode root = parse(reply.content());
        boolean needSearch = root.path("needSearch").asBoolean(false);
        List<String> queries = new ArrayList<>();
        JsonNode qs = root.path("queries");
        if (qs.isArray()) {
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode q : qs) {
                String text = q.asText("").trim();
                if (text.isEmpty() || seen.contains(text)) {
                    continue;
                }
                seen.add(text);
                queries.add(text);
                if (queries.size() >= MAX_QUERIES) {
                    break;
                }
            }
        }
        String reason = root.path("reason").asText("");
        log.info("[web-orchestrator] judge scene={} needSearch={} queries={}",
                scene, needSearch, queries);
        return new Decision(needSearch, queries, reason);
    }

    private String buildJudgePrompt(String context, String userInput) {
        StringBuilder builder = new StringBuilder();
        builder.append("【创作上下文】\n");
        builder.append(context == null || context.isBlank() ? "（无）" : context.trim()).append("\n");
        builder.append("\n【本次任务】\n");
        builder.append(userInput == null || userInput.isBlank() ? "（无）" : userInput.trim()).append("\n");
        return builder.toString();
    }

    /** 对多个检索词依次检索并合并结果。 */
    private SearchBundleVO execute(AiScene scene, List<String> queries, Integer topK) {
        List<SearchResultVO> merged = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        String provider = null;
        String providerLabel = null;
        long totalElapsed = 0;
        String firstError = null;
        boolean anyUsed = false;

        for (String query : queries) {
            SearchBundleVO bundle;
            try {
                bundle = webSearchService.search(scene, query, topK);
            } catch (RuntimeException ex) {
                log.warn("[web-orchestrator] search failed scene={} query={}: {}", scene, query, ex.toString());
                if (firstError == null) {
                    firstError = "联网检索失败：" + (ex.getMessage() == null ? "未知原因" : ex.getMessage());
                }
                continue;
            }
            if (bundle.used()) {
                anyUsed = true;
                provider = bundle.provider();
                providerLabel = bundle.providerLabel();
            }
            if (bundle.error() != null && firstError == null) {
                firstError = bundle.error();
            }
            totalElapsed += bundle.elapsedMs();
            if (bundle.results() != null) {
                for (SearchResultVO item : bundle.results()) {
                    String url = item.url() == null ? "" : item.url();
                    if (!url.isEmpty() && !seenUrls.add(url)) {
                        continue;
                    }
                    merged.add(item);
                }
            }
        }

        if (!anyUsed) {
            return new SearchBundleVO(false, provider, providerLabel, String.join(" | ", queries),
                    topK, merged, firstError == null ? "联网检索未成功" : firstError, totalElapsed);
        }
        return new SearchBundleVO(true, provider, providerLabel, String.join(" | ", queries),
                topK, merged, firstError, totalElapsed);
    }

    private JsonNode parse(String content) {
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            throw new BizException(com.storyforge.common.api.ErrorCode.AI_PARSE_FAILED,
                    "联网判断模型未返回结构化结果");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                throw new BizException(com.storyforge.common.api.ErrorCode.AI_PARSE_FAILED,
                        "联网判断结果无法解析");
            }
        }
    }

    private record Decision(boolean needSearch, List<String> queries, String reason) {
    }
}
