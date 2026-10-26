package com.storyforge.modules.bookshelf.service;

import com.storyforge.infra.ai.PromptKit;
import com.storyforge.infra.text.NovelTextParser;

import java.util.List;

/**
 * 拆书提示词构建。
 *
 * <p>拆书分两个阶段，提示词也分两套：</p>
 * <ol>
 *   <li><b>拆</b>：逐段做事实提炼 → 汇成主线脉络。这一阶段允许出现原文人名与专有名词，
 *       因为要准确描述人物功能；但禁写评论、禁补情节。</li>
 *   <li><b>改</b>：按改编程度重建世界观、人物、势力、大纲。这一阶段必须脱敏：
 *       原文人名与专有名词进入「禁用清单」，新作不得出现任何可识别的对应物。</li>
 * </ol>
 */
public final class DeconstructPromptBuilder {

    private DeconstructPromptBuilder() {
    }

    /** 拆解阶段与改编阶段共用的角色设定。 */
    public static final String SYSTEM = """
            你是资深小说拆解师与改编策划，做过多年编辑与故事医生。
            你的工作分两步：先把一部完成的作品拆到骨架（人物功能、因果链、结构手法），再按用户要求的改编幅度重建一部新作。
            工作纪律：
            1. 拆解时只提炼事实：不评价文笔，不猜测作者意图，不补写原文没有的情节；材料不足时写明「材料未覆盖」，不要编造。
            2. 改编时守住底线：新作是「同一故事内核的另一次讲述」，不是同人续写。人物、地名、组织名、功法/技术名、称号一律重新命名，不得沿用原文任何专有名词，不得谐音，不得使用与原文连续 15 字以上相同的表述。
            3. 一切设定都要能支撑剧情运转：规则写清代价与边界，人物写清欲望与缺陷，势力写清资源与矛盾。
            4. 输出严格遵循给定 JSON 结构，只输出一个 JSON 对象，不要解释文字，不要 markdown 代码块。
            5. 所有字符串值都是单行纯文本：不出现英文双引号与真实换行；需要引用时用「」。
            """ + PromptKit.ANTI_CLICHE;

    /** 解析失败后的重试后缀。 */
    public static final String RETRY_SUFFIX = """
            上一次输出不是合法 JSON（多半是字符串里混入了英文双引号或换行）。
            这次只输出可被 JSON 解析器直接解析的对象：不解释、不加代码块、值内不出现英文双引号。
            """;

    /**
     * 第一段：分段事实提炼。
     */
    public static String digestPrompt(NovelTextParser.Segment segment, Meta meta) {
        StringBuilder builder = new StringBuilder();
        builder.append("【本次任务】\n")
                .append("这是一部投稿小说的第 ").append(segment.index()).append("/").append(segment.total())
                .append(" 段，原文约 ").append(meta.chars()).append(" 汉字");
        if (meta.chapters() > 0) {
            builder.append("、识别到 ").append(meta.chapters()).append(" 章");
        }
        builder.append("；本段约 ").append(segment.chars()).append(" 汉字。\n");
        if (segment.sampled()) {
            builder.append("注意：原文段数超过单次通读上限，本段属于抽样段落（头、中、尾均匀取样），"
                    + "段落之间可能不连续，提炼时不要假设与上一段直接衔接。\n");
        }
        builder.append("只做事实提炼，供后续拆解使用。不要评价，不要续写，不要总结主题。\n\n");

        builder.append("【提炼要求】\n")
                .append("1. summary：按时间顺序写清「谁在什么处境下做了什么、结果如何」，500~700 字；保留因果链与代价，不要写成读后感。\n")
                .append("2. characters：本段有名有姓、有行动的角色，最多 10 个。name 照抄原文姓名（不要音译或改名）；"
                        + "role 写身份与在故事中的功能；traits 写性格与动机，各不超过 40 字。\n")
                .append("3. events：本段推动剧情的关键事件，最多 8 条；每条一句，写清「谁 → 做了什么 → 造成什么变化」。\n")
                .append("4. settings：本段出现的专有名词，最多 15 个：地名、组织/门派、功法/技术/体系、关键物品、称号。\n")
                .append("5. 如果本段只是过渡章节，summary 也要写清它把哪两条线接了起来。\n\n");

        builder.append("【输出格式】\n")
                .append("{\n")
                .append("  \"summary\": \"500~700 字\",\n")
                .append("  \"characters\": [{\"name\": \"原文姓名\", \"role\": \"身份与功能\", \"traits\": \"性格与动机\"}],\n")
                .append("  \"events\": [\"关键事件，一句一条\"],\n")
                .append("  \"settings\": [\"专有名词\"]\n")
                .append("}\n\n");

        builder.append("【本段正文开始】\n").append(segment.text()).append("\n【本段正文结束】");
        return builder.toString();
    }

