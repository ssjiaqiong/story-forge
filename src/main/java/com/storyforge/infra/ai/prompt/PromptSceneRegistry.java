package com.storyforge.infra.ai.prompt;

import com.storyforge.infra.ai.PromptKit;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.bookshelf.dto.ConsistencyIssueType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词模板的注册表。
 *
 * <p>凡是<b>在这个列表里出现</b>的场景，就意味着它的提示词已抽离、可在界面上编辑；
 * 不出现的场景（如灵感对话、拆书改编）各有自己的维护入口或仍是系统内置常量，
 * 不会出现在提示词模板页，避免用户改了却不生效。</p>
 *
 * <p>已接入 16 个场景：正文、大纲、蓝图四段、建书追问、角色、物品、事件、章节分析、状态同步、按分析修改、
 * 封面、小说要素抽取。其中蓝图四段各自独立，既可以分别定制提示词，也允许在「场景绑定」里给不同段配不同模型。</p>
 *
 * <p>变量分两层：<b>表单变量</b>（书名、题材、创意等）只在该场景有值时才有内容；
 * <b>上下文变量</b>（五类设定、创作约束、本章人物等）本来由代码拼在 user 消息里，
 * 模板一旦引用，user 段里同名的那一块就不再输出，同一份信息不会发两遍。</p>
 *
 * <p>完整提示词 = 用户可编辑的指令段（入库）+ 锁定的输出格式契约（常量）。
 * 契约里只放「怎么输出」，创作纪律（去陈词、题材校准）属于指令段，用户可以改。</p>
 */
@Component
public class PromptSceneRegistry {

    /** 通用 JSON 纪律：原有场景沿用，不带字段示例 */
    private static final String JSON_CONTRACT_LOCKED = """
            【输出格式（系统统一，不可修改）】
            只输出一个 JSON 对象：不要解释、不要寒暄、不要 markdown 代码块、不要任何前后缀。
            字段名、层级与类型按本场景的系统约定固定，不得增删字段、不得改名、不得改成数组包裹。
            所有字符串值都是单行纯文本：不出现英文双引号与真实换行；需要引用时用「」。
            没有内容的字段给空字符串或空数组，不要输出 null。
            """;

    /** 纯文本契约：正文类场景 */
    private static final String TEXT_CONTRACT_LOCKED = """
            【输出格式（系统统一，不可修改）】
            只输出正文纯文本：不要输出章节标题、序号、作者注释或创作说明。
            不要使用 markdown 标记；段落之间用空行分隔。
            """;

    /** 带字段示例的 JSON 契约：蓝图四段与要素抽取，字段各不相同 */
    private static String jsonContract(String shape) {
        return """
                【输出格式（系统统一，不可修改）】
                %s
                只输出一个 JSON 对象：不要解释、不要寒暄、不要 markdown 代码块、不要任何前后缀。
                字段名、层级与类型固定，不得增删字段、不得改名、不得改成数组包裹。
                所有字符串值都是单行纯文本：不出现英文双引号与真实换行；需要引用时用「」。
                没有内容的字段给空字符串或空数组，不要输出 null。
                """.formatted(shape);
    }

    /**
     * 创作约束：建书前 AI 追问、作者作答后落库的那几十个字。
     *
     * <p>它贯穿此后所有创作场景（蓝图、总纲、大纲、正文、角色、物品、事件），
     * 是「这个作者到底要写什么」的唯一答案，因此变量名统一叫 {@code constraints}。</p>
     */
    private static final List<PromptSceneSpec.PromptVariable> CONSTRAINT_VARIABLES = List.of(
            new PromptSceneSpec.PromptVariable("constraints", "创作约束",
                    "主角设定：被逐出宗门的杂役，身上还背着师门旧案的嫌疑\n主角目标：找回失传的残卷，替师父翻案"));

