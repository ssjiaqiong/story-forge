package com.storyforge.infra.text;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 AI 味检测。
 *
 * <p>模型的评分容易被长文本里的修辞糊弄，这里先算一组确定性信号：套话密度、句首重复、
 * 段落节奏均匀度、对话占比、平均句长。这些信号会注入提示词作为证据，并对模型评分设置上限
 * （例如每千字套话 8 次以上，无论模型给多少分，最终不超过 45），避免「AI 互评放水」。</p>
 */
@Component
public class AiFlavorMeter {

    /** 常见 AI 腔与套话，命中越多越可疑 */
    private static final List<String> CLICHES = List.of(
            "空气仿佛凝固", "嘴角微微上扬", "嘴角勾起", "眼中闪过一丝", "深邃的眼眸", "眸光微闪",
            "心中一震", "心头一紧", "不由得", "不禁", "下意识", "若有所思", "意味深长",
            "不容置疑", "显而易见", "与此同时", "值得一提的是", "总而言之", "综上所述",
            "命运的齿轮", "这一切都", "沉默片刻", "良久", "缓缓开口", "淡淡地说", "露出一抹",
            "仿佛", "似乎", "仿若", "神色复杂", "深吸一口气", "轻叹一声", "微微一怔");

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[。！？!?…\\n]+");
    private static final Pattern HAN = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]");
    private static final Pattern DIALOGUE = Pattern.compile("[“「][^”」]{1,200}[”」]");

    /**
     * 说明书腔：把因果压成判断句，像设定卡而不像小说。
     * 例：「痕会烧，烧不穿要看后面怎么稀释。」
     */
    private static final Pattern MANUAL_CLAUSE = Pattern.compile(
            "[^，。！？；：\\n]{1,16}(?:会|能)[^，。！？；：\\n]{1,14}，"
                    + "[^，。！？；：\\n]{0,18}(?:要看|得看|全看|取决于|看后面|就看得)");

    /**
     * 忍痛模板：先给身体感受，再声明「不在意」，两句互相抵销。
     * 例：「他的手受伤了，很疼，但他没有在意。」
     */
    private static final Pattern STOIC_TEMPLATE = Pattern.compile(
            "[^，。！？\\n]{0,12}(?:很疼|太疼|很痛|一阵疼|疼得|痛得|难受)"
                    + "[^。！？\\n]{0,12}(?:但|可|却|不过)[^。！？\\n]{0,10}"
                    + "(?:没有在意|没在意|并不在意|毫不在意|毫无反应|像没感觉|仿佛不知道|没当回事)");

    /**
     * 零件作主语：让身体部位或生理现象承担感受与受动，人在句子里退场。
     * 例：「尾椎那里像被人拿根铁丝往上抽」「脊髓里那道痕会烧」。
     *
     * <p>只抓三种最常见的触发词（像／仿佛／似乎、被、会／能），不抓「血往袖子里走」这种
     * 物在动的写法——那是对的。</p>
     */
    private static final Pattern BODY_SUBJECT = Pattern.compile(
            "(?:尾椎|腰椎|颈椎|脊椎|脊背|脊髓|骨髓|骨缝|骨头|肋骨|肩胛|手臂|胳膊|手腕|手背|掌心|"
                    + "手指|指尖|脚踝|膝盖|后背|胸口|喉咙|脖子|后颈|牙关|舌尖|伤口|疤痕|神经|血管|"
                    + "皮肤|皮下|旧疤)"
                    + "[^，。！？；\\n]{0,5}"
                    + "(?:像|仿佛|似乎|被|会|能)");

    /**
     * 等分量化：把连续的身体感受切开计数，像仪器读数而不是人的感受。
     * 例：「一节一节地拔」「一点一点从后背爬上来」。
     *
     * <p>只认「不可再分的细分单位」（节、寸、点、下、滴、丝…）。「一步一步」「一天一天」
     * 是正常叙述，不在此列，否则整章都会被标红。</p>
     */
    private static final Pattern UNIT_COUNTING = Pattern.compile(
            "一([节寸点下滴丝分厘格块片截层次线])一\\1(?:地|的)?[^，。！？\\n]{0,6}");

    private static final int MANUAL_SAMPLE_MAX = 3;
    private static final int MANUAL_SAMPLE_LEN = 26;

    /**
     * 统计结果。
     *
     * @param hanCount       汉字数
     * @param paragraphCount 段落数
     * @param avgParagraph   平均段长（汉字）
     * @param paragraphCv    段长离散度，越小说明节奏越均匀
     * @param repeatedOpen   重复句首（出现 3 次及以上的开头）
     * @param clicheHits     套话命中次数
     * @param clicheSamples  命中的套话样例
     * @param clicheDensity  每千字套话次数
     * @param manualHits     说明书腔命中句数（判断句式 + 忍痛模板）
     * @param manualSamples  命中的原句样例
     * @param partHits       零件作主语命中句数（身体部位承担感受／受动）
     * @param unitHits       等分量化命中次数（「一节一节」配身体感觉）
     * @param bodySamples    零件作主语与等分量化的原句样例
     * @param dialogueRatio  含对话的段落占比
     * @param avgSentence    平均句长（汉字）
     */
    public record Metrics(int hanCount,
                          int paragraphCount,
                          int avgParagraph,
                          double paragraphCv,
                          List<String> repeatedOpen,
                          int clicheHits,
                          List<String> clicheSamples,
                          double clicheDensity,
                          int manualHits,
                          List<String> manualSamples,
                          int partHits,
                          int unitHits,
                          List<String> bodySamples,
                          double dialogueRatio,
                          double avgSentence) {
    }

    /**
     * 一条给界面看的信号。
     *
     * @param level HIGH / MID / LOW，分别对应可疑 / 一般 / 正常
     */
    public record Signal(String name, String value, String level) {
    }

    public Metrics measure(String text) {
        String content = text == null ? "" : text;
        int hanCount = countHan(content);
        List<String> paragraphs = new ArrayList<>();
        for (String line : content.split("\\n+")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        int paragraphCount = Math.max(1, paragraphs.size());
        double avgParagraph = (double) hanCount / paragraphCount;
        double paragraphCv = coefficientOfVariation(paragraphs);

        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_SPLIT.matcher(content);
        int cursor = 0;
        while (matcher.find()) {
            String part = content.substring(cursor, matcher.start()).trim();
            cursor = matcher.end();
            if (!part.isEmpty()) {
                sentences.add(part.replaceAll("^[“「\\s]+", ""));
            }
        }
        double totalSentenceHan = sentences.stream().mapToInt(this::countHan).sum();
        double avgSentence = sentences.isEmpty() ? 0 : totalSentenceHan / sentences.size();

        Map<String, Integer> openings = new LinkedHashMap<>();
        for (String sentence : sentences) {
            String opening = opening(sentence);
            if (opening.length() >= 4) {
                openings.merge(opening, 1, Integer::sum);
            }
        }
        List<String> repeated = openings.entrySet().stream()
                .filter(entry -> entry.getValue() >= 3)
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(6)
                .map(entry -> entry.getKey() + "×" + entry.getValue())
                .toList();

        int clicheHits = 0;
        List<String> samples = new ArrayList<>();
        for (String cliche : CLICHES) {
            int hits = countOccurrences(content, cliche);
            if (hits > 0) {
                clicheHits += hits;
                if (samples.size() < 6) {
                    samples.add(cliche + (hits > 1 ? "×" + hits : ""));
                }
            }
        }
        // 「仿佛」「似乎」这类两字词单独计数容易虚高，按半次折算更接近真实观感
        clicheHits = (int) Math.round(clicheHits * 0.85D);

        List<String> manualSamples = new ArrayList<>();
        int manualHits = collect(content, MANUAL_CLAUSE, manualSamples)
                + collect(content, STOIC_TEMPLATE, manualSamples);

        List<String> bodySamples = new ArrayList<>();
        int partHits = collect(content, BODY_SUBJECT, bodySamples);
        int unitHits = collect(content, UNIT_COUNTING, bodySamples);

        long dialogueParagraphs = paragraphs.stream().filter(p -> DIALOGUE.matcher(p).find()).count();
        double dialogueRatio = paragraphs.isEmpty() ? 0 : (double) dialogueParagraphs / paragraphs.size();
        double density = hanCount == 0 ? 0 : clicheHits * 1000.0D / hanCount;

        return new Metrics(hanCount, paragraphCount, (int) Math.round(avgParagraph),
                round(paragraphCv), repeated, clicheHits, samples, round(density),
                manualHits, manualSamples, partHits, unitHits, bodySamples,
                round(dialogueRatio * 100), round(avgSentence));
    }

    /** 本地信号列表，界面直接展示。 */
    public List<Signal> signals(Metrics metrics) {
        List<Signal> signals = new ArrayList<>();
        signals.add(new Signal("套话密度", String.format("%.1f 次/千字（共 %d 处）", metrics.clicheDensity(), metrics.clicheHits()),
                metrics.clicheDensity() >= 8 ? "HIGH" : metrics.clicheDensity() >= 3 ? "MID" : "LOW"));
        signals.add(new Signal("说明书腔",
                metrics.manualHits() == 0
                        ? "没有判断句式"
                        : metrics.manualHits() + " 句：" + String.join(" / ", metrics.manualSamples()),
                metrics.manualHits() >= 3 ? "HIGH" : metrics.manualHits() >= 1 ? "MID" : "LOW"));
        int bodyHits = metrics.partHits() + metrics.unitHits();
        signals.add(new Signal("身体写法",
                bodyHits == 0
                        ? "感受落在人身上，没有等分计数"
                        : String.format("零件作主语 %d 句、等分量化 %d 处：%s",
                        metrics.partHits(), metrics.unitHits(),
                        String.join(" / ", metrics.bodySamples())),
                bodyHits >= 4 ? "HIGH" : bodyHits >= 1 ? "MID" : "LOW"));
        signals.add(new Signal("重复句首", metrics.repeatedOpen().isEmpty() ? "没有明显重复" : String.join("、", metrics.repeatedOpen()),
                metrics.repeatedOpen().size() >= 3 ? "HIGH" : metrics.repeatedOpen().isEmpty() ? "LOW" : "MID"));
        signals.add(new Signal("段落节奏", String.format("平均 %d 字/段，离散度 %.2f", metrics.avgParagraph(), metrics.paragraphCv()),
                metrics.paragraphCv() < 0.35 ? "HIGH" : metrics.paragraphCv() < 0.6 ? "MID" : "LOW"));
        signals.add(new Signal("平均句长", String.format("%.0f 字/句", metrics.avgSentence()),
                metrics.avgSentence() > 34 || metrics.avgSentence() < 8 ? "MID" : "LOW"));
        signals.add(new Signal("对话占比", String.format("%.0f%% 的段落含对话", metrics.dialogueRatio()),
                metrics.dialogueRatio() < 8 ? "MID" : "LOW"));
        return signals;
    }

    /**
     * 依据本地信号给模型评分设上限：模型倾向于给高分，这里只做「只降不升」。
     *
     * @return 允许的最高分，100 表示不限制
     */
    public int scoreCap(Metrics metrics) {
        if (metrics.hanCount() < 300) {
            return 55;
        }
        if (metrics.clicheDensity() >= 8 || metrics.repeatedOpen().size() >= 4) {
            return 45;
        }
        // 说明书腔、零件作主语、等分量化都属「不像人写」，合并成一条机械总分
        int mechanicalHits = metrics.manualHits() + metrics.partHits() + metrics.unitHits();
        if (mechanicalHits >= 5) {
            return 55;
        }
        if (metrics.clicheDensity() >= 4 || metrics.repeatedOpen().size() >= 2 || mechanicalHits >= 2) {
            return 65;
        }
        if (metrics.clicheDensity() >= 2 || !metrics.repeatedOpen().isEmpty() || metrics.paragraphCv() < 0.3) {
            return 80;
        }
        return 100;
    }

    /** 把本地统计写成提示词片段。 */
    public String describe(Metrics metrics) {
        return """
                汉字数 %d，段落数 %d，平均段长 %d 字，段长离散度 %.2f（越小越均匀，均匀往往更像 AI）。
                套话命中 %d 处（每千字 %.1f），样例：%s。
                说明书腔 %d 处（把因果压成判断句、先说疼再说不在意），样例：%s。
                身体写法问题 %d 处（零件作主语 %d 句、等分量化 %d 处），样例：%s。
                重复句首：%s。
                平均句长 %.0f 字，含对话的段落占比 %.0f%%。
                """.formatted(
                metrics.hanCount(),
                metrics.paragraphCount(),
                metrics.avgParagraph(),
                metrics.paragraphCv(),
                metrics.clicheHits(),
                metrics.clicheDensity(),
                metrics.clicheSamples().isEmpty() ? "无" : String.join("、", metrics.clicheSamples()),
                metrics.manualHits(),
                metrics.manualSamples().isEmpty() ? "无" : String.join("／", metrics.manualSamples()),
                metrics.partHits() + metrics.unitHits(),
                metrics.partHits(),
                metrics.unitHits(),
                metrics.bodySamples().isEmpty() ? "无" : String.join("／", metrics.bodySamples()),
                metrics.repeatedOpen().isEmpty() ? "无" : String.join("、", metrics.repeatedOpen()),
                metrics.avgSentence(),
                metrics.dialogueRatio());
    }

    /** 按句式扫描命中数，顺带留几条原句作证据。 */
    private int collect(String content, Pattern pattern, List<String> samples) {
        Matcher matcher = pattern.matcher(content);
        int hits = 0;
        while (matcher.find()) {
            hits++;
            if (samples.size() < MANUAL_SAMPLE_MAX) {
                samples.add(clip(matcher.group()));
            }
        }
        return hits;
    }

    private String clip(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= MANUAL_SAMPLE_LEN ? flat : flat.substring(0, MANUAL_SAMPLE_LEN) + "…";
    }

    private String opening(String sentence) {
        String cleaned = sentence.replaceAll("^[\\s“”「」『』（(]+", "");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < cleaned.length() && builder.length() < 4; i++) {
            char ch = cleaned.charAt(i);
            if (HAN.matcher(String.valueOf(ch)).matches()) {
                builder.append(ch);
            } else {
                break;
            }
        }
        return builder.toString();
    }

    private double coefficientOfVariation(List<String> paragraphs) {
        if (paragraphs.size() < 2) {
            return 0;
        }
        double[] lengths = paragraphs.stream().mapToDouble(this::countHan).toArray();
        double mean = 0;
        for (double value : lengths) {
            mean += value;
        }
        mean /= lengths.length;
        if (mean == 0) {
            return 0;
        }
        double variance = 0;
        for (double value : lengths) {
            variance += (value - mean) * (value - mean);
        }
        variance /= lengths.length;
        return Math.sqrt(variance) / mean;
    }

    private int countHan(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = HAN.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int countOccurrences(String text, String keyword) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) >= 0) {
            count++;
            index += keyword.length();
        }
        return count;
    }

    private double round(double value) {
        return Math.round(value * 100) / 100.0D;
    }
}