    /**
     * 第二段：主线脉络。
     */
    public static String spinePrompt(String digestText, Meta meta) {
        StringBuilder builder = new StringBuilder();
        builder.append("【原作材料】\n")
                .append("文件名：").append(meta.fileName()).append("\n")
                .append("规模：约 ").append(meta.chars()).append(" 汉字");
        if (meta.chapters() > 0) {
            builder.append("，识别到 ").append(meta.chapters()).append(" 章");
        }
        builder.append("\n通读覆盖：").append(meta.segmentCount()).append(" 段")
                .append(meta.sampled() ? "（原文较长，按头/中/尾抽样通读，非逐字全文）" : "（覆盖全文）")
                .append("\n\n【分段纪要】\n").append(digestText).append("\n\n");

        builder.append("【本次任务】\n")
                .append("提炼这部作品的主线脉络。这是给作者看的拆解报告，允许出现原文姓名与专有名词，"
                        + "但不要大段复述情节，重点是结构与因果。\n\n");

        builder.append("【提炼要求】\n")
                .append("1. logline：一句话说清故事内核，40 字以内；不得出现任何原文人名与专有名词，要能脱离原作独立成立。\n")
                .append("2. theme：作品真正在讨论的问题（欲望、代价、选择），一句话；不要写成题材标签。\n")
                .append("3. mainline：400~600 字，按「开局处境 → 第一次升级 → 中点反转 → 最低谷 → 终局选择」推进；"
                        + "每一步都要写清主角失去了什么、得到了什么，以及推动它的具体原因。\n")
                .append("4. acts：2~4 幕，每幕写清覆盖范围与这一幕改变了什么。\n")
                .append("5. turns：3~5 个关键转折，每条写清「原本以为 → 实际发生 → 后果」。\n")
                .append("6. characterFunctions：3~8 个，按功能位归类（主角/对手/引路人/镜像/代价承担者）；"
                        + "originalRole 写原文姓名或身份，role 写他在主线里不可替代的作用。\n")
                .append("7. ending：结局形态，以及它回答了前面提出的哪个问题。\n\n");

        builder.append("【输出格式】\n")
                .append("{\n")
                .append("  \"logline\": \"一句话内核\",\n")
                .append("  \"theme\": \"作品讨论的问题\",\n")
                .append("  \"mainline\": \"400~600 字\",\n")
                .append("  \"acts\": [{\"name\": \"第一幕\", \"range\": \"前四分之一\", \"content\": \"120~200 字\"}],\n")
                .append("  \"turns\": [\"原本以为 → 实际发生 → 后果\"],\n")
                .append("  \"characterFunctions\": [{\"function\": \"主角\", \"originalRole\": \"原文姓名或身份\", \"role\": \"作用\"}],\n")
                .append("  \"ending\": \"80~150 字\"\n")
                .append("}");
        return builder.toString();
    }