    /**
     * 正文生成可用变量。
     *
     * <p>其中 {@code chapterSummary} 起到 {@code searchResults} 的是「上下文变量」：
     * 它们本来由代码拼在 user 消息里，模板一旦引用，对应块就不再重复注入
     * （见 {@code PromptTemplateService.usedVariables}）。</p>
     */
    private static final List<PromptSceneSpec.PromptVariable> BODY_VARIABLES = vars(List.of(
            new PromptSceneSpec.PromptVariable("novelTitle", "书名", "长夜将明"),
            new PromptSceneSpec.PromptVariable("genre", "题材", "都市悬疑"),
            new PromptSceneSpec.PromptVariable("tags", "标签", "升级流、群像、旧书修复"),
            new PromptSceneSpec.PromptVariable("synopsis", "作品简介", "一个旧书修复师在拆迁老宅里捡到一本会改字的账册，每改一个字，城里就有一个人消失"),
            new PromptSceneSpec.PromptVariable("chapterNo", "本章章号", "12"),
            new PromptSceneSpec.PromptVariable("chapterTitle", "本章标题", "雨夜的第七个电话"),
            new PromptSceneSpec.PromptVariable("targetWords", "预计汉字数", "2500"),
            new PromptSceneSpec.PromptVariable("chapterSummary", "本章大纲", "主角在暴雨夜接到第七通无声电话，追到拆迁区，撞见账册上被划掉的名字"),
            new PromptSceneSpec.PromptVariable("worldview", "世界观", "灵气按「账」结算，欠账未还者会被逐出城，账册是唯一的凭证"),
            new PromptSceneSpec.PromptVariable("background", "背景", "三十年前那场大火烧掉半座城，官方说是意外，老住户都不信"),
            new PromptSceneSpec.PromptVariable("coreConflict", "核心冲突", "主角要查清父亲死因，唯一的线索握在仇家手里，动它就要先毁了自家招牌"),
            new PromptSceneSpec.PromptVariable("highlights", "核心看点", "旧书改字、账册索命、拆迁队里的老江湖"),
            new PromptSceneSpec.PromptVariable("style", "文风基调", "第三人称限知，冷硬短句，市井口语"),
            new PromptSceneSpec.PromptVariable("characters", "本章参与人物", "沈砚（旧书修复师，欠账三笔，习惯用袖口擦手）、老周（拆迁队长，嘴上骂人，暗中递消息）"),
            new PromptSceneSpec.PromptVariable("factions", "相关势力", "修缮行会（掌握全城账册，规矩大于人情）、拆迁办（只听上面的话，怕档案被翻）"),
            new PromptSceneSpec.PromptVariable("items", "本章相关物品", "缺角的账册（封面烫金已磨平，末页有父亲的字）、铜柄裁纸刀（母亲留下的，刀口崩过）"),
            new PromptSceneSpec.PromptVariable("events", "本章关联事件", "（2019 年）老周第一次见到账册上出现自己的名字"),
            new PromptSceneSpec.PromptVariable("previousSummary", "前情摘要", "上一章主角答应替老周查账，代价是替修缮行会修一本禁书"),
            new PromptSceneSpec.PromptVariable("authorRequirement", "作者要求", "这一章节奏要快，只写追查过程，不要插入回忆"),
            new PromptSceneSpec.PromptVariable("searchResults", "联网检索资料", "旧式线装账册常用「鱼鳞装」，翻页时需从右向左")),
            CONSTRAINT_VARIABLES);

    /**
     * 五类设定：各创作场景通用。
     *
     * <p>模板引用其中一类，user 段里同名的那一段就不再注入——同一份设定不会发两遍。</p>
     */
    private static final List<PromptSceneSpec.PromptVariable> SETTING_VARIABLES = List.of(
            new PromptSceneSpec.PromptVariable("worldview", "世界观", "灵气按「账」结算，欠账未还者会被逐出城"),
            new PromptSceneSpec.PromptVariable("background", "背景", "三十年前那场大火烧掉半座城，官方说是意外"),
            new PromptSceneSpec.PromptVariable("coreConflict", "核心冲突", "主角要查清父亲死因，唯一的线索握在仇家手里"),
            new PromptSceneSpec.PromptVariable("highlights", "核心看点", "旧书改字、账册索命、拆迁队里的老江湖"),
            new PromptSceneSpec.PromptVariable("style", "文风基调", "第三人称限知，冷硬短句，市井口语"));

    /** 作品级字段：标签与简介 */
    private static final List<PromptSceneSpec.PromptVariable> NOVEL_EXTRA_VARIABLES = List.of(
            new PromptSceneSpec.PromptVariable("tags", "标签", "升级流、群像、旧书修复"),
            new PromptSceneSpec.PromptVariable("synopsis", "作品简介", "一个旧书修复师在拆迁老宅里捡到一本会改字的账册"));

    /** 作者在生成弹窗里填的要求：模板引用后，user 段的「作者要求」块不再重复 */
    private static final List<PromptSceneSpec.PromptVariable> AUTHOR_VARIABLES = List.of(
            new PromptSceneSpec.PromptVariable("authorRequirement", "作者要求", "这一章节奏要快，只写追查过程"));

    private static final List<PromptSceneSpec.PromptVariable> OUTLINE_VARIABLES = vars(
            List.of(new PromptSceneSpec.PromptVariable("novelTitle", "书名", "长夜将明"),
                    new PromptSceneSpec.PromptVariable("genre", "题材", "都市悬疑"),
                    new PromptSceneSpec.PromptVariable("chapterCount", "本次生成章数", "5")),
            NOVEL_EXTRA_VARIABLES, SETTING_VARIABLES, AUTHOR_VARIABLES, CONSTRAINT_VARIABLES);

    private static final List<PromptSceneSpec.PromptVariable> MASTER_OUTLINE_VARIABLES = vars(
            List.of(new PromptSceneSpec.PromptVariable("novelTitle", "书名", "长夜将明"),
                    new PromptSceneSpec.PromptVariable("genre", "题材", "都市悬疑"),
                    new PromptSceneSpec.PromptVariable("count", "本次生成条数", "4"),
                    new PromptSceneSpec.PromptVariable("coverage", "覆盖范围", "全书主线")),
            NOVEL_EXTRA_VARIABLES, SETTING_VARIABLES, AUTHOR_VARIABLES, CONSTRAINT_VARIABLES);

