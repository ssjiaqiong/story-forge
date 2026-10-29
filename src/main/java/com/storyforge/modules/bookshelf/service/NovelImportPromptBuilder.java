package com.storyforge.modules.bookshelf.service;

import com.storyforge.infra.ai.PromptKit;
import com.storyforge.infra.text.NovelTextParser;

import java.util.List;

/**
 * 整本 TXT 导入的提示词构建。
 *
 * <p>导入是「还原」而不是「改编」：原文已经在库里，AI 要做的是把作者原本心里那份档案补出来，
 * 让续写时有设定可注入、有大纲可对齐。所以这组提示词的第一条纪律是
 * <b>只承认原文有的事实，不改造、不美化、不换名</b>。</p>
 *
 * <p>分四步，与拆书同理按「先事实、后归纳、最后成篇」推进：</p>
 * <ol>
 *   <li>逐段通读：只出事实清单（事件、出场人物、物品、势力、专有名词）；</li>
 *   <li>设定与人物：把清单压成世界观、背景、核心冲突、人物档案与关系；</li>
 *   <li>总纲：一条贯穿全书的主线，按幕切分；</li>
 *   <li>逐章大纲：按章回填 summary，续写时作为上下文。</li>
 * </ol>
 */
public final class NovelImportPromptBuilder {

    private NovelImportPromptBuilder() {
    }

    /** 所有阶段共用的角色设定与纪律。 */
    public static final String SYSTEM = """
            你是资深小说编辑与故事档案师。用户手里只有一部作品的正文（TXT 成稿），
            你的任务是从正文里<b>逆向还原</b>出这本书的创作档案：世界观、背景、人物、势力、物品、总纲与逐章大纲。
            工作纪律：
            1. 只用原文有的事实。人名、地名、组织名、功法名、物品名、称号一律沿用原文写法，一个字都不改，不替换、不美化、不音译。
            2. 原文没写清的，允许按上下文保守推断，但推断必须与原文自洽；推断不出来的宁可不写，不要编造新体系、新人物、新设定。
            3. 你是在做档案，不是写书评：不评价文笔，不写读后感，不提出修改建议，不补写原文没有的情节。
            4. 信息取舍按「对续写有用」排序：人物要写清欲望、缺陷与当前状态；设定要写清规则、代价与边界；势力要写清资源与矛盾。
            5. 输出严格遵循给定 JSON 结构，只输出一个 JSON 对象：不要解释文字，不要 markdown 代码块。
            6. 所有字符串值都是单行纯文本：不出现英文双引号与真实换行；需要引用时用「」。
            """ + PromptKit.ANTI_CLICHE;

    /** 解析失败后的重试后缀。 */
    public static final String RETRY_SUFFIX = """
            上一次输出不是合法 JSON（多半是字符串里混入了英文双引号或换行）。
            这次只输出可被 JSON 解析器直接解析的对象：不解释、不加代码块、值内不出现英文双引号。
            """;

    /* ------------------------------------------------------------------ */
    /* 1. 逐段通读                                                        */
    /* ------------------------------------------------------------------ */

    /** 分段通读：只出事实清单。 */
    public static String segmentPrompt(NovelTextParser.Segment segment, int totalChapters, int totalChars) {
        return """
                下面是一部小说的第 %d/%d 段原文（全书约 %d 章、%d 字）。
                请只做事实登记，不要概括主题，不要评价。
                输出 JSON：
                {
                  "plotPoints": ["本段发生的关键事件，按时间顺序 3~8 条，每条 30 字以内，写清谁做了什么、结果如何"],
                  "characters": [
                    {"name": "原文人名", "identity": "本段中的身份/职务", "traits": "本段体现出的性格或能力，20 字内",
                     "change": "本段中的状态变化（受伤/晋升/死亡/关系变化），没有就空字符串"}
                  ],
                  "items": [{"name": "原文物品名", "category": "法宝/武器/药物/信物/其他", "owner": "持有者", "desc": "作用与来历，30 字内"}],
                  "factions": [{"name": "原文组织名", "category": "门派/家族/朝廷/商会/其他", "desc": "本段体现出的立场与实力，30 字内"}],
                  "terms": ["原文特有的专有名词（地名、功法、境界、称号、货币等）5~20 个，保持原文写法"],
                  "tone": "本段的叙事视角与语言风格，20 字内"
                }
                只登记本段确实出现的内容，没出现的字段给空数组。同一人物在不同段重复出现是正常的，每次都要登记。

                【原文】
                %s
                """.formatted(segment.index(), segment.total(), totalChapters, totalChars, segment.text());
    }