    /**
     * 第三段：世界观 / 背景 / 书名 / 简介 / 冲突 / 看点 / 文风。
     */
    public static String settingPrompt(String spineText, String digestText, String bannedNames, Meta meta) {
        StringBuilder builder = new StringBuilder();
        builder.append("【原作骨架】\n").append(spineText).append("\n\n");
        builder.append("【原作细节纪要】\n").append(digestText).append("\n\n");
        if (!bannedNames.isBlank()) {
            builder.append("【禁用清单】\n以下原文专有名词一律不得出现在新作中（不得谐音、不得拆分重组）：\n")
                    .append(bannedNames).append("\n\n");
        }

        builder.append("【改编程度】").append(meta.levelLabel()).append("\n").append(levelGuide(meta.level())).append("\n\n");
        if (meta.direction() != null && !meta.direction().isBlank()) {
            builder.append("【作者指定的改编方向】（必须落实，优先级高于改编幅度的默认策略）\n")
                    .append(meta.direction().trim()).append("\n\n");
        } else {
            builder.append("【改编方向】作者未指定，请按改编幅度自行选择一个新的背景坐标系，不要沿用原文的时代与地域。\n\n");
        }

        builder.append("【本次任务】\n")
                .append("只输出新作的「书名 + 题材 + 标签 + 简介 + 世界观 + 背景 + 核心冲突 + 核心看点 + 文风」。"
                        + "不写人物小传，不写章节大纲。\n\n");

        builder.append("【写作要求】\n")
                .append("1. title：书名不超过 20 字，不能是原文书名的改写或后缀变化。\n")
                .append("2. genre：2~6 字题材；改编幅度为 HEAVY / REBUILD 时允许更换题材，但必须与世界观一致。\n")
                .append("3. tags：3~6 个标签，每个不超过 8 字，必须是读者可检索的具体元素，不要写「精彩」「必看」。\n")
                .append("4. synopsis：250~400 字。让读者在 30 秒内知道：主角是谁 → 想要什么 → 谁挡着他 → 失败会失去什么 → 结尾钩子。"
                        + "不要写成背景介绍，不要用反问句收尾。\n")
                .append("5. worldview：600~900 字。先立一条核心规则（力量、技术、制度或禁忌），再写它的收益、代价与失效条件；"
                        + "至少 3 个可触摸的日常细节（物价、工序、称呼、节日、刑罚、交通）；写清势力之间在争什么；"
                        + "最后留一个当前无法解决的矛盾。\n")
                .append("6. background：400~600 字。3 个关键历史事件，每个按「起因 → 经过 → 后果」写，后果必须改变规则或势力格局；"
                        + "至少一个事件的官方说法与民间记忆不一致；结尾留一条尚未爆发的压力。\n")
                .append("7. coreConflict：150~250 字。双方目标、底线、为什么都不能退让、冲突如何升级、最终要回答什么问题。\n")
                .append("8. highlights：3~5 条，每条不超过 20 字，必须是可想象的桥段或设定，不是形容词。\n")
                .append("9. style：人称 + 叙述距离 + 语言质感，不超过 40 字。\n\n");

        builder.append("【输出格式】\n")
                .append("{\n")
                .append("  \"title\": \"新书名\",\n")
                .append("  \"genre\": \"题材\",\n")
                .append("  \"tags\": [\"标签\"],\n")
                .append("  \"synopsis\": \"250~400 字\",\n")
                .append("  \"worldview\": \"600~900 字\",\n")
                .append("  \"background\": \"400~600 字\",\n")
                .append("  \"coreConflict\": \"150~250 字\",\n")
                .append("  \"highlights\": [\"不超过 20 字\"],\n")
                .append("  \"style\": \"不超过 40 字\"\n")
                .append("}");
        return builder.toString();
    }

