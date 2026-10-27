package com.storyforge.modules.novel.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import com.storyforge.modules.bookshelf.dto.CharacterRelationVO;
import com.storyforge.modules.bookshelf.dto.CharacterSaveRequest;
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.ChapterRequest;
import com.storyforge.modules.bookshelf.dto.ChapterVO;
import com.storyforge.modules.bookshelf.dto.FactionMemberRequest;
import com.storyforge.modules.bookshelf.dto.FactionRequest;
import com.storyforge.modules.bookshelf.dto.FactionVO;
import com.storyforge.modules.bookshelf.dto.ItemRequest;
import com.storyforge.modules.bookshelf.dto.ItemVO;
import com.storyforge.modules.bookshelf.dto.MasterOutlineRequest;
import com.storyforge.modules.bookshelf.dto.MasterOutlineVO;
import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.dto.RelationRequest;
import com.storyforge.modules.bookshelf.dto.SettingDocVO;
import com.storyforge.modules.bookshelf.dto.TimelineRequest;
import com.storyforge.modules.bookshelf.dto.TimelineVO;
import com.storyforge.modules.bookshelf.entity.SettingDocEntity;
import com.storyforge.modules.bookshelf.mapper.SettingDocMapper;
import com.storyforge.modules.bookshelf.service.BookshelfService;
import com.storyforge.modules.bookshelf.service.FactionService;
import com.storyforge.modules.bookshelf.service.MasterOutlineService;
import com.storyforge.modules.bookshelf.service.NovelAssetService;
import com.storyforge.modules.inspiration.entity.InspirationMessageEntity;
import com.storyforge.modules.inspiration.mapper.InspirationMessageMapper;
import com.storyforge.modules.novel.agent.dto.AgentMessageVO;
import com.storyforge.modules.novel.agent.dto.AgentOperationVO;
import com.storyforge.modules.novel.agent.dto.AgentSendRequest;
import com.storyforge.modules.novel.agent.dto.AgentSendResult;
import com.storyforge.modules.novel.agent.dto.AgentSessionVO;
import com.storyforge.modules.novel.agent.entity.NovelAgentMessageEntity;
import com.storyforge.modules.novel.agent.entity.NovelAgentSessionEntity;
import com.storyforge.modules.novel.agent.mapper.NovelAgentMessageMapper;
import com.storyforge.modules.novel.agent.mapper.NovelAgentSessionMapper;
import com.storyforge.modules.novel.dto.NovelPatch;
import com.storyforge.modules.novel.dto.NovelVO;
import com.storyforge.modules.novel.service.NovelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小说智能助手 Agent。
 *
 * <p>通过聊天形式修改小说：把当前小说状态（设定、人物、物品、势力、关系、事件、章节）
 * 序列化成快照交给模型，模型根据用户自然语言指令输出一组结构化操作
 * （{@code {reply, operations[]}}），服务端把操作映射到现有 Service 方法执行，
 * 实现「改世界观、删人物、改物品、改关系」等全部编辑能力。</p>
 *
 * <p>会话按小说维度保存（每部小说一个会话），聊天记录落 {@code t_novel_agent_message}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelAgentService {

    private static final int CONTEXT_MAX_MESSAGES = 20;
    /** 单次指令最多迭代多少轮 AI，防止死循环 */
    private static final int MAX_AGENT_TURNS = 6;
    /** 智能助手单次 AI 调用超时（毫秒），多步读快照 + 结构化输出较慢，需放宽 */
    private static final int AGENT_TIMEOUT_MS = 300_000;
    /** 智能助手单轮输出上限，收敛到合理值避免模型长篇输出拖慢 */
    private static final int AGENT_MAX_TOKENS = 4096;
    private static final int MAX_CHARACTERS_IN_SNAPSHOT = 15;
    private static final int MAX_ITEMS_IN_SNAPSHOT = 20;
    private static final int MAX_FACTIONS_IN_SNAPSHOT = 10;
    private static final int MAX_RELATIONS_IN_SNAPSHOT = 40;
    private static final int MAX_EVENTS_IN_SNAPSHOT = 30;
    private static final int MAX_MASTER_OUTLINES_IN_SNAPSHOT = 20;
    private static final int MAX_CHAPTERS_IN_SNAPSHOT = 60;
    private static final int TITLE_MAX_LENGTH = 40;
    /** 灵感对话记录注入上限：最多取最近多少轮，每轮最多多少字 */
    private static final int MAX_INSPIRATION_TURNS = 40;
    private static final int MAX_INSPIRATION_TURN_CHARS = 400;
    /** 回复里出现这些说法，说明模型在宣称「已经改完了」，可用来识别「只说不做」 */
    private static final List<String> ACTION_CLAIM_WORDS = List.of(
            "已删除", "已删掉", "已经删除", "已修改", "已改好", "改好了", "已更新",
            "已新增", "已创建", "已改为", "已把", "已经改", "删掉了");

    private static final String SYSTEM_PROMPT = """
            你是小说编辑助手，负责根据作者的自然语言指令，直接修改当前小说的设定、人物、物品、势力、人物关系、事件、总纲与章节。

            【工作方式】
            1. 先读【当前小说状态】，理解已有的人物、物品、势力、关系、设定、总纲和章节。
            2. 如果提供了【灵感对话记录】，它是作者最初创作这本书时的原始设想，可用来判断作者的真实意图；但以【当前小说状态】为准——若记录里的早期想法与现状不一致，按现状理解，不要把已废弃的早期设定当成现行设定。
            3. 根据作者指令，把需要执行的修改写成 operations 数组（每项一个操作）。
            4. 所有 id 必须逐字来自【当前小说状态】里的 id，绝不要编造。
            5. 删除操作要谨慎：只有作者明确要求删除时才删除；拿不准就先问，不要自作主张。

            【多步执行】
            作者的一句话可能包含多个任务，或需要「先建后改」「先查再改」等多步才能完成。
            你一次输出不必（也不应）把所有事都塞进一次调用；可以分多轮逐步完成：
            - 每一轮你只处理当前最该做的一批操作，输出 operations 执行它们；
            - 执行后你会收到【上一轮执行结果】，据此判断还有哪些没做完，继续下一轮；
            - 只有当作者要求的全部任务都已完成（或确认无法完成、需要作者补充信息）时，才输出 done: true 结束。
            - done: true 时 operations 应为空数组 []，reply 里给作者一个完整的总结。

            【操作类型与参数】
            - UPDATE_NOVEL：改基本信息。参数 fields：{title?, penName?, genre?, tags?, intro?, status?}（只写要改的字段）。
            - UPDATE_SETTING：改设定。参数：{category: "WORLDVIEW|BACKGROUND|CORE_CONFLICT|HIGHLIGHT|STYLE", content: "修改后的完整内容"}。
            - CREATE_CHARACTER：建角色。参数 fields：{name, rolePos("PROTAGONIST|SUPPORTING|ANTAGONIST|MINOR"), identity, gender, ageDesc, appearance, personality, motivation, goldenFinger, arc}。
            - UPDATE_CHARACTER：改角色。参数：{id, fields}，fields 里给要改的字段（含 name 一起给，未改字段保持原值）。
            - DELETE_CHARACTER：删角色。参数：{id}。
            - CREATE_ITEM：建物品。参数 fields：{name, category, owner, description, origin, status("ACTIVE|DESTROYED|ABANDONED|LOST|CONSUMED"), quantity}。
            - UPDATE_ITEM：改物品。参数：{id, fields}。
            - DELETE_ITEM：删物品。参数：{id}。
            - CREATE_RELATION：建关系。参数 fields：{fromCharacterId 或 fromCharacterName, toCharacterId 或 toCharacterName, relationType("MASTER_APPRENTICE|FAMILY|SPOUSE|LOVER|FRIEND|ALLY|ENEMY|RIVAL|COLLEAGUE|OTHER"), description}。两端角色必须在本书已存在；同一轮里刚 CREATE_CHARACTER 的角色还没有 id，此时改用 fromCharacterName / toCharacterName 写角色名。
            - UPDATE_RELATION：改关系。参数：{id, fields}，fields 同样支持 fromCharacterName / toCharacterName。
            - DELETE_RELATION：删关系。参数：{id}。
            - CREATE_FACTION：建势力。参数 fields：{name, category, remark, scope, status("ACTIVE|DORMANT|DESTROYED|DISBANDED")}。
            - UPDATE_FACTION：改势力。参数：{id, fields}。
            - DELETE_FACTION：删势力。参数：{id}。
            - ADD_FACTION_MEMBER：加势力成员。参数：{factionId, characterId, position}。
            - UPDATE_FACTION_MEMBER：改成员职位。参数：{memberId, position}。
            - REMOVE_FACTION_MEMBER：移除成员。参数：{memberId}。
            - UPDATE_EVENT：改事件。参数：{id, fields: {eventTime?, title?, description?, status?("UNUSED|ONGOING|DONE|ABANDONED")}}。
            - DELETE_EVENT：删事件。参数：{id}。
            - CREATE_MASTER_OUTLINE：建总纲。参数 fields：{title, content, sortNo?}。总纲是「整段剧情的走向」，只有一段正文（200~400 字，写这一段要发生什么），不关联人物/势力/物品；一条总纲对应多条章节大纲，章节大纲不得越出所属总纲的范围。
            - UPDATE_MASTER_OUTLINE：改总纲。参数：{id, fields: {title?, content?}}。改 content 必须给修改后的完整正文，不能只给片段。
            - DELETE_MASTER_OUTLINE：删总纲。参数：{id}。删除后原本挂在它下面的章节大纲会解除关联，章节本身不会删除。
            - CREATE_CHAPTER：建章节。参数 fields：{volumeNo?, chapterNo?, title, summary?, status?, masterOutlineId?}。
            - UPDATE_CHAPTER：改章节。参数：{id, fields: {title?, summary?, content?, status?, masterOutlineId?}}。masterOutlineId 写总纲 id 表示把本章挂到该总纲；写 0 表示解除关联。
            - DELETE_CHAPTER：删章节。参数：{id}。

            【输出格式】
            只输出一个 JSON 对象，不要解释文字，不要 markdown 代码块：
            {
              "reply": "给作者的自然语言回复，说明你这一步改了什么、为什么这么改",
              "operations": [ 操作对象数组 ],
              "done": true 或 false
            }

            操作对象的写法示例（照此结构，不要自创层级）：
            {"reply": "改好了", "operations": [{"type": "UPDATE_CHARACTER", "id": "1234567890", "fields": {"name": "沈默", "personality": "更沉默了"}}, {"type": "CREATE_CHARACTER", "fields": {"name": "小林", "rolePos": "SUPPORTING", "identity": "跑外信使"}}, {"type": "UPDATE_SETTING", "category": "WORLDVIEW", "content": "完整的新世界观正文"}], "done": true}
            - type、id、category 等控制字段与业务字段在同一层；
            - 角色/物品/势力/关系/事件的业务字段放在该操作对象同级的 fields 里；
            - 禁止再用 params、arguments、payload、data 等键把参数包一层。

            【硬性要求】
            1. 没有需要执行的修改时 operations 给空数组 []，只回复文字。
            2. done 表示「作者要求的所有任务是否已全部完成」：全部完成（或需要作者补充信息）给 true，还有未完成的任务给 false。
            3. UPDATE_SETTING 的 content 必须给修改后的完整内容，不能只给改动片段。
            4. 所有字符串值单行纯文本：不出现英文双引号与换行，引用他人话语用「」。
            5. 一个操作对象只对应一处修改；改多个角色就写多个 UPDATE_CHARACTER。
            6. 作者常用名字指代实体（如「改一下沈默」）：先在【当前小说状态】里按名字找到对应条目，操作里必须带上它的 id 字段；名字只写在 fields.name 或 reply 里，绝不能只给名字不给 id。
            7. 修改类操作（UPDATE_/DELETE_）的 id 必须写在操作对象第一层（与 type 同级），不要写进 fields 里，也不要省略。
            8. 绝不要自己编造 id。同一批里刚新建的角色需要被关系/势力成员引用时，用 fromCharacterName / toCharacterName / name 写角色名，后端会按名字匹配；【上一轮执行结果】里的 (id=…) 才是真实 id，下一轮可以直接用。
            9. 总纲是章节大纲的上位约束，两者不要混：作者说「改这段剧情走向」用 UPDATE_MASTER_OUTLINE；说「把某章挂到某总纲上」用 UPDATE_CHAPTER 的 masterOutlineId。给章节挂总纲前，先从【当前小说状态】的 masterOutlines 里取 id，不要凭标题新建一条。
            10. 绝不能只说不做：凡是 reply 里说了「已删除/已修改/已新增」，operations 里就必须有对应操作。系统会检查这一点，说了却没给操作会被要求重做。
            """;

    private final NovelAgentSessionMapper sessionMapper;
    private final NovelAgentMessageMapper messageMapper;
    private final InspirationMessageMapper inspirationMessageMapper;
    private final NovelService novelService;
    private final BookshelfService bookshelfService;
    private final NovelAssetService assetService;
    private final FactionService factionService;
    private final MasterOutlineService masterOutlineService;
    private final SettingDocMapper settingDocMapper;
    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;
    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;

    /* ------------------------------------------------------------------ */
    /* 会话                                                               */
    /* ------------------------------------------------------------------ */

    public AgentSessionVO session(Long novelId) {
        requireNovel(novelId);
        NovelAgentSessionEntity session = getOrCreateSession(novelId);
        return toSessionVO(session, listMessages(session.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearSession(Long novelId) {
        requireNovel(novelId);
        NovelAgentSessionEntity session = findSession(novelId);
        if (session == null) {
            return;
        }
        messageMapper.delete(Wrappers.<NovelAgentMessageEntity>lambdaQuery()
                .eq(NovelAgentMessageEntity::getSessionId, session.getId()));
        sessionMapper.update(null, Wrappers.<NovelAgentSessionEntity>lambdaUpdate()
                .set(NovelAgentSessionEntity::getTitle, null)
                .set(NovelAgentSessionEntity::getMessageCount, 0)
                .set(NovelAgentSessionEntity::getLastMessageAt, null)
                .set(NovelAgentSessionEntity::getUpdatedAt, LocalDateTime.now())
                .eq(NovelAgentSessionEntity::getId, session.getId()));
    }

    /* ------------------------------------------------------------------ */
    /* 发送消息                                                           */
    /* ------------------------------------------------------------------ */

    /** 多步执行过程中每轮的进度回调，供异步任务把中间过程实时反馈给前端。 */
    public interface TurnSink {
        /**
         * 一轮执行完毕。
         *
         * @param turnNo     第几轮（从 1 开始）
         * @param reply      本轮模型给作者的自然语言回复
         * @param operations 本轮执行的操作摘要（成功/失败）
         */
        void onTurn(int turnNo, String reply, List<AgentOperationVO> operations);
    }

    /** 多步循环中每轮开始前的取消检测回调，返回 true 表示已请求取消、应立即中止。 */
    public interface CancelCheck {
        boolean isCancelled();
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentSendResult send(Long novelId, AgentSendRequest request) {
        return sendInternal(novelId, request, null, null);
    }

    /** 供异步任务服务调用：执行多步 Agent Loop，每轮通过 sink 回调中间过程。 */
    public AgentSendResult sendWithSink(Long novelId, AgentSendRequest request, TurnSink sink) {
        return sendInternal(novelId, request, sink, null);
    }

    /** 供异步任务服务调用：支持取消检测，取消后中止循环。 */
    public AgentSendResult sendWithSink(Long novelId, AgentSendRequest request, TurnSink sink, CancelCheck cancelCheck) {
        return sendInternal(novelId, request, sink, cancelCheck);
    }

    private AgentSendResult sendInternal(Long novelId, AgentSendRequest request, TurnSink sink, CancelCheck cancelCheck) {
        requireNovel(novelId);
        String content = request.getContent().trim();
        NovelAgentSessionEntity session = getOrCreateSession(novelId);

        NovelAgentMessageEntity userMessage = saveMessage(session, "USER", content, null, "OK",
                null, null, null, null);

        String replyContent = null;
        List<AgentOperationVO> operations = new ArrayList<>();
        String error = null;
        String model = null;
        Integer promptTokens = null;
        Integer completionTokens = null;
        try {
            AiEndpoint endpoint = resolveAgentEndpoint();

            // 多步循环：反复调用 AI，直到模型判定任务完成或达到轮数上限
            StringBuilder feedback = new StringBuilder();
            String lastReply = "";
            boolean finished = false;
            boolean emptyClaimRetried = false;
            for (int turn = 1; turn <= MAX_AGENT_TURNS && !finished; turn++) {
                if (cancelCheck != null && cancelCheck.isCancelled()) {
                    throw new AgentCancelledException();
                }
                String systemPrompt = systemPrompt(novelId, feedback.toString());
                List<ChatMessage> payload = buildPayload(session, systemPrompt);
                ChatResult reply = aiUsageService.chat(AiScene.NOVEL_AGENT, endpoint, payload);
                model = reply.model();
                promptTokens = addToken(promptTokens, reply.promptTokens());
                completionTokens = addToken(completionTokens, reply.completionTokens());

                AgentReply parsed = parseReply(reply.content());
                List<AgentOperationVO> turnOps = executeOperations(novelId, parsed.operations());
                operations.addAll(turnOps);

                String stepNote = appendFailures(parsed.reply(), turnOps);
                lastReply = hasText(stepNote) ? stepNote : parsed.reply();

                if (sink != null) {
                    sink.onTurn(turn, lastReply, turnOps);
                }

                if (parsed.done() || (turnOps.isEmpty() && parsed.operations().isEmpty())) {
                    // 模型偶尔「只说不做」：reply 声称已删除/已修改，operations 却是空数组。
                    // 这种情况必须纠正一次，否则作者会以为改好了，实际库里什么都没变。
                    if (!emptyClaimRetried && parsed.operations().isEmpty() && operations.isEmpty()
                            && claimsAction(parsed.reply())) {
                        emptyClaimRetried = true;
                        log.warn("[novel-agent] 模型声称已执行但未输出操作，已要求重试 novelId={} reply={}",
                                novelId, truncate(parsed.reply(), 120));
                        feedback.append("【纠正】你上一步的回复声称已经完成修改，但没有输出任何 operations，实际上什么都没做。\n")
                                .append("如果作者确实要求修改，请在这一轮输出对应的 operations；")
                                .append("如果因为缺少信息无法执行，请在 reply 里明确说明缺什么，不要声称已完成。\n\n");
                        continue;
                    }
                    finished = true;
                    replyContent = lastReply;
                    break;
                }
                // 把本轮执行结果反馈给模型，让它继续处理剩余任务
                feedback.append("【上一轮执行结果】\n")
                        .append("你上一步说：").append(truncate(parsed.reply(), 300)).append("\n")
                        .append("执行了 ").append(turnOps.size()).append(" 项操作：\n");
                for (AgentOperationVO op : turnOps) {
                    feedback.append("- ").append(op.isSuccess() ? "成功" : "失败")
                            .append(" ").append(labelOfType(op.getType()));
                    if (hasText(op.getTarget())) {
                        feedback.append("「").append(op.getTarget()).append("」");
                    }
                    // 带上真实 id：新建的角色/物品/势力下一轮可直接引用，避免模型编造 id
                    if (hasText(op.getId())) {
                        feedback.append("(id=").append(op.getId()).append(")");
                    }
                    if (!op.isSuccess() && hasText(op.getMessage())) {
                        feedback.append("，原因：").append(op.getMessage());
                    }
                    feedback.append("\n");
                }
                feedback.append("请继续完成作者剩余的指令，全部完成后输出 done: true。\n\n");
            }
            if (replyContent == null) {
                replyContent = lastReply;
                if (!hasText(replyContent)) {
                    replyContent = "已处理你的指令，但仍有任务可能未全部完成，请确认或继续补充。";
                }
            }
        } catch (AgentCancelledException ex) {
            // 用户主动停止：不落库失败消息，直接向上抛出，由任务服务标记为 CANCELLED
            throw ex;
        } catch (BizException ex) {
            error = ex.getMessage();
            log.warn("[novel-agent] send failed novelId={}: {}", novelId, error);
        } catch (RuntimeException ex) {
            error = ex.getMessage() == null ? "AI 调用失败" : ex.getMessage();
            log.warn("[novel-agent] send failed novelId={}: {}", novelId, ex.toString());
        }

        NovelAgentMessageEntity assistantMessage = saveMessage(session, "ASSISTANT",
                replyContent == null ? "" : replyContent, operations,
                replyContent == null ? "FAILED" : "OK", model, promptTokens, completionTokens, error);

        String title = session.getTitle();
        if (!hasText(title)) {
            title = truncate(content.replaceAll("\\s+", " ").trim(), TITLE_MAX_LENGTH);
        }
        updateSessionStats(session, title);

        AgentSendResult result = new AgentSendResult();
        result.setUserMessage(toMessageVO(userMessage));
        result.setAssistantMessage(toMessageVO(assistantMessage));
        result.setSession(toSessionVO(reloadSession(session.getId()), List.of()));
        return result;
    }

    /* ------------------------------------------------------------------ */
    /* 上下文构建                                                         */
    /* ------------------------------------------------------------------ */

    private String systemPrompt(Long novelId, String feedback) {
        String rendered = promptTemplateService.system(AiScene.NOVEL_AGENT, Map.of());
        String instruction = hasText(rendered) ? rendered : SYSTEM_PROMPT;
        StringBuilder builder = new StringBuilder();
        builder.append(instruction).append("\n\n【当前小说状态】\n").append(buildSnapshot(novelId));
        if (hasText(feedback)) {
            builder.append("\n\n").append(feedback);
        }
        return builder.toString();
    }

    private List<ChatMessage> buildPayload(NovelAgentSessionEntity session, String systemPrompt) {
        List<ChatMessage> payload = new ArrayList<>();
        payload.add(ChatMessage.system(systemPrompt));
        List<NovelAgentMessageEntity> history = listMessages(session.getId());
        int from = Math.max(0, history.size() - CONTEXT_MAX_MESSAGES);
        for (int i = from; i < history.size(); i++) {
            NovelAgentMessageEntity item = history.get(i);
            if (!"OK".equals(item.getStatus())) {
                continue;
            }
            if (!"USER".equals(item.getRole()) && !"ASSISTANT".equals(item.getRole())) {
                continue;
            }
            payload.add(new ChatMessage(
                    "USER".equals(item.getRole()) ? "user" : "assistant",
                    item.getContent() == null ? "" : item.getContent()));
        }
        return payload;
    }

    /** 序列化小说状态快照，供模型读取当前设定并引用实体 id。 */
    private String buildSnapshot(Long novelId) {
        NovelDetailVO detail = bookshelfService.detail(novelId);
        NovelVO novel = detail.getNovel();
        Map<String, Object> snapshot = new LinkedHashMap<>();

        Map<String, Object> novelMap = new LinkedHashMap<>();
        novelMap.put("id", novel.getId());
        novelMap.put("title", novel.getTitle());
        novelMap.put("penName", novel.getPenName());
        novelMap.put("genre", novel.getGenre());
        novelMap.put("tags", novel.getTags());
        novelMap.put("intro", novel.getIntro());
        novelMap.put("status", novel.getStatus());
        snapshot.put("novel", novelMap);

        List<Map<String, Object>> settings = new ArrayList<>();
        for (SettingDocVO doc : detail.getSettings() == null ? List.<SettingDocVO>of() : detail.getSettings()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", doc.getCategory());
            item.put("label", doc.getLabel());
            item.put("content", doc.getContent());
            settings.add(item);
        }
        snapshot.put("settings", settings);

        List<CharacterVO> characters = assetService.listCharacters(novelId);
        List<Map<String, Object>> charList = new ArrayList<>();
        for (int i = 0; i < characters.size(); i++) {
            CharacterVO c = characters.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("name", c.getName());
            item.put("rolePos", c.getRolePos());
            item.put("roleLabel", c.getRoleLabel());
            item.put("identity", c.getIdentity());
            item.put("gender", c.getGender());
            item.put("ageDesc", c.getAgeDesc());
            if (i < MAX_CHARACTERS_IN_SNAPSHOT) {
                item.put("appearance", c.getAppearance());
                item.put("personality", c.getPersonality());
                item.put("motivation", c.getMotivation());
                item.put("goldenFinger", c.getGoldenFinger());
                item.put("arc", c.getArc());
            }
            charList.add(item);
        }
        snapshot.put("characters", charList);

        List<ItemVO> items = assetService.listItems(novelId);
        List<Map<String, Object>> itemList = new ArrayList<>();
        for (int i = 0; i < items.size() && i < MAX_ITEMS_IN_SNAPSHOT; i++) {
            ItemVO it = items.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", it.id());
            item.put("name", it.name());
            item.put("category", it.category());
            item.put("owner", it.owner());
            item.put("description", it.description());
            item.put("origin", it.origin());
            item.put("status", it.status());
            item.put("quantity", it.quantity());
            itemList.add(item);
        }
        snapshot.put("items", itemList);

        List<FactionVO> factions = factionService.list(novelId);
        List<Map<String, Object>> factionList = new ArrayList<>();
        for (int i = 0; i < factions.size() && i < MAX_FACTIONS_IN_SNAPSHOT; i++) {
            FactionVO f = factions.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", f.id());
            item.put("name", f.name());
            item.put("category", f.category());
            item.put("remark", f.remark());
            item.put("scope", f.scope());
            item.put("status", f.status());
            List<Map<String, Object>> members = new ArrayList<>();
            List<com.storyforge.modules.bookshelf.dto.FactionMemberVO> memberList =
                    f.members() == null ? List.of() : f.members();
            for (com.storyforge.modules.bookshelf.dto.FactionMemberVO m : memberList) {
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("memberId", m.id());
                member.put("characterId", m.characterId());
                member.put("name", m.name());
                member.put("position", m.position());
                members.add(member);
            }
            item.put("members", members);
            factionList.add(item);
        }
        snapshot.put("factions", factionList);

        List<CharacterRelationVO> relations = assetService.listRelations(novelId);
        List<Map<String, Object>> relationList = new ArrayList<>();
        for (int i = 0; i < relations.size() && i < MAX_RELATIONS_IN_SNAPSHOT; i++) {
            CharacterRelationVO r = relations.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.id());
            item.put("fromCharacterId", r.fromCharacterId());
            item.put("fromCharacterName", r.fromCharacterName());
            item.put("toCharacterId", r.toCharacterId());
            item.put("toCharacterName", r.toCharacterName());
            item.put("relationType", r.relationType());
            item.put("description", r.description());
            relationList.add(item);
        }
        snapshot.put("relations", relationList);

        List<TimelineVO> events = assetService.listTimeline(novelId);
        List<Map<String, Object>> eventList = new ArrayList<>();
        for (int i = 0; i < events.size() && i < MAX_EVENTS_IN_SNAPSHOT; i++) {
            TimelineVO e = events.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.id());
            item.put("title", e.title());
            item.put("eventTime", e.eventTime());
            item.put("description", e.description());
            item.put("status", e.status());
            eventList.add(item);
        }
        snapshot.put("events", eventList);

        List<MasterOutlineVO> masterOutlines = masterOutlineService.list(novelId);
        List<Map<String, Object>> outlineList = new ArrayList<>();
        for (int i = 0; i < masterOutlines.size() && i < MAX_MASTER_OUTLINES_IN_SNAPSHOT; i++) {
            MasterOutlineVO mo = masterOutlines.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", mo.id());
            item.put("sortNo", mo.sortNo());
            item.put("title", mo.title());
            // 正文不截断：模型要改总纲时必须看到原文，否则只能重写
            item.put("content", mo.content());
            item.put("chapterCount", mo.chapterCount());
            outlineList.add(item);
        }
        snapshot.put("masterOutlines", outlineList);

        List<ChapterVO> chapters = assetService.listChapters(novelId);
        List<Map<String, Object>> chapterList = new ArrayList<>();
        for (int i = 0; i < chapters.size() && i < MAX_CHAPTERS_IN_SNAPSHOT; i++) {
            ChapterVO ch = chapters.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ch.id());
            item.put("volumeNo", ch.volumeNo());
            item.put("chapterNo", ch.chapterNo());
            item.put("title", ch.title());
            item.put("summary", ch.summary());
            item.put("status", ch.status());
            if (ch.masterOutlineId() != null) {
                item.put("masterOutlineId", ch.masterOutlineId());
            }
            chapterList.add(item);
        }
        snapshot.put("chapters", chapterList);

        // 灵感模式创建的小说：把来源灵感会话的聊天记录一并注入，供模型参考创作初衷
        appendInspirationTranscript(snapshot, novel);

        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            log.warn("[novel-agent] serialize snapshot failed: {}", ex.getMessage());
            return "{}";
        }
    }

    /** 灵感对话记录写入快照；非灵感来源或记录为空时跳过。 */
    private void appendInspirationTranscript(Map<String, Object> snapshot, NovelVO novel) {
        if (novel == null || !"INSPIRATION".equalsIgnoreCase(novel.getSource())
                || novel.getInspirationSessionId() == null) {
            return;
        }
        try {
            List<InspirationMessageEntity> messages = inspirationMessageMapper.selectList(
                    Wrappers.<InspirationMessageEntity>lambdaQuery()
                            .eq(InspirationMessageEntity::getSessionId, novel.getInspirationSessionId())
                            .orderByAsc(InspirationMessageEntity::getId));
            if (messages == null || messages.isEmpty()) {
                return;
            }
            List<Map<String, String>> transcript = new ArrayList<>();
            for (InspirationMessageEntity item : messages) {
                if (!"OK".equals(item.getStatus())) {
                    continue;
                }
                if (!"USER".equals(item.getRole()) && !"ASSISTANT".equals(item.getRole())) {
                    continue;
                }
                Map<String, String> turn = new LinkedHashMap<>();
                turn.put("role", "USER".equals(item.getRole()) ? "作者" : "策划");
                turn.put("content", truncate(item.getContent(), MAX_INSPIRATION_TURN_CHARS));
                transcript.add(turn);
                if (transcript.size() >= MAX_INSPIRATION_TURNS) {
                    break;
                }
            }
            if (!transcript.isEmpty()) {
                snapshot.put("inspirationTranscript", transcript);
            }
        } catch (RuntimeException ex) {
            log.warn("[novel-agent] load inspiration transcript failed novelId={}: {}",
                    novel.getId(), ex.toString());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 解析与执行                                                         */
    /* ------------------------------------------------------------------ */

    private AgentReply parseReply(String raw) {
        if (!hasText(raw)) {
            return new AgentReply("", List.of(), false);
        }
        String candidate = LenientJson.extractObject(raw);
        if (candidate.isEmpty()) {
            // 没有 JSON：纯文本回复，无操作，视为完成（无可执行的修改）
            return new AgentReply(raw.trim(), List.of(), true);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                root = objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[novel-agent] parse reply failed, fallback to plain text: {}", ex.getMessage());
                return new AgentReply(raw.trim(), List.of(), true);
            }
        }
        String reply = textOf(root, "reply");
        List<JsonNode> operations = new ArrayList<>();
        JsonNode opsNode = root.path("operations");
        if (opsNode.isArray()) {
            opsNode.forEach(operations::add);
        }
        boolean done = root.path("done").asBoolean(operations.isEmpty());
        return new AgentReply(reply == null ? "" : reply, operations, done);
    }

    private List<AgentOperationVO> executeOperations(Long novelId, List<JsonNode> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }
        log.info("[novel-agent] 开始执行 {} 项操作 novelId={}", operations.size(), novelId);
        List<AgentOperationVO> results = new ArrayList<>();
        // 同一批里「先建后引」很常见（先 CREATE_CHARACTER 再 CREATE_RELATION）：
        // 记录本轮新建的角色名 → id，供后续操作按名字引用，避免模型编造 id。
        Map<String, Long> createdCharacters = new LinkedHashMap<>();
        for (JsonNode node : operations) {
            if (node == null || !node.isObject()) {
                continue;
            }
            String type = textOf(node, "type");
            if (!hasText(type)) {
                log.warn("[novel-agent] 跳过缺少 type 的操作：{}", node);
                continue;
            }
            log.info("[novel-agent] 操作结构：type={} 顶层键=[{}] 参数键=[{}]",
                    type.trim().toUpperCase(), keysOf(node), keysOf(payloadOf(node)));
            results.add(executeOne(novelId, type.trim().toUpperCase(), node, createdCharacters));
        }
        long okCount = results.stream().filter(AgentOperationVO::isSuccess).count();
        log.info("[novel-agent] 操作执行完毕 novelId={}：成功 {} 项，失败 {} 项",
                novelId, okCount, results.size() - okCount);
        return results;
    }

    private AgentOperationVO executeOne(Long novelId, String type, JsonNode node,
                                        Map<String, Long> createdCharacters) {
        Long userId = UserContextHolder.requireUserId();
        AgentOperationVO result = new AgentOperationVO();
        result.setType(type);
        result.setSuccess(true);
        try {
            switch (type) {
                case "UPDATE_NOVEL" -> {
                    JsonNode fields = payloadOf(node);
                    NovelPatch patch = new NovelPatch(
                            textOf(fields, "title"),
                            textOf(fields, "penName"),
                            textOf(fields, "genre"),
                            textList(fields, "tags"),
                            textOf(fields, "intro"),
                            textOf(fields, "status"),
                            null);
                    novelService.update(novelId, patch);
                    result.setTarget("基本信息");
                }
                case "UPDATE_SETTING" -> {
                    String category = textAt(node, "category");
                    String content = textAt(node, "content");
                    updateSetting(novelId, userId, category, content);
                    result.setTarget(settingLabel(category));
                }
                case "CREATE_CHARACTER" -> {
                    CharacterSaveRequest req = toDto(payloadOf(node), CharacterSaveRequest.class);
                    CharacterVO vo = assetService.createCharacter(novelId, req);
                    result.setTarget(vo.getName());
                    setEntityId(result, vo.getId());
                    String createdKey = normalizeName(vo.getName());
                    if (createdKey != null && !createdKey.isEmpty() && vo.getId() != null) {
                        createdCharacters.put(createdKey, vo.getId());
                    }
                }
                case "UPDATE_CHARACTER" -> {
                    Long id = resolveCharacterId(novelId, node, createdCharacters);
                    CharacterSaveRequest req = toDto(payloadOf(node), CharacterSaveRequest.class);
                    CharacterVO vo = assetService.updateCharacter(id, req);
                    result.setTarget(vo.getName());
                    setEntityId(result, vo.getId());
                }
                case "DELETE_CHARACTER" -> {
                    Long id = resolveCharacterId(novelId, node, createdCharacters);
                    CharacterVO before = findCharacter(novelId, id);
                    assetService.deleteCharacter(id);
                    result.setTarget(before == null ? String.valueOf(id) : before.getName());
                }
                case "CREATE_ITEM" -> {
                    ItemRequest req = toDto(payloadOf(node), ItemRequest.class);
                    ItemVO vo = assetService.createItem(novelId, req);
                    result.setTarget(vo.name());
                    setEntityId(result, vo.id());
                }
                case "UPDATE_ITEM" -> {
                    Long id = resolveItemId(novelId, node);
                    ItemRequest req = toDto(payloadOf(node), ItemRequest.class);
                    ItemVO vo = assetService.updateItem(id, req);
                    result.setTarget(vo.name());
                    setEntityId(result, vo.id());
                }
                case "DELETE_ITEM" -> {
                    Long id = resolveItemId(novelId, node);
                    ItemVO before = findItem(novelId, id);
                    assetService.deleteItem(id);
                    result.setTarget(before == null ? String.valueOf(id) : before.name());
                }
                case "CREATE_RELATION" -> {
                    RelationRequest req = toDto(payloadOf(node), RelationRequest.class);
                    req.setFromCharacterId(resolveRelationEnd(novelId, node,
                            new String[]{"fromCharacterId", "fromId", "from", "sourceId", "source"},
                            new String[]{"fromCharacterName", "fromName", "from", "source"},
                            "起始", createdCharacters, true));
                    req.setToCharacterId(resolveRelationEnd(novelId, node,
                            new String[]{"toCharacterId", "toId", "to", "targetId", "target"},
                            new String[]{"toCharacterName", "toName", "to", "target"},
                            "目标", createdCharacters, true));
                    var vo = assetService.createRelation(novelId, req);
                    result.setTarget(vo.fromCharacterName() + "—" + vo.toCharacterName());
                    setEntityId(result, vo.id());
                }
                case "UPDATE_RELATION" -> {
                    Long id = requireId(node, "id");
                    RelationRequest req = toDto(payloadOf(node), RelationRequest.class);
                    // 只改关系类型/描述时模型常常不给两端 → 用现有关系的两端补上
                    CharacterRelationVO existing = findRelation(novelId, id);
                    Long fromId = resolveRelationEnd(novelId, node,
                            new String[]{"fromCharacterId", "fromId", "from", "sourceId", "source"},
                            new String[]{"fromCharacterName", "fromName", "from", "source"},
                            "起始", createdCharacters, false);
                    Long toId = resolveRelationEnd(novelId, node,
                            new String[]{"toCharacterId", "toId", "to", "targetId", "target"},
                            new String[]{"toCharacterName", "toName", "to", "target"},
                            "目标", createdCharacters, false);
                    req.setFromCharacterId(fromId != null ? fromId
                            : (existing == null ? null : existing.fromCharacterId()));
                    req.setToCharacterId(toId != null ? toId
                            : (existing == null ? null : existing.toCharacterId()));
                    var vo = assetService.updateRelation(id, req);
                    result.setTarget(vo.fromCharacterName() + "—" + vo.toCharacterName());
                    setEntityId(result, vo.id());
                }
                case "DELETE_RELATION" -> {
                    Long id = requireId(node, "id");
                    assetService.deleteRelation(id);
                    result.setTarget(String.valueOf(id));
                }
                case "CREATE_FACTION" -> {
                    FactionRequest req = toDto(payloadOf(node), FactionRequest.class);
                    FactionVO vo = factionService.create(novelId, req);
                    result.setTarget(vo.name());
                }
                case "UPDATE_FACTION" -> {
                    Long id = requireId(node, "id");
                    FactionRequest req = toDto(payloadOf(node), FactionRequest.class);
                    FactionVO vo = factionService.update(id, req);
                    result.setTarget(vo.name());
                }
                case "DELETE_FACTION" -> {
                    Long id = requireId(node, "id");
                    factionService.delete(id);
                    result.setTarget(String.valueOf(id));
                }
                case "ADD_FACTION_MEMBER" -> {
                    Long factionId = requireId(node, "factionId");
                    FactionMemberRequest req = new FactionMemberRequest();
                    req.setCharacterId(resolveCharacterId(novelId, node, createdCharacters));
                    req.setPosition(textAt(node, "position"));
                    factionService.addMember(factionId, req);
                    result.setTarget(textAt(node, "position"));
                }
                case "UPDATE_FACTION_MEMBER" -> {
                    Long memberId = requireId(node, "memberId");
                    factionService.updateMember(memberId, textAt(node, "position"));
                    result.setTarget(String.valueOf(memberId));
                }
                case "REMOVE_FACTION_MEMBER" -> {
                    Long memberId = requireId(node, "memberId");
                    factionService.removeMember(memberId);
                    result.setTarget(String.valueOf(memberId));
                }
                case "UPDATE_EVENT" -> {
                    Long id = requireId(node, "id");
                    TimelineRequest req = toDto(payloadOf(node), TimelineRequest.class);
                    TimelineVO vo = assetService.updateTimeline(id, req);
                    result.setTarget(vo.title());
                }
                case "DELETE_EVENT" -> {
                    Long id = requireId(node, "id");
                    assetService.deleteTimeline(id);
                    result.setTarget(String.valueOf(id));
                }
                case "CREATE_MASTER_OUTLINE" -> {
                    MasterOutlineRequest req = toDto(payloadOf(node), MasterOutlineRequest.class);
                    MasterOutlineVO vo = masterOutlineService.create(novelId, req);
                    result.setTarget(vo.title());
                    setEntityId(result, vo.id());
                }
                case "UPDATE_MASTER_OUTLINE" -> {
                    Long id = resolveMasterOutlineId(novelId, node);
                    MasterOutlineRequest req = toDto(payloadOf(node), MasterOutlineRequest.class);
                    MasterOutlineVO vo = masterOutlineService.update(id, req);
                    result.setTarget(vo.title());
                    setEntityId(result, vo.id());
                }
                case "DELETE_MASTER_OUTLINE" -> {
                    Long id = resolveMasterOutlineId(novelId, node);
                    String title = masterOutlineTitle(novelId, id);
                    masterOutlineService.delete(id);
                    result.setTarget(title == null ? String.valueOf(id) : title);
                }
                case "CREATE_CHAPTER" -> {
                    ChapterRequest req = toDto(payloadOf(node), ChapterRequest.class);
                    ChapterVO vo = assetService.createChapter(novelId, req);
                    result.setTarget(vo.title());
                }
                case "UPDATE_CHAPTER" -> {
                    Long id = requireId(node, "id");
                    ChapterRequest req = toDto(payloadOf(node), ChapterRequest.class);
                    ChapterVO vo = assetService.updateChapter(id, req);
                    result.setTarget(vo.title());
                }
                case "DELETE_CHAPTER" -> {
                    Long id = requireId(node, "id");
                    assetService.deleteChapter(id);
                    result.setTarget(String.valueOf(id));
                }
                default -> throw BizException.of(ErrorCode.AGENT_OPERATION_INVALID, "无法识别的操作：" + type);
            }
        } catch (BizException ex) {
            result.setSuccess(false);
            result.setMessage(ex.getMessage());
        } catch (RuntimeException ex) {
            result.setSuccess(false);
            result.setMessage(hasText(ex.getMessage()) ? ex.getMessage() : "执行失败");
        }
        logOperation(result, node);
        return result;
    }

    /** 逐条记录智能助手的数据操作，便于排查「改了什么/哪一步失败」。 */
    private void logOperation(AgentOperationVO result, JsonNode rawNode) {
        String action = labelOfType(result.getType());
        String target = hasText(result.getTarget()) ? result.getTarget() : "（未命名）";
        if (result.isSuccess()) {
            log.info("[novel-agent] 执行成功：{}「{}」{}", action, target,
                    hasText(result.getId()) ? "(id=" + result.getId() + ")" : "");
            return;
        }
        // 参数层（解包 params/fields 后真正喂给业务的数据）单独打印，便于判断模型到底给了哪些字段
        String payload = "";
        if (rawNode != null) {
            payload = payloadOf(rawNode).toString();
            if (payload.length() > 400) {
                payload = payload.substring(0, 400) + "…";
            }
        }
        String raw = rawNode == null ? "" : rawNode.toString();
        if (raw.length() > 900) {
            raw = raw.substring(0, 900) + "…";
        }
        log.warn("[novel-agent] 执行失败：{}「{}」，原因：{}，解析出的参数层：{}，模型原始操作：{}",
                action, target, result.getMessage(), payload, raw);
    }

    /** 把操作失败信息追加到回复末尾，让作者清楚哪几项没改成功。 */
    private String appendFailures(String reply, List<AgentOperationVO> operations) {
        if (operations == null || operations.isEmpty()) {
            return reply;
        }
        long failed = operations.stream().filter(op -> !op.isSuccess()).count();
        if (failed == 0) {
            return reply;
        }
        StringBuilder builder = new StringBuilder(reply == null ? "" : reply);
        builder.append("\n\n（部分修改未执行成功：");
        boolean first = true;
        for (AgentOperationVO op : operations) {
            if (op.isSuccess()) {
                continue;
            }
            if (!first) {
                builder.append("；");
            }
            first = false;
            builder.append(op.getType());
            if (hasText(op.getTarget())) {
                builder.append("「").append(op.getTarget()).append("」");
            }
            if (hasText(op.getMessage())) {
                builder.append("——").append(op.getMessage());
            }
        }
        builder.append("）");
        return builder.toString();
    }

    /* ------------------------------------------------------------------ */
    /* 设定文档细粒度更新                                                 */
    /* ------------------------------------------------------------------ */

    private static final Map<String, String> SETTING_LABELS = Map.of(
            "WORLDVIEW", "世界观",
            "BACKGROUND", "背景设定",
            "CORE_CONFLICT", "核心冲突",
            "HIGHLIGHT", "核心看点",
            "STYLE", "文风基调");

    private void updateSetting(Long novelId, Long userId, String category, String content) {
        String normalized = normalizeCategory(category);
        if (normalized == null) {
            throw BizException.of(ErrorCode.AGENT_OPERATION_INVALID,
                    "无法识别的设定分类：" + category);
        }
        if (!hasText(content)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "设定内容不能为空");
        }
        SettingDocEntity existing = settingDocMapper.selectOne(Wrappers.<SettingDocEntity>lambdaQuery()
                .eq(SettingDocEntity::getNovelId, novelId)
                .eq(SettingDocEntity::getCategory, normalized));
        if (existing != null) {
            existing.setContentMd(content.trim());
            settingDocMapper.updateById(existing);
        } else {
            int sort = settingDocMapper.selectList(Wrappers.<SettingDocEntity>lambdaQuery()
                            .eq(SettingDocEntity::getNovelId, novelId))
                    .stream().mapToInt(s -> s.getSort() == null ? 0 : s.getSort())
                    .max().orElse(0) + 1;
            SettingDocEntity entity = new SettingDocEntity();
            entity.setUserId(userId);
            entity.setNovelId(novelId);
            entity.setCategory(normalized);
            entity.setTitle(SETTING_LABELS.get(normalized));
            entity.setContentMd(content.trim());
            entity.setInjectScope("GLOBAL");
            entity.setSort(sort);
            entity.setDeleted(0);
            entity.setVersion(0);
            settingDocMapper.insert(entity);
        }
    }

    private String normalizeCategory(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String upper = raw.trim().toUpperCase();
        if (SETTING_LABELS.containsKey(upper)) {
            return upper;
        }
        for (Map.Entry<String, String> entry : SETTING_LABELS.entrySet()) {
            if (entry.getValue().equals(raw.trim())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String settingLabel(String category) {
        String normalized = normalizeCategory(category);
        return normalized == null ? category : SETTING_LABELS.get(normalized);
    }

    /* ------------------------------------------------------------------ */
    /* 会话与消息存取                                                     */
    /* ------------------------------------------------------------------ */

    private NovelAgentSessionEntity getOrCreateSession(Long novelId) {
        NovelAgentSessionEntity existing = findSession(novelId);
        if (existing != null) {
            return existing;
        }
        NovelAgentSessionEntity entity = new NovelAgentSessionEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(novelId);
        entity.setMessageCount(0);
        entity.setDeleted(0);
        entity.setVersion(0);
        try {
            sessionMapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException ex) {
            NovelAgentSessionEntity concurrent = findSession(novelId);
            if (concurrent != null) {
                return concurrent;
            }
            throw ex;
        }
    }

    private NovelAgentSessionEntity findSession(Long novelId) {
        return sessionMapper.selectOne(Wrappers.<NovelAgentSessionEntity>lambdaQuery()
                .eq(NovelAgentSessionEntity::getNovelId, novelId)
                .orderByDesc(NovelAgentSessionEntity::getCreatedAt)
                .last("limit 1"));
    }

    private NovelAgentSessionEntity reloadSession(Long id) {
        NovelAgentSessionEntity entity = sessionMapper.selectById(id);
        if (entity == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "助手会话不存在");
        }
        return entity;
    }

    private List<NovelAgentMessageEntity> listMessages(Long sessionId) {
        return messageMapper.selectList(Wrappers.<NovelAgentMessageEntity>lambdaQuery()
                .eq(NovelAgentMessageEntity::getSessionId, sessionId)
                .orderByAsc(NovelAgentMessageEntity::getId));
    }

    private NovelAgentMessageEntity saveMessage(NovelAgentSessionEntity session,
                                                String role,
                                                String content,
                                                List<AgentOperationVO> operations,
                                                String status,
                                                String model,
                                                Integer promptTokens,
                                                Integer completionTokens,
                                                String errorMessage) {
        NovelAgentMessageEntity entity = new NovelAgentMessageEntity();
        entity.setUserId(session.getUserId());
        entity.setNovelId(session.getNovelId());
        entity.setSessionId(session.getId());
        entity.setRole(role);
        entity.setContent(content);
        if (operations != null && !operations.isEmpty()) {
            try {
                entity.setOperations(objectMapper.writeValueAsString(operations));
            } catch (Exception ex) {
                log.warn("[novel-agent] serialize operations failed: {}", ex.getMessage());
            }
        }
        entity.setStatus(status);
        entity.setModel(model);
        entity.setPromptTokens(promptTokens);
        entity.setCompletionTokens(completionTokens);
        entity.setErrorMessage(truncate(errorMessage, 480));
        entity.setDeleted(0);
        entity.setVersion(0);
        messageMapper.insert(entity);
        return entity;
    }

    private void updateSessionStats(NovelAgentSessionEntity session, String title) {
        long okCount = messageMapper.selectCount(Wrappers.<NovelAgentMessageEntity>lambdaQuery()
                .eq(NovelAgentMessageEntity::getSessionId, session.getId())
                .eq(NovelAgentMessageEntity::getStatus, "OK"));
        NovelAgentSessionEntity patch = new NovelAgentSessionEntity();
        patch.setId(session.getId());
        patch.setVersion(session.getVersion());
        patch.setMessageCount((int) okCount);
        patch.setLastMessageAt(LocalDateTime.now());
        if (hasText(title)) {
            patch.setTitle(truncate(title, TITLE_MAX_LENGTH));
        }
        sessionMapper.updateById(patch);
    }

    /* ------------------------------------------------------------------ */
    /* 视图与工具                                                         */
    /* ------------------------------------------------------------------ */

    private AgentSessionVO toSessionVO(NovelAgentSessionEntity session, List<NovelAgentMessageEntity> messages) {
        AgentSessionVO vo = new AgentSessionVO();
        vo.setId(session.getId());
        vo.setNovelId(session.getNovelId());
        vo.setTitle(session.getTitle());
        vo.setMessageCount(session.getMessageCount());
        vo.setLastMessageAt(session.getLastMessageAt());
        if (messages != null && !messages.isEmpty()) {
            vo.setMessages(messages.stream().map(this::toMessageVO).toList());
        }
        return vo;
    }

    private AgentMessageVO toMessageVO(NovelAgentMessageEntity entity) {
        AgentMessageVO vo = new AgentMessageVO();
        vo.setId(entity.getId());
        vo.setRole(entity.getRole());
        vo.setContent(entity.getContent());
        if (hasText(entity.getOperations())) {
            try {
                vo.setOperations(objectMapper.readValue(entity.getOperations(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, AgentOperationVO.class)));
            } catch (Exception ex) {
                log.warn("[novel-agent] parse operations failed: {}", ex.getMessage());
            }
        }
        vo.setStatus(entity.getStatus());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setModel(entity.getModel());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    private void requireNovel(Long novelId) {
        novelService.detail(novelId);
    }

    /**
     * 解析智能助手场景端点，并把单次请求超时提升到足够长。
     *
     * <p>多步 Agent 单轮要读入完整小说快照（上万字符）再输出结构化操作，实测单轮耗时可达
     * 90 秒以上，而默认超时仅 60 秒，导致频繁 {@code request timed out}。这里把超时抬到
     * {@value #AGENT_TIMEOUT_MS} 毫秒，并把单轮输出上限收敛到一个合理值，避免模型长篇输出拖慢。</p>
     */
    private AiEndpoint resolveAgentEndpoint() {
        AiEndpoint base = endpointResolver.resolve(AiScene.NOVEL_AGENT);
        int timeout = Math.max(base.timeoutMs(), AGENT_TIMEOUT_MS);
        Integer maxTokens = (base.maxTokens() == null || base.maxTokens() <= 0)
                ? AGENT_MAX_TOKENS
                : Math.min(base.maxTokens(), AGENT_MAX_TOKENS);
        return new AiEndpoint(base.baseUrl(), base.apiKey(), base.model(), timeout,
                base.extraHeaders(), base.temperature(), maxTokens);
    }

    private CharacterVO findCharacter(Long novelId, Long characterId) {
        for (CharacterVO c : assetService.listCharacters(novelId)) {
            if (c.getId().equals(characterId)) {
                return c;
            }
        }
        return null;
    }

    private ItemVO findItem(Long novelId, Long itemId) {
        for (ItemVO it : assetService.listItems(novelId)) {
            if (it.id().equals(itemId)) {
                return it;
            }
        }
        return null;
    }

    private <T> T toDto(JsonNode node, Class<T> type) {
        try {
            return objectMapper.reader().without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .treeToValue(node, type);
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.AGENT_OPERATION_INVALID, "操作参数不合法：" + ex.getMessage());
        }
    }

    /** 必填 id：在操作对象的各层（外层 / params 包裹层 / fields 内）依次查找，找不到即报错。 */
    private Long requireId(JsonNode node, String... fields) {
        Long id = idOf(node, fields);
        if (id == null) {
            throw BizException.of(ErrorCode.AGENT_OPERATION_INVALID, "缺少 " + fields[0]);
        }
        return id;
    }

    /** 在操作对象各层查找 id（外层 → 解包后的参数层 → 其 fields），找不到返回 null。 */
    private Long idOf(JsonNode node, String... fields) {
        for (JsonNode layer : layersOf(node)) {
            Long id = optionalLong(layer, fields);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /** 读取可能缺失的 id 字段，缺失/非法返回 null 而不抛错。 */
    private Long optionalLong(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.asLong();
            }
            try {
                String text = value.asText("").trim();
                if (!text.isEmpty()) {
                    return Long.parseLong(text);
                }
            } catch (NumberFormatException ignored) {
                // 继续尝试下一个字段
            }
        }
        return null;
    }

    /**
     * 宽松解析角色 id。模型常见三种问题都要兜住：
     * ① 用名字指代角色而漏 id；② 给本轮新建角色编一个不存在的 id；
     * ③ 把 id 写错成别的书的角色。因此 id 必须真实属于本书，否则按名字（含本轮新建）兜底。
     */
    /** 总纲 id 必须属于本书：模型有时会拿别的书的 id，或只给标题。找不到就报错而不是误改。 */
    private Long resolveMasterOutlineId(Long novelId, JsonNode node) {
        List<MasterOutlineVO> all = masterOutlineService.list(novelId);
        Long id = idOf(node, "id", "masterOutlineId", "outlineId", "targetId");
        if (id != null) {
            for (MasterOutlineVO item : all) {
                if (id.equals(item.id())) {
                    return id;
                }
            }
        }
        String title = textAt(node, "title");
        if (!hasText(title)) {
            title = textAt(node, "name");
        }
        String target = hasText(title) ? normalizeName(title) : null;
        if (target != null) {
            for (MasterOutlineVO item : all) {
                if (target.equals(normalizeName(item.title()))) {
                    return item.id();
                }
            }
            for (MasterOutlineVO item : all) {
                String name = normalizeName(item.title());
                if (name != null && !name.isEmpty() && name.contains(target)) {
                    return item.id();
                }
            }
        }
        if (id != null) {
            throw BizException.of(ErrorCode.AGENT_OPERATION_INVALID,
                    "总纲 id " + id + " 不属于本书（也未提供能匹配上的总纲标题）");
        }
        throw BizException.of(ErrorCode.AGENT_OPERATION_INVALID,
                "缺少 id（也未提供总纲标题，无法定位是哪一条总纲）");
    }

    private String masterOutlineTitle(Long novelId, Long id) {
        if (id == null) {
            return null;
        }
        for (MasterOutlineVO item : masterOutlineService.list(novelId)) {
            if (id.equals(item.id())) {
                return item.title();
            }
        }
        return null;
    }

    private Long resolveCharacterId(Long novelId, JsonNode node, Map<String, Long> createdCharacters) {
        List<CharacterVO> all = assetService.listCharacters(novelId);
        Long id = idOf(node, "id", "characterId", "targetId", "charId");
        if (id != null && containsCharacter(all, id)) {
            return id;
        }
        String name = nameOf(node);
        Long byName = matchCharacter(all, createdCharacters, name);
        if (byName != null) {
            return byName;
        }
        if (id != null) {
            throw BizException.of(ErrorCode.AGENT_OPERATION_INVALID,
                    "角色 id " + id + " 不属于本书（也未提供可识别的角色名）");
        }
        throw BizException.of(ErrorCode.AGENT_OPERATION_INVALID,
                "缺少 id（且无法按名字定位角色：" + (name == null ? "未提供名字" : name) + "）");
    }

    /** 解析关系的某一端：id 必须属于本书，否则按角色名（含本轮新建）兜底；required=false 时找不到返回 null。 */
    private Long resolveRelationEnd(Long novelId, JsonNode node,
                                    String[] idKeys, String[] nameKeys,
                                    String sideLabel, Map<String, Long> createdCharacters,
                                    boolean required) {
        List<CharacterVO> all = assetService.listCharacters(novelId);
        Long id = idOf(node, idKeys);
        if (id != null && containsCharacter(all, id)) {
            return id;
        }
        String name = null;
        for (String key : nameKeys) {
            name = textAt(node, key);
            if (name != null) {
                break;
            }
        }
        Long byName = matchCharacter(all, createdCharacters, name);
        if (byName != null) {
            return byName;
        }
        // 最后一层兜底：模型确实给了 id 但该 id 不属于本书、本轮又只新建了一个角色时，
        // 几乎可以确定指的是它（典型场景：CREATE_CHARACTER 之后立刻 CREATE_RELATION，还没有真实 id）。
        // 注意必须要求 id != null：否则「只改关系类型、不传两端」会被误绑到新建角色上。
        if (id != null && createdCharacters != null && createdCharacters.size() == 1) {
            Long only = createdCharacters.values().iterator().next();
            log.warn("[novel-agent] {}角色解析失败（id={} name={}），按本轮唯一新建角色 id={} 兜底",
                    sideLabel, id, name, only);
            return only;
        }
        if (!required) {
            return null;
        }
        if (id != null) {
            throw BizException.of(ErrorCode.AGENT_OPERATION_INVALID,
                    sideLabel + "角色 id " + id + " 不属于本书：请改用 " + sideLabel + "角色名，"
                            + "或使用【上一轮执行结果】里给出的真实 id");
        }
        throw BizException.of(ErrorCode.AGENT_OPERATION_INVALID,
                "缺少" + sideLabel + "角色（请给 id 或角色名）");
    }

    /** 按 id 找本书内已有关系，用于 UPDATE_RELATION 补齐未提供的两端。 */
    private CharacterRelationVO findRelation(Long novelId, Long relationId) {
        if (relationId == null) {
            return null;
        }
        for (CharacterRelationVO vo : assetService.listRelations(novelId)) {
            if (relationId.equals(vo.id())) {
                return vo;
            }
        }
        return null;
    }

    /** 角色 id 是否确实属于本书。 */
    private boolean containsCharacter(List<CharacterVO> all, Long id) {
        return all.stream().anyMatch(c -> id.equals(c.getId()));
    }

    /** 按名字匹配角色：优先本轮新建映射，其次全书精确匹配，最后做双向包含匹配。 */
    private Long matchCharacter(List<CharacterVO> all, Map<String, Long> createdCharacters, String rawName) {
        String wanted = normalizeName(rawName);
        if (wanted == null || wanted.isEmpty()) {
            return null;
        }
        if (createdCharacters != null) {
            Long hit = createdCharacters.get(wanted);
            if (hit != null) {
                return hit;
            }
        }
        for (CharacterVO c : all) {
            if (wanted.equals(normalizeName(c.getName()))) {
                return c.getId();
            }
        }
        for (CharacterVO c : all) {
            String current = normalizeName(c.getName());
            if (current != null && !current.isEmpty()
                    && (current.contains(wanted) || wanted.contains(current))) {
                return c.getId();
            }
        }
        return null;
    }

    /** 把实体 id 记到操作结果上（String，避免 19 位雪花在 JSON 中丢精度）。 */
    private void setEntityId(AgentOperationVO result, Long id) {
        result.setId(id == null ? null : String.valueOf(id));
    }

    /** 宽松解析物品 id，同 resolveCharacterId。 */
    private Long resolveItemId(Long novelId, JsonNode node) {
        Long id = idOf(node, "id", "itemId", "targetId");
        if (id != null) {
            return id;
        }
        String name = nameOf(node);
        String wanted = normalizeName(name);
        if (wanted != null && !wanted.isEmpty()) {
            ItemVO matched = null;
            for (ItemVO it : assetService.listItems(novelId)) {
                String current = normalizeName(it.name());
                if (current == null) {
                    continue;
                }
                if (current.equals(wanted)) {
                    matched = it;
                    break;
                }
                if (matched == null && (current.contains(wanted) || wanted.contains(current))) {
                    matched = it;
                }
            }
            if (matched != null) {
                return matched.id();
            }
        }
        throw BizException.of(ErrorCode.AGENT_OPERATION_INVALID,
                "缺少 id（且无法按名字定位物品：" + (name == null ? "未提供名字" : name) + "）");
    }

    /** 从操作对象取名字：依次在各层（fields → 解包参数层 → 外层）找 name 及其别名。 */
    private String nameOf(JsonNode node) {
        String[] nameKeys = {"name", "characterName", "targetName", "character", "item", "faction", "title"};
        for (JsonNode layer : layersOf(node)) {
            for (String field : nameKeys) {
                String value = textOf(layer, field);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 在操作的各层（fields → 解包参数层 → 外层）找文本字段。 */
    private String textAt(JsonNode node, String field) {
        for (JsonNode layer : layersOf(node)) {
            String value = textOf(layer, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 名字归一化：去掉书名号/引号/空白，便于「沈默」与「「沈默」」匹配。 */
    private String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        return name.replaceAll("[「」『』《》\"'`\\s]", "").trim();
    }

    /** 模型可能用来包裹参数的键（实测出现过 params，其余为常见同义写法）。 */
    private static final String[] WRAPPER_KEYS = {
            "params", "payload", "data", "arguments", "args", "input", "body", "properties"
    };

    /**
     * 剥掉模型多包的一层参数容器（params / payload / arguments…），最多剥两层。
     * 例：{"type":"UPDATE_CHARACTER","params":{"id":"1","fields":{…}}} → {"id":"1","fields":{…}}
     */
    private JsonNode unwrap(JsonNode node) {
        JsonNode current = node;
        for (int depth = 0; depth < 2; depth++) {
            if (current == null || !current.isObject()) {
                return current;
            }
            JsonNode inner = null;
            for (String key : WRAPPER_KEYS) {
                JsonNode candidate = current.path(key);
                if (candidate.isObject() && !candidate.isEmpty()) {
                    inner = candidate;
                    break;
                }
            }
            if (inner == null) {
                return current;
            }
            current = inner;
        }
        return current;
    }

    /** 操作对象的有效层级：fields → 解包后的参数层 → 原始外层（外层最后，避免空壳覆盖真实值）。 */
    private List<JsonNode> layersOf(JsonNode node) {
        List<JsonNode> layers = new ArrayList<>();
        if (node == null) {
            return layers;
        }
        JsonNode base = unwrap(node);
        JsonNode fields = base.path("fields");
        if (fields.isObject() && !fields.isEmpty()) {
            layers.add(fields);
        }
        layers.add(base);
        if (base != node) {
            layers.add(node);
        }
        return layers;
    }

    /**
     * 取操作的参数节点：优先 fields 子对象；模型有时省略 fields 包裹（参数平铺在参数层上），
     * 此时回退到解包后的参数层——toDto 已忽略未知字段，多出的 type/id 会被丢弃。
     */
    private JsonNode payloadOf(JsonNode node) {
        JsonNode base = unwrap(node);
        JsonNode fields = base.path("fields");
        if (fields.isObject() && fields.size() > 0) {
            return fields;
        }
        return base;
    }

    /** 列出对象的一级键，用于日志排查模型返回结构。 */
    private String keysOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "-";
        }
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return String.join(",", keys);
    }

    private String textOf(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private List<String> textList(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        List<String> result = new ArrayList<>();
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isArray()) {
            value.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            });
            return result;
        }
        return null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    /** 累加可能为空的 token 计数。 */
    private Integer addToken(Integer total, Integer delta) {
        if (delta == null) {
            return total;
        }
        int d = Math.max(delta, 0);
        return total == null ? d : total + d;
    }

    /** 操作类型 → 中文名，供反馈日志展示。 */
    /** 回复是否在宣称「已经做了修改」，用于识别只说不做的空操作。 */
    private boolean claimsAction(String reply) {
        if (!hasText(reply)) {
            return false;
        }
        for (String word : ACTION_CLAIM_WORDS) {
            if (reply.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String labelOfType(String type) {
        if (type == null) {
            return "未知操作";
        }
        return switch (type) {
            case "UPDATE_NOVEL" -> "修改基本信息";
            case "UPDATE_SETTING" -> "修改设定";
            case "CREATE_CHARACTER" -> "新增角色";
            case "UPDATE_CHARACTER" -> "修改角色";
            case "DELETE_CHARACTER" -> "删除角色";
            case "CREATE_ITEM" -> "新增物品";
            case "UPDATE_ITEM" -> "修改物品";
            case "DELETE_ITEM" -> "删除物品";
            case "CREATE_RELATION" -> "新增关系";
            case "UPDATE_RELATION" -> "修改关系";
            case "DELETE_RELATION" -> "删除关系";
            case "CREATE_FACTION" -> "新增势力";
            case "UPDATE_FACTION" -> "修改势力";
            case "DELETE_FACTION" -> "删除势力";
            case "ADD_FACTION_MEMBER" -> "添加势力成员";
            case "UPDATE_FACTION_MEMBER" -> "修改势力成员";
            case "REMOVE_FACTION_MEMBER" -> "移除势力成员";
            case "UPDATE_EVENT" -> "修改事件";
            case "DELETE_EVENT" -> "删除事件";
            case "CREATE_MASTER_OUTLINE" -> "新增总纲";
            case "UPDATE_MASTER_OUTLINE" -> "修改总纲";
            case "DELETE_MASTER_OUTLINE" -> "删除总纲";
            case "CREATE_CHAPTER" -> "新增章节";
            case "UPDATE_CHAPTER" -> "修改章节";
            case "DELETE_CHAPTER" -> "删除章节";
            default -> type;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 模型输出：回复文本 + 一组操作 + 是否完成。 */
    private record AgentReply(String reply, List<JsonNode> operations, boolean done) {
    }

    /** 用户主动停止多步执行时抛出，用于中止循环且不落库失败消息。 */
    public static final class AgentCancelledException extends RuntimeException {
        public AgentCancelledException() {
            super("已停止");
        }
    }
}