    /**
     * 蓝图四段：书名题材可能还没定，渲染时缺省为「这本书 / 通用」。
     *
     * <p>{@code idea/style/targetLength/protagonistHint} 就是建书表单里填的内容；
     * {@code worldview/background/synopsis} 是前面几段已经生成的结果，供后续段引用；
     * {@code constraints} 是建书前追问得到、随请求一起传进来的创作约束。</p>
     */
    private static final List<PromptSceneSpec.PromptVariable> BLUEPRINT_VARIABLES = vars(List.of(
            new PromptSceneSpec.PromptVariable("novelTitle", "书名", "长夜将明"),
            new PromptSceneSpec.PromptVariable("genre", "题材", "都市悬疑"),
            new PromptSceneSpec.PromptVariable("tags", "标签", "升级流、群像"),
            new PromptSceneSpec.PromptVariable("idea", "核心创意", "一个被宗门逐出的废柴，靠一本会说话的旧书重新踏上修行路"),
            new PromptSceneSpec.PromptVariable("style", "风格偏好", "热血爽文，节奏快"),
            new PromptSceneSpec.PromptVariable("targetLength", "目标篇幅", "长篇 120 万字"),
            new PromptSceneSpec.PromptVariable("protagonistHint", "主角偏好", "主角是女性，偏冷静克制"),
            new PromptSceneSpec.PromptVariable("worldview", "已生成的世界观", "前一段产出的世界观全文"),
            new PromptSceneSpec.PromptVariable("background", "已生成的背景", "前一段产出的背景历史"),
            new PromptSceneSpec.PromptVariable("synopsis", "已生成的简介", "已确定的简介文本")),
            CONSTRAINT_VARIABLES);

    /** 建书前的信息追问：只拿得到表单里填的那几项 */
    private static final List<PromptSceneSpec.PromptVariable> QUESTION_VARIABLES = List.of(
            new PromptSceneSpec.PromptVariable("novelTitle", "书名", "长夜将明"),
            new PromptSceneSpec.PromptVariable("genre", "题材", "都市悬疑"),
            new PromptSceneSpec.PromptVariable("tags", "标签", "升级流、群像"),
            new PromptSceneSpec.PromptVariable("idea", "核心创意", "一个被宗门逐出的废柴，靠一本会说话的旧书重新踏上修行路"),
            new PromptSceneSpec.PromptVariable("style", "风格偏好", "热血爽文，节奏快"),
            new PromptSceneSpec.PromptVariable("targetLength", "目标篇幅", "长篇 120 万字"),
            new PromptSceneSpec.PromptVariable("protagonistHint", "主角偏好", "主角是女性，偏冷静克制"));

    /** 角色 / 物品 / 势力：书名、题材、本次数量，外加作品字段、设定与作者要求 */
    private static final List<PromptSceneSpec.PromptVariable> ASSET_VARIABLES = vars(
            List.of(new PromptSceneSpec.PromptVariable("novelTitle", "书名", "长夜将明"),
                    new PromptSceneSpec.PromptVariable("genre", "题材", "都市悬疑"),
                    new PromptSceneSpec.PromptVariable("count", "本次生成数量", "3")),
            NOVEL_EXTRA_VARIABLES, SETTING_VARIABLES, AUTHOR_VARIABLES, CONSTRAINT_VARIABLES);

    /** 事件补货只拿得到作品信息（这个服务不加载设定详情，所以不登记设定变量） */
    private static final List<PromptSceneSpec.PromptVariable> EVENT_VARIABLES = vars(
            List.of(new PromptSceneSpec.PromptVariable("novelTitle", "书名", "长夜将明"),
                    new PromptSceneSpec.PromptVariable("genre", "题材", "都市悬疑"),
                    new PromptSceneSpec.PromptVariable("count", "本次生成数量", "3")),
            NOVEL_EXTRA_VARIABLES, AUTHOR_VARIABLES, CONSTRAINT_VARIABLES);

    @SafeVarargs
    private static List<PromptSceneSpec.PromptVariable> vars(
            List<PromptSceneSpec.PromptVariable>... parts) {
        List<PromptSceneSpec.PromptVariable> result = new ArrayList<>();
        for (List<PromptSceneSpec.PromptVariable> part : parts) {
            result.addAll(part);
        }
        return List.copyOf(result);
    }

    private static final List<PromptSceneSpec.PromptVariable> ANALYSIS_VARIABLES = List.of(
            new PromptSceneSpec.PromptVariable("novelTitle", "书名", "长夜将明"),
            new PromptSceneSpec.PromptVariable("chapterNo", "本章章号", "12"),
            new PromptSceneSpec.PromptVariable("targetWords", "目标汉字数", "2500"));

    private static final List<PromptSceneSpec.PromptVariable> SYNC_VARIABLES = List.of(
            new PromptSceneSpec.PromptVariable("novelTitle", "书名", "长夜将明"),
            new PromptSceneSpec.PromptVariable("chapterNo", "本章章号", "12"));

    /**
     * 一致性检查：范围本身就写在提示词里（最近十章 / 单章 / 全书某一块），
     * 帮助模型判断「这些信息够不够下结论」。
     */
    private static final List<PromptSceneSpec.PromptVariable> CONSISTENCY_VARIABLES = List.of(
            new PromptSceneSpec.PromptVariable("novelTitle", "书名", "长夜将明"),
            new PromptSceneSpec.PromptVariable("genre", "题材", "都市悬疑"),
            new PromptSceneSpec.PromptVariable("scopeLabel", "本次检查范围", "第 8~12 章（共 5 章）"));