    /**
     * 第四段：人物 + 关系 + 势力（含脱敏要求）。
     */
    public static String characterPrompt(String settingText, String spineText, String bannedNames, Meta meta) {
        StringBuilder builder = new StringBuilder();
        builder.append("【新作设定】\n").append(settingText).append("\n\n");
        builder.append("【原作人物功能】（只借功能位，不借姓名与身份）\n").append(spineText).append("\n\n");
        if (!bannedNames.isBlank()) {
            builder.append("【禁用姓名】\n以下原文人名一律不得使用，也不得使用同音字、近音字、增减一字或改姓不改名：\n")
                    .append(bannedNames).append("\n\n");
        }

        builder.append("【改编程度】").append(meta.levelLabel()).append("\n").append(levelGuide(meta.level())).append("\n\n");

        builder.append("【本次任务】\n")
                .append("为这部新作设计人物、人物关系与势力。必须彻底脱敏：\n")
                .append("- 姓名全新；外貌不写可识别特征（胎记、伤疤位置、招牌服饰），只写当前状态与气质。\n")
                .append("- 身份换到新世界的制度里（宗门→公司/学院/军团/行会，按新世界观决定），不要直接照搬原文职位。\n")
                .append("- 人物数量：主角 1 个 + 配角/反派 5~8 个；至少 1 个 ANTAGONIST，其动机必须让读者理解。\n\n");

        builder.append("【字段要求】\n")
                .append("主角：appearance 60~100 字；personality 80~120 字（主导性格 + 一个矛盾点 + 压力下的反应）；"
                        + "motivation 80~120 字（表面想要什么 / 真正缺什么 / 最怕失去什么）；"
                        + "goldenFinger 80~120 字（能力 + 代价 + 限制 + 暴露风险，没有就写「无」并说明靠什么立足）；"
                        + "arc 80~120 字（初始相信的谎言 → 被什么打破 → 最终选择）。\n")
                .append("配角：personality 60~100 字，含一个矛盾点或说话习惯；motivation 60~100 字，含欲望与顾忌。\n")
                .append("relations：8~14 条。from / to 必须是本次输出的人物姓名；type 只能取 "
                        + "MASTER_APPRENTICE / FAMILY / SPOUSE / LOVER / FRIEND / ALLY / ENEMY / RIVAL / COLLEAGUE / OTHER；"
                        + "description 写清这段关系当前的张力（不超过 40 字），不要只写「好友」「仇敌」。\n")
                .append("factions：3~5 个。name 全新；category 取 公司 / 帮派 / 国家 / 组织 / 宗门 / 其他 之一；"
                        + "status 取 ACTIVE / DORMANT / DESTROYED / DISBANDED；scope 写营业或管辖范围；"
                        + "remark 写它与主线的关系（40~80 字）；members 2~5 人，character 必须是本次人物，position 是自由文本职位。\n\n");

        builder.append("【输出格式】\n")
                .append("{\n")
                .append("  \"protagonist\": {\"name\": \"\", \"identity\": \"\", \"gender\": \"\", \"ageDesc\": \"\", "
                        + "\"appearance\": \"\", \"personality\": \"\", \"motivation\": \"\", \"goldenFinger\": \"\", \"arc\": \"\"},\n")
                .append("  \"characters\": [{\"name\": \"\", \"rolePos\": \"SUPPORTING 或 ANTAGONIST\", \"identity\": \"\", "
                        + "\"gender\": \"\", \"ageDesc\": \"\", \"personality\": \"\", \"motivation\": \"\"}],\n")
                .append("  \"relations\": [{\"from\": \"\", \"to\": \"\", \"type\": \"\", \"description\": \"\"}],\n")
                .append("  \"factions\": [{\"name\": \"\", \"category\": \"\", \"status\": \"\", \"scope\": \"\", \"remark\": \"\", "
                        + "\"members\": [{\"character\": \"\", \"position\": \"\"}]}]\n")
                .append("}");
        return builder.toString();
    }

    /**
     * 第五段：事件池 + 章节大纲。
     */
    public static String outlinePrompt(String materialText, Meta meta) {
        StringBuilder builder = new StringBuilder();
        builder.append("【新作全部设定】\n").append(materialText).append("\n\n");

        builder.append("【本次任务】\n")
                .append("排出全书的事件池与章节大纲。只写标题与摘要，不写正文。\n\n");

        builder.append("【事件池要求】\n")
                .append("1. 8~15 个事件，覆盖开局、升级、中点反转、最低谷、终局。\n")
                .append("2. 每个事件写清：谁触发的、改变了什么、代价是什么；不要写成「主角获得机缘」这种空话。\n")
                .append("3. eventTime 用故事内时间（如「第一年冬」「新历三年」），状态取 UNUSED。\n")
                .append("4. participants 必须是设定里出现的人物姓名，1~4 人。\n\n");

        builder.append("【章节大纲要求】\n")
                .append("1. 共 ").append(meta.chapterTarget()).append(" 章，按每 8~12 章一卷自动分卷；volumeNo 从 1 开始，chapterNo 全书连续编号。\n")
                .append("2. 每章 summary 150~220 字：谁在什么处境下、想做什么、被什么挡住、付出什么代价、结尾留什么钩子。\n")
                .append("3. 每章 characters 3~6 人，必须是设定里的人物；events 0~3 条，必须是本次事件池里的标题原文。\n")
                .append("4. 章节之间要有因果推进：前章的结果必须是后章的起因；不要写成互不相干的单元剧。\n")
                .append("5. 第 1 章必须在前 500 字内给出主角的处境与麻烦；最后一章必须收束主线冲突，不要留未解释的核心悬念。\n")
                .append("6. 标题不超过 20 字，不要用「第一章」这类占位标题，也不要照抄原文标题。\n\n");

        builder.append("【输出格式】\n")
                .append("{\n")
                .append("  \"events\": [{\"title\": \"\", \"eventTime\": \"\", \"description\": \"60~120 字\", "
                        + "\"status\": \"UNUSED\", \"participants\": [\"人物姓名\"]}],\n")
                .append("  \"chapters\": [{\"volumeNo\": 1, \"volumeTitle\": \"\", \"chapterNo\": 1, \"title\": \"\", "
                        + "\"summary\": \"150~220 字\", \"characters\": [\"人物姓名\"], \"events\": [\"事件标题\"]}]\n")
                .append("}");
        return builder.toString();
    }