    /* ------------------------------------------------------------------ */
    /* 2. 设定                                                             */
    /* ------------------------------------------------------------------ */

    /** 由通读清单归纳设定与简介。 */
    public static String settingPrompt(String digestText) {
        return """
                下面是对一部小说全文的分段事实登记（按原文顺序）。
                请据此归纳这本书的基础设定。所有专有名词必须与登记中出现的写法完全一致。

                输出 JSON：
                {
                  "title": "书名：原文里有就用原文书名，没有就按内容起一个不超过 20 字的名字",
                  "genre": "题材，2~6 字，如 玄幻 / 仙侠 / 都市 / 悬疑 / 科幻 / 历史",
                  "tags": ["3~5 个具体标签，如 升级流、宗门争斗、旧案重查"],
                  "synopsis": "180~300 字简介：主角是谁 → 想要什么 → 被什么阻碍 → 失败会失去什么 → 目前走到哪一步",
                  "worldview": "世界观 300~600 字：时代与地点、世界规则、力量/职业体系及其境界划分、社会结构与秩序维护者",
                  "background": "背景 300~600 字：故事开始前发生过的关键历史、当前局势、各方矛盾焦点",
                  "coreConflict": "核心冲突 80~150 字：谁与谁为了什么不可调和的矛盾在争夺什么",
                  "highlights": ["3~5 条这本书真正吸引人的点，每条 20 字内"],
                  "style": "文风与叙事视角 60 字内：人称、节奏、语言特点"
                }
                全书事实登记如下：

                %s
                """.formatted(digestText);
    }

    /* ------------------------------------------------------------------ */
    /* 3. 人物、势力与关系                                                 */
    /* ------------------------------------------------------------------ */

    /** 人物档案 + 势力 + 关系。 */
    public static String castPrompt(String digestText, String settingText, int totalChapters) {
        return """
                下面是一部小说的设定摘要与全文事实登记。请建立这本书的人物档案与势力版图。
                人名、组织名必须与登记中的写法一致，不要合并不同的人，也不要把同一个人拆成两个。

                输出 JSON：
                {
                  "factions": [
                    {"name": "组织名", "category": "门派/家族/朝廷/商会/军团/其他", "scope": "活动范围与影响力，20 字内",
                     "remark": "立场、资源、与其他势力的关系，60 字内", "members": ["成员原文人名"]}
                  ],
                  "characters": [
                    {"name": "原文人名", "alias": "别名/称号，没有就空字符串", "gender": "MALE/FEMALE/UNKNOWN",
                     "ageDesc": "如 二十余岁 / 少年 / 中年", "identity": "身份职务，20 字内",
                     "rolePos": "PROTAGONIST/ANTAGONIST/SUPPORTING/MINOR",
                     "faction": "所属组织名，没有就空字符串", "firstChapter": 1,
                     "personality": "性格与行事风格，40 字内",
                     "motivation": "现在最想要什么、为了什么，50 字内",
                     "arc": "至今的经历与当前处境（修为/职位/伤势/关系），60 字内",
                     "profile": "150~250 字小传：出身、目标与欲望、性格缺陷、能力手段、与主角的关系、在全书结束时的状态"}
                  ],
                  "relations": [
                    {"from": "人名", "to": "人名", "type": "师徒/父子/夫妻/盟友/仇敌/同门/上下级",
                     "description": "关系的由来与当前状态，30 字内"}
                  ]
                }
                要求：
                1. characters 覆盖所有有名字、且对剧情有推动的人物，主角必须标 PROTAGONIST，主要反派标 ANTAGONIST；
                   只出现一次的路人不要收进来。全书约 %d 章，主要人物一般 8~30 个。
                2. firstChapter 填这个人第一次出场的章号（按登记顺序估算，从 1 开始）。
                3. relations 只写确实存在的关系，不写「可能是」。
                4. 主角的 profile 要写清「他现在最想做什么、还差什么」，这是续写的起点。

                【设定摘要】
                %s

                【全文事实登记】
                %s
                """.formatted(totalChapters, settingText, digestText);
    }