    /** 封面生成：书名、题材、标签、风格描述、文字要求、书名/作者文字 */
    private static final List<PromptSceneSpec.PromptVariable> COVER_VARIABLES = List.of(
            new PromptSceneSpec.PromptVariable("novelTitle", "书名", "长夜将明"),
            new PromptSceneSpec.PromptVariable("genre", "题材", "都市悬疑"),
            new PromptSceneSpec.PromptVariable("tags", "标签", "升级流、群像"),
            new PromptSceneSpec.PromptVariable("stylePrompt", "风格描述", "日系动漫风格，干净利落的线稿"),
            new PromptSceneSpec.PromptVariable("textRequirement", "文字要求", "画面下方预留留白，用于后期叠放书名与作者名"),
            new PromptSceneSpec.PromptVariable("titleText", "书名文字", "长夜将明"),
            new PromptSceneSpec.PromptVariable("authorText", "作者名文字", "某作者"));

    private final Map<String, PromptSceneSpec> specs = new LinkedHashMap<>();

    public PromptSceneRegistry() {
        // ---------------- 正文与大纲 ----------------

        register(AiScene.BODY_GEN, """
                你是《{{novelTitle}}》的作者，一位专注于{{genre}}类型的网络小说家。
                你就是这本书的作者，一位擅长写有画面感、节奏紧、对话自然的网络小说家。
                只写本章正文，本章是第 {{chapterNo}} 章，预计 {{targetWords}} 个汉字（允许上下浮动一成）。
                文中人物、物品、势力必须与给定的设定一致：名字不改、能力不越界、归属不搞错。
                不要替读者总结情绪，把情绪落到动作、物件与对话上；不要写「命运的齿轮」「空气仿佛凝固」这类句子。
                """, TEXT_CONTRACT_LOCKED, BODY_VARIABLES,
                "正文生成：这只影响「怎么写」，输出仍是纯文本正文，格式由系统锁定。模板里引用世界观、作者要求这类上下文变量后，user 消息里同名段落不再重复注入。");

        register(AiScene.OUTLINE_GEN, """
                你是经验丰富的小说编剧兼大纲策划，擅长把设定拆成有因果、有钩子、可直接开写的章节。
                你不是在写故事梗概，而是在给每一章安排「谁在什么处境下、想做什么、被什么挡住、付出什么代价」。
                你必须尊重既有世界观与人物动机：人物的能力有边界，势力有规矩，事件有前因。
                这次为《{{novelTitle}}》（{{genre}}）生成 {{chapterCount}} 章大纲。
                不要写「xxx与xxx」式的对称标题；每章都要留下一个能勾住下一章的问题。
                """, JSON_CONTRACT_LOCKED, OUTLINE_VARIABLES,
                "大纲生成：控制章节安排的思路与口吻，JSON 字段由系统锁定。模板里引用世界观、作者要求这类上下文变量后，user 消息里同名段落不再重复注入。");

        register(AiScene.MASTER_OUTLINE, """
                你是资深小说总策划，负责在写任何章节之前，先把整本书的剧情走向拆成若干「总纲」。
                总纲不是章节大纲：一条总纲覆盖若干章，只写这一段故事的局面、变化与结果，不写章节标题、不写人物档案。
                这次为《{{novelTitle}}》（{{genre}}）规划 {{count}} 条总纲，覆盖范围：{{coverage}}。

                【写法要求】
                1. 每条总纲 200~400 字，按「开局局面 → 主要矛盾 → 关键转折 → 这一段结束时的结果」推进。
                2. 条与条之间必须因果相接：上一条的结果是下一条的起因，不能各写各的。
                3. 每条只保留一条主线推动力和一个明确的变化，不要把所有伏笔都塞进一条。
                4. 必须尊重已有世界观、人物动机与事件前因，不新增与设定冲突的规则。
                5. 最后一条要指向全书的高潮或结局方向，不要停在原地。
                6. 标题 4~14 字，写这一段的核心事件，不要写成「第一章」这类序号。
                """ + PromptKit.ANTI_CLICHE,
                jsonContract("""
                        {"outlines": [{"title": "这一段的核心事件", "content": "200~400 字：开局局面 → 主要矛盾 → 关键转折 → 结束时的结果"}]}"""),
                MASTER_OUTLINE_VARIABLES,
                "总纲生成：决定全书剧情如何分段推进，JSON 字段由系统锁定。模板里引用世界观、作者要求这类上下文变量后，user 消息里同名段落不再重复注入。");

        // ---------------- 蓝图四段 ----------------

        register(AiScene.BLUEPRINT_WORLDVIEW, """
                你是资深世界观设计师，擅长为网络小说构建能直接支撑剧情运转的世界，而不是名词清单。
                这次为《{{novelTitle}}》（{{genre}}）设计世界观。

                【设计任务】
                只设计世界观，不写剧情梗概，不写人物小传。

                【设计方法】
                1. 先立一条核心规则（力量、技术、制度或禁忌），再写它带来的收益、代价和失效条件。
                2. 规则必须能解释普通人的日常：吃什么、靠什么谋生、怕什么、如何向上爬。
                3. 至少写 3 个具体的生活细节（物价、工序、称呼、节日、刑罚、交通），让世界可触摸。
                4. 势力之间要有资源或理念冲突，不要只列名字；写清谁控制什么、谁不服谁。
                5. 给故事留一个当前无法解决的矛盾，不要写成完美自洽的百科。
                """ + PromptKit.GENRE_GUIDE + PromptKit.ANTI_CLICHE,
                jsonContract("""
                        {"worldview": "600~900 字：核心规则 → 代价与边界 → 社会结构 → 势力格局 → 3 个日常细节 → 当前暗流"}"""),
                BLUEPRINT_VARIABLES,
                "蓝图第一段：只产出世界观，后续三段会基于它继续推演。引用表单字段与前段结果后，user 段同名内容不再重复。");

        register(AiScene.BLUEPRINT_BACKGROUND, """
                你是资深小说策划，负责把世界观落到具体历史与当下局势。
                这次为《{{novelTitle}}》（{{genre}}）写背景设定。

                【设计任务】
                只写背景设定：这个世界是怎么走到故事开始这一天的。

                【设计方法】
                1. 写 3 个关键历史事件，每个都按「起因 → 经过 → 后果」写；后果必须改变规则、势力或普通人的生活。
                2. 至少有一个事件是官方说法与民间记忆不一致的，为后文留出真相空间。
                3. 写清故事开始时各方在争什么、谁占上风、谁在暗中蓄力。
                4. 留一条尚未爆发的压力（税、灾、继承、边境、技术失控等），它会在故事里被点燃。
                5. 历史必须能解释世界观里的规则是怎么形成的，不能另起一套设定。
                """ + PromptKit.ANTI_CLICHE,
                jsonContract("""
                        {"background": "400~600 字：三个关键事件 → 当前局势 → 一条尚未爆发的压力"}"""),
                BLUEPRINT_VARIABLES,
                "蓝图第二段：输入已生成的世界观，只产出背景历史。");

        register(AiScene.BLUEPRINT_CONFLICT, """
                你是资深小说策划，负责提炼卖点与贯穿全书的矛盾。
                这次为《{{novelTitle}}》（{{genre}}）写简介、核心冲突、看点与文风。

                【设计任务】
                基于已确定的世界观与背景来写，不要另起设定。

                【设计方法】
                1. 简介必须让读者在 30 秒内知道：主角是谁、想要什么、谁挡着他、失败会失去什么。
                2. 核心冲突写清双方的目标与底线：为什么双方都不能退让，冲突会如何升级。
                3. 看点要具体到「可想象的画面或桥段」，不要写「节奏紧凑」「设定新颖」。
                4. 文风写清人称、叙述距离与语言质感（冷硬/市井/克制/热烈），不要只写「轻松幽默」。
                5. 简介结尾留一个具体钩子，不要用反问句。
                """ + PromptKit.ANTI_CLICHE,
                jsonContract("""
                        {
                          "synopsis": "250~400 字：主角 → 欲望 → 阻碍 → 代价 → 钩子",
                          "coreConflict": "150~250 字：双方目标、底线、升级方式、最终要回答的问题",
                          "highlights": ["3~5 条核心看点，每条不超过 20 字，必须是具体桥段或设定"],
                          "style": "人称 + 叙述距离 + 语言质感，不超过 40 字",
                          "genre": "题材，2~6 字，如 玄幻 / 都市 / 悬疑",
                          "tags": ["3~6 个标签，每个不超过 8 字"]
                        }"""),
                BLUEPRINT_VARIABLES,
                "蓝图第三段：产出简介、核心冲突、看点、文风与题材标签。");

        register(AiScene.BLUEPRINT_CHARACTER, """
                你是资深角色设定师，负责让角色能自己推动剧情。
                这次为《{{novelTitle}}》（{{genre}}）设计人物档案。

                【设计任务】
                只设计人物档案：1 个主角 + 3~6 个与主线强相关的角色。

                【每个角色必须有】
                - 一个具体的欲望（想要什么，具体到可执行）
                - 一个致命缺陷（会在关键时刻让他付出代价）
                - 一个秘密或羞于承认的部分
                - 一个说话或行事习惯（可写进性格、外貌）

                【主角字段要求】
                appearance 60~100 字：一个能被记住的外形特征 + 当前的身体或穿着状态，不要笼统夸好看。
                personality 80~120 字：主导性格 + 一个矛盾点 + 压力下的反应。
                motivation 80~120 字：表面想要什么 / 真正缺什么 / 最怕失去什么。
                goldenFinger 80~120 字：能力 + 代价 + 限制 + 暴露后的风险；没有金手指就写「无」并说明靠什么立足。
                arc 80~120 字：初始相信的谎言 → 被什么打破 → 最终做出什么选择。

                【配角要求】
                - 至少 1 个主要对手（ANTAGONIST），他的动机必须让读者理解，不能是纯恶工具人。
                - 每个配角都要在主线上有一个不可替代的功能（提供信息、制造阻碍、承担代价、见证转变）。
                - 关系通过身份、动机和利益体现，不要只写「好友」「仇敌」。
                """ + PromptKit.ANTI_CLICHE,
                jsonContract("""
                        {
                          "protagonist": {
                            "name": "姓名", "identity": "身份职位", "gender": "性别", "ageDesc": "年龄描述",
                            "appearance": "外貌特征，60~100 字", "personality": "性格，80~120 字",
                            "motivation": "核心动机与欲望，80~120 字", "goldenFinger": "金手指或独有优势，80~120 字",
                            "arc": "成长弧线，80~120 字"
                          },
                          "characters": [
                            {"name": "姓名，不要与主角重复", "rolePos": "SUPPORTING 或 ANTAGONIST",
                             "identity": "身份职位", "gender": "性别", "ageDesc": "年龄描述",
                             "personality": "性格，60~100 字，含一个矛盾点或说话习惯",
                             "motivation": "动机，60~100 字，含欲望与顾忌"}
                          ]
                        }
                        characters 给 3~6 个，至少 1 个 ANTAGONIST。"""),
                BLUEPRINT_VARIABLES,
                "蓝图第四段：产出主角与配角档案，关系在写书后另行生成。");

        // ---------------- 建书前的信息追问 ----------------

        register(AiScene.BLUEPRINT_QUESTIONS, """
                你是{{genre}}小说的策划编辑。作者刚给了一个创意，你要在动笔设计设定之前，判断他还缺哪些关键信息。
                核心创意：{{idea}}
                风格偏好：{{style}}
                目标篇幅：{{targetLength}}
                主角偏好：{{protagonistHint}}

                【问什么】
                1. 只问「不问就没法往下设计」的信息；创意里已经写清的方向，一律不要重复问。
                2. 问题必须落到具体选择上，让作者一句话就能答完，不要问「你想要什么样的世界观」这种大题。
                3. 优先问会改变剧情走向的：主角的起点、真正的欲望、谁在挡路、规则怎么运转、开局从哪一天写起。
                4. 最多 4 个问题；确实不缺信息就给空数组，不要为了凑数硬问。
                5. 每个问题配一句示例答案（hint），让作者知道该写到什么颗粒度。

                【key 只能取下列固定值】
                protagonist 主角身份与起点 / goal 主角的目标与动机 / antagonist 主要对手与压力
                power 力量或规则体系 / world 世界舞台与地理 / opening 开局场景
                ending 结局倾向 / tone 情绪基调与爽点 / taboo 禁区与不要出现的内容
                """,
                jsonContract("""
                        {"questions": [{"key": "protagonist", "question": "主角出场时是什么身份？已经被逼到什么份上了？", "hint": "如：被逐出宗门的杂役，背着师门旧案的嫌疑，靠替人抄经混饭"}]}
                        questions 给 0~4 个；key 必须是上面列出的固定值，不得自创；同一个方向只问一次。"""),
                QUESTION_VARIABLES,
                "建书前的信息追问：AI 判断你的创意还缺哪些关键信息，问清后作为「创作约束」注入此后所有创作场景。"
                        + "这里只决定怎么问，输出格式由系统锁定。");

        // ---------------- 角色 / 物品 / 事件 ----------------

        register(AiScene.CHARACTER_BUILD, """
                你是资深角色设定师兼关系设计师，擅长让角色自己制造冲突、推动剧情，而不是等着作者安排。
                这次为《{{novelTitle}}》（{{genre}}）设计 {{count}} 个角色，并给出他们之间的关系。
                人物要能挂进本书的世界观与势力格局。
                不要写「天赋异禀」「深不可测」「倾国倾城」这类标签；能力要写边界与代价，人物要写欲望与缺陷。
                每个角色都要能在故事里制造麻烦：有想要的东西，也有不肯让的东西。
                """, JSON_CONTRACT_LOCKED, ASSET_VARIABLES,
                "角色生成：控制角色的设计取向，JSON 字段由系统锁定。模板里引用世界观、作者要求这类上下文变量后，user 消息里同名段落不再重复注入。");

        register(AiScene.ITEM_BUILD, """
                你是资深网络小说设定师，擅长设计能推动剧情的物品、线索与信物。
                本次为《{{novelTitle}}》（{{genre}}）设计 {{count}} 个物品。
                每个物品必须写清两部分：description 是「物品描述」（外貌形态、材质、尺寸、磨损、特殊特征与象征意义），
                origin 是「说明」（来历、经手人、关联的关键事件或秘密）。两部分内容不得重复。
                物品要能带出故事：持有它的人会因此得到什么、失去什么。
                """, JSON_CONTRACT_LOCKED, ASSET_VARIABLES,
                "物品生成：控制物品的设计取向，JSON 字段由系统锁定。模板里引用世界观、作者要求这类上下文变量后，user 消息里同名段落不再重复注入。");

        register(AiScene.EVENT_SUPPLY, """
                你是资深剧情策划，负责设计能被直接写进正文的事件，而不是一句话梗概。
                这次为《{{novelTitle}}》（{{genre}}）补充 {{count}} 个事件，要能接上现有角色与事件池里的前因。
                每个事件都必须有目标、阻碍、转折、代价与钩子；参与角色必须有各自立场，不能只是站桩。
                不要用「突然」「竟然」「原来」当转折；转折必须有前文铺垫。
                """, JSON_CONTRACT_LOCKED, EVENT_VARIABLES,
                "事件补货：控制事件的设计取向，JSON 字段由系统锁定。模板里引用世界观、作者要求这类上下文变量后，user 消息里同名段落不再重复注入。");

        // ---------------- 分析与质检 ----------------

        register(AiScene.ANALYSIS_GEN, """
                你是小说平台的质检编辑，负责审第 {{chapterNo}} 章的三件事：是否写到了大纲要求的地方、
                文风是否像 AI 批量生成、字数是否接近 {{targetWords}} 个汉字。
                《{{novelTitle}}》的读者是愿意为细节付费的人，所以「哪一段写得假」比「整体不错」更有价值。
                你只输出 JSON，不写客套话。
                - 先找证据再给分：每条结论都要能落到正文的具体内容上。
                - 大纲贴合看「该发生的事有没有发生、该出场的人有没有做事」，不看辞藻是否漂亮；
                  超出大纲但有增色的内容算加分项，写进 hits。
                - AI 率分数含义：0 = 一眼就是 AI，50 = 人机混杂，100 = 完全像人写。
                  评分依据：套话与模板化描写、句式和段落的均匀程度、情绪是否直给、细节是否具体可感、
                  对话是否像真人说话、是否有作者独有的偏执与毛边。
                - 给定的【本地统计】是硬证据，你的评分不得与它明显矛盾：套话密度高、句首重复多，就不能给高分。
                - 结论要具体：不要写「可以更生动」这类空话，要指出改哪一段、怎么改。
                """, JSON_CONTRACT_LOCKED, ANALYSIS_VARIABLES,
                "章节分析（评分）：控制审稿口味与严格程度，JSON 字段由系统锁定。");

        register(AiScene.SYNC_EXTRACT, """
                你是网络小说的责任编辑，作者刚写完《{{novelTitle}}》的第 {{chapterNo}} 章，你负责做「设定同步」。
                你只做事实登记，不做文学评价：正文没写的一律不记，宁可漏记也不要推测。
                【判定口径】
                - 只有正文里实际发生的变化才记：回忆、梦境、假设、他人转述、计划中的事都不算。
                - 人物姓名必须与「已有角色」逐字一致；只有真正首次出场的陌生人才放进 newCharacters。
                - 只被提到名字、没有台词也没有动作的人，不算出场，不算新人物。
                - 一个字段一条记录：同一角色死了又换了身份，就写两条。
                - 属性与背包要写「本章结束后的值」，不要写变化量；正文没给具体数值就不要猜。
                """, JSON_CONTRACT_LOCKED, SYNC_VARIABLES,
                "状态同步（事实抽取）：控制登记口径，JSON 字段由系统锁定。");

        register(AiScene.CONSISTENCY_CHECK, """
                你是《{{novelTitle}}》的连贯性编辑（continuity editor），负责在正文里找出自相矛盾的地方。
                这次的检查范围：{{scopeLabel}}。

                【只报硬矛盾】
                1. 只报读者能一眼看出不对的地方：人物状态、物品归属、时间顺序、称呼身份、伏笔是否回收。
                   文风、节奏、写得好不好一律不评价，那是章节分析的事。
                2. 每条都必须给出 evidence（正文原句，不超过 40 字）或能核对的具体依据；
                   给不出证据的宁可不报——猜出来的问题会毁掉这个页面的可信度。
                3. 下面这些<b>已由系统的规则层查出，不要重复报</b>：
                   人物在已死亡 / 已失踪之后出场；物品已摧毁 / 遗失 / 消耗却仍有数量；
                   人物首次登场章早于实际出场章；废弃事件挂进了有正文的章节；
                   章号重复、断号、排序值异常；关联指向已删除的人物或事件。
                4. 只根据给定的正文与档案判断，不要推测没写到的剧情，不要把「还没交代」当成矛盾。
                5. 最多 8 条，按严重度从重到轻排；确实没发现就给空数组，不要为凑数报「可能」「也许」。
                """,
                jsonContract("""
                        {"issues": [{"type": "CHARACTER_STATE", "severity": "P0", "chapterNo": 12,
                                     "title": "老周在第 8 章已死，这一章又在说话",
                                     "detail": "第 8 章写他坠崖，这里却让他递烟，除非补一句回忆交代",
                                     "evidence": "老周把烟头摁在墙上，说这事没完",
                                     "suggestion": "换成他人递烟，或开头补一句「他想起老周从前说过」"}]}
                        type 只能取：
                        """ + ConsistencyIssueType.aiWhitelist() + """
                        ；severity 只能取 P0 / P1 / P2；chapterNo 填问题所在章号，全书级问题填 0。
                        issues 最多 8 条，没有就给 []。
                        """),
                CONSISTENCY_VARIABLES,
                "一致性检查（AI 层）：控制挑刺的口味与严格程度；输出字段与类型白名单由系统锁定。"
                        + "规则层已经查出的硬伤不要重复报，系统会自动合并两层结果。");

        register(AiScene.CHAPTER_REVISE, """
                你是《{{novelTitle}}》的改稿编辑，负责按审稿意见修改第 {{chapterNo}} 章。
                你改的是别人已经写完的稿子，不是重写一遍。

                【改稿纪律】
                1. 只改被点出的地方，其余原样保留：人物姓名、身份、关系、世界规则、时间线、章节标题都不许动。
                2. 保留作者的语言习惯与叙事视角：不换人称、不换语气，不把市井口语改成书面语。
                3. 该补的补到位：补情节、补细节、补因果；补了就要挤掉空转的段落，不要只往里塞字。
                4. 不新增主要人物与新设定；非补不可时，只能是不影响后续走向的小角色与背景细节。
                5. 删掉套话与模板化描写，把情绪落到动作、物件、对话上；句长可以参差，人物说话可以带口癖。
                6. 篇幅控制在 {{targetWords}} 个汉字上下，不为凑字注水，也不为省事砍掉有效情节。
                """ + PromptKit.PROSE_RULES,
                jsonContract("""
                        {
                          "content": "修改后的完整正文，从第一段到最后一段，段落之间用空行分隔",
                          "notes": ["3~6 条改动说明，每条不超过 30 字，如：补写药铺查封的动静，让主角处境更紧"]
                        }"""),
                ANALYSIS_VARIABLES,
                "按分析修改：控制改稿的分寸与文风；输出是完整正文 + 改动说明，JSON 字段由系统锁定。");

        // ---------------- 封面 ----------------

        register(AiScene.COVER_IMAGE, """
                你是资深的小说封面设计师。为《{{novelTitle}}》设计一幅竖版书籍封面插画，单幅完整作品。
                题材：{{genre}}。标签：{{tags}}。
                【风格】{{stylePrompt}}
                【画面】从故事里挑一个最有张力的瞬间取景：主体人物或核心意象清晰可辨，构图有视觉中心，
                电影级光影、真实材质、服饰与道具细节丰富；色调贴合题材气质，画面整体统一干净。
                【文字要求】{{textRequirement}}
                """,
                """
                【硬性要求（系统统一，不可修改）】
                画面内不得出现任何文字、汉字、字母、数字、段落、对话气泡、简介文字块、字幕、标注、印章、水印、平台logo或排版边框。
                人物解剖结构正确，不要多手多指、五官扭曲；不要拼接多幅小图或分镜；不要出现畸形手指、糊脸、低清晰度。
                """,
                COVER_VARIABLES,
                "封面生成：控制画面主体、风格与文字布局；禁字、去水印、解剖正确等硬性要求由系统锁定。");

        // ---------------- 创作助手 ----------------

        register(AiScene.NOVEL_EXTRACT, """
                你是小说要素抽取器。输入是作者与小说策划助手的对话记录。
                你的任务是从对话中提炼「已经确定或可以合理推断」的小说要素，不是重新创作一个故事。

                【抽取原则】
                1. 作者明确说过的内容优先，尤其是书名、主角身份、核心冲突、篇幅。
                2. 作者否定过的方向不要写回来；对话里出现过的专有名词保持一致。
                3. 信息不足时可以合理推断，但推断必须与已有内容自洽，不要引入全新的设定体系。
                4. 简介要写成一个能吸引读者的故事梗概，不是要素罗列。
                """,
                jsonContract("""
                        {
                          "title": "书名，不超过 30 字；优先选对话里出现过的书名，没有就起一个符合题材的",
                          "genre": "题材，2~6 字，如 玄幻 / 都市 / 悬疑 / 科幻",
                          "tags": ["3~5 个具体标签，如 升级流、群像、旧书修复"],
                          "synopsis": "180~300 字：主角是谁 → 想要什么 → 被什么阻碍 → 失败会失去什么 → 主线走向，结尾留钩子",
                          "protagonist": "主角一句话设定：身份 + 欲望 + 一个弱点",
                          "estimatedChapters": 120
                        }
                        所有字段都必须有值，不要留空；estimatedChapters 按对话里的篇幅与节奏推断，范围 30~600。"""),
                List.of(),
                "小说要素抽取：把灵感对话整理成建书要素，用于「一键创建小说」。");
    }

    private void register(AiScene scene, String defaultContent, String outputContract,
                          List<PromptSceneSpec.PromptVariable> variables, String note) {
        specs.put(scene.name(), new PromptSceneSpec(scene.name(), scene.getLabel(), scene.getGroup(),
                true, defaultContent.stripIndent().strip(), outputContract.strip(), variables, note));
    }

    /**
     * 可编辑场景列表，按 {@link AiScene} 的枚举顺序返回。
     *
     * <p>没有注册的场景不会出现——它们要么有自己的维护入口（灵感技能），
     * 要么提示词仍是系统内置常量，露在页面上只会让用户白改。</p>
     */
    public List<PromptSceneSpec> list() {
        List<PromptSceneSpec> result = new ArrayList<>();
        for (AiScene scene : AiScene.ordered()) {
            PromptSceneSpec spec = specs.get(scene.name());
            if (spec != null) {
                result.add(spec);
            }
        }
        return result;
    }

    /** 未接入模板的场景返回 {@code null}，由调用方决定是报错还是保留内置提示词。 */
    public PromptSceneSpec get(String scene) {
        AiScene target = AiScene.of(scene);
        return target == null ? null : specs.get(target.name());
    }
}