    /** 四档改编程度的行为约定，直接进提示词，决定输出差异。 */
    public static String levelGuide(String level) {
        return switch (level == null ? "MEDIUM" : level) {
            case "LIGHT" -> """
                    【轻度改编】只换皮，不动骨架。
                    - 世界观规则、时代背景、地理格局、势力结构基本保留，但所有专有名词必须替换（人名、地名、组织名、功法/技术名、称号）。
                    - 主线走向、关键转折的顺序与功能保持不变；只调整表达方式、细节与次要桥段。
                    - 人物数量与功能配置保持不变，身份层级不变，只换姓名与外貌描述。
                    - 允许保留原文的叙事节奏与卷章结构。""";
            case "HEAVY" -> """
                    【重度改编】换骨，留内核。
                    - 只保留故事内核（核心矛盾 + 情感命题 + 结构手法），重新设计世界观、背景、势力格局与人物谱系。
                    - 可以更换题材、时代、地域与叙事重心；主线走向允许大幅改变，关键转折必须重新发明，不复用原文桥段。
                    - 人物全部重新创作，只保留功能位（主角/对手/引路人/镜像/代价承担者）。
                    - 结尾可以选择与原作不同的收束方式，但必须回应同一情感命题。""";
            case "REBUILD" -> """
                    【完全重构】只借方法，不借内容。
                    - 只借用可迁移的叙事母题与结构手法（例如「以一次追查揭开家族旧案」这种抽象结构），其余全部原创。
                    - 世界观、背景、势力、人物、事件、冲突起因与解决方式都不得与原文有可识别的对应关系；允许更换题材与类型。
                    - 读者不能从新作中认出原作的角色、地名、组织或桥段。
                    - 输出必须是另一本书：同样的命题，完全不同的讲述。""";
            default -> """
                    【中度改编】换背景，守骨架。
                    - 保留主线骨架与核心冲突类型，但背景的时代、地域、行业或力量体系必须换一层外壳（例如宗门换成公司、王朝换成殖民城市、修真换成生物改造）。
                    - 世界观规则重新设计，不能直接照搬原文的力量体系与制度，但功能要能对应。
                    - 可合并、拆分或替换次要人物，调整部分情节顺序与动机；中点反转与结局方向必须保住。
                    - 人物数量可与原文接近，但姓名、身份、关系网络全部重建。""";
        };
    }

    /** 把分段纪要拼成给下一段模型看的材料。 */
    public static String joinDigests(List<String> digests, int maxChars) {
        StringBuilder builder = new StringBuilder();
        for (String item : digests) {
            if (item == null || item.isBlank()) {
                continue;
            }
            builder.append(item.trim()).append("\n\n");
            if (builder.length() >= maxChars) {
                break;
            }
        }
        String text = builder.toString().trim();
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n…（材料过长，已截断）";
    }

    /**
     * 单次拆书的元信息。
     *
     * @param fileName      上传文件名
     * @param chars         原文汉字数
     * @param chapters      识别章节数
     * @param level         改编程度编码
     * @param levelLabel    改编程度中文名
     * @param direction     背景改编方向
     * @param chapterTarget 目标章节数
     * @param segmentCount  通读段数
     * @param sampled       是否抽样
     */
    public record Meta(String fileName,
                       int chars,
                       int chapters,
                       String level,
                       String levelLabel,
                       String direction,
                       int chapterTarget,
                       int segmentCount,
                       boolean sampled) {
    }
}