    /* ------------------------------------------------------------------ */
    /* 4. 物品                                                             */
    /* ------------------------------------------------------------------ */

    public static String itemPrompt(String digestText, String settingText) {
        return """
                下面是一部小说的设定摘要与全文事实登记。请整理这本书里对剧情有作用的物品。
                物品名必须与原文写法一致；只收推动过剧情的物品，日常器物不要收。

                输出 JSON：
                {
                  "items": [
                    {"name": "原文物品名", "category": "法宝/武器/丹药/秘籍/信物/材料/其他",
                     "owner": "当前持有者，可空", "origin": "来历，30 字内",
                     "description": "作用与限制，60 字内", "status": "AVAILABLE/LOST/DESTROYED/CONSUMED"}
                  ]
                }
                数量一般 8~40 件，按对剧情的重要程度排序。

                【设定摘要】
                %s

                【全文事实登记】
                %s
                """.formatted(settingText, digestText);
    }

    /* ------------------------------------------------------------------ */
    /* 5. 总纲                                                            */
    /* ------------------------------------------------------------------ */

    public static String masterOutlinePrompt(String settingText, String castText, String digestText, int totalChapters) {
        return """
                下面是一部小说的设定、人物与全文事实登记。请写一条贯穿全书的总纲，用于后续续写时把握走向。

                输出 JSON：
                {
                  "title": "总纲标题，如「第一卷到第 N 章：从守墓人到执剑人」，20 字内",
                  "logline": "一句话主线，30 字内：谁 + 想要什么 + 被什么挡住",
                  "mainline": "主线推进 300~500 字：按因果链写清故事从开局到目前位置是怎么走到这一步的",
                  "acts": [
                    {"name": "第一幕 起", "range": "第 1-30 章", "content": "这一幕的核心事件、主角状态变化与转折点，150~300 字"}
                  ],
                  "ending": "按现有伏笔判断的结局走向 100~200 字（不是新编，是原文指向）",
                  "openThreads": ["尚未收束的伏笔，每条 30 字内，3~8 条"]
                }
                要求：
                1. acts 按全书约 %d 章切 3~5 幕，range 必须覆盖全书，不要留空档。
                2. 总纲写的是「已经发生的事」，不是「接下来建议写什么」。
                3. openThreads 直接决定续写从哪儿接，必须具体。

                【设定摘要】
                %s

                【人物】
                %s

                【全文事实登记】
                %s
                """.formatted(totalChapters, settingText, castText, digestText);
    }

    /* ------------------------------------------------------------------ */
    /* 6. 逐章大纲                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * 逐章大纲：一次处理一批章节，输入是章号 + 标题 + 正文开头。
     *
     * @param chapters 本批章节，元素为 {chapterNo, title, excerpt}
     */
    public static String chapterOutlinePrompt(String masterText, List<ChapterBrief> chapters) {
        StringBuilder list = new StringBuilder();
        for (ChapterBrief chapter : chapters) {
            list.append("第 ").append(chapter.chapterNo()).append(" 章 ").append(chapter.title()).append('\n');
            if (chapter.excerpt() != null && !chapter.excerpt().isBlank()) {
                list.append("  正文开头：").append(chapter.excerpt()).append('\n');
            }
        }
        return """
                下面是一部小说的总纲，以及其中一批章节的标题与正文开头。
                请为这批章节各写一段「章节大纲」，用来在续写时交代这一章讲了什么。

                输出 JSON：
                {
                  "chapters": [
                    {"chapterNo": 1, "summary": "80~120 字：本章谁做了什么、起了什么冲突、结果如何、结尾留了什么钩子"}
                  ]
                }
                要求：
                1. chapterNo 必须与输入完全对应，不得漏章、不得多写、不得改号。
                2. summary 只写这一章实际发生的事，不评价、不写「本章展现了……」这类空话。
                3. 人物用原文名字；不引入本章没出现的人物。

                【总纲】
                %s

                【本批章节】
                %s
                """.formatted(masterText, list);
    }

    /**
     * 逐章大纲的输入。
     *
     * @param chapterNo 章号
     * @param title     章标题
     * @param excerpt   正文开头（用于判断本章内容）
     */
    public record ChapterBrief(int chapterNo, String title, String excerpt) {
    }
}
