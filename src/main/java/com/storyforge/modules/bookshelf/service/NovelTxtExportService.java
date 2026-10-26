package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.exception.BizException;
import com.storyforge.common.util.WordCounts;
import com.storyforge.modules.bookshelf.dto.TxtExportOptions;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 小说正文导出为 TXT。
 *
 * <p>与 {@link NovelTransferService} 的 JSON 备份包不同，这里产出的是<b>给人读的成品</b>：
 * 只有卷名、章节标题和正文，不含任何 id、设定、大纲摘要、分析记录。</p>
 *
 * <p>格式写死、不做模板，因为各网文平台的差异主要在编码与章节标题，这两项已由参数覆盖：
 * 段首不缩进（作者各自偏好不同，加了反而要手动删），段落间空行原样保留。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelTxtExportService {

    /** UTF-8 BOM：Windows 记事本与不少投稿平台靠它判断编码。 */
    private static final String BOM = "\uFEFF";

    /** 章节标题里已带「第 N 章」前缀时原样输出，避免出现「第 3 章 第三章 旧案」。 */
    private static final Pattern HAS_CHAPTER_PREFIX =
            Pattern.compile("^第\\s*[\\d零一二三四五六七八九十百千万两]+\\s*[章回节]");

    private static final String EMPTY_HINT = "（这本书还没有可导出的正文）";

    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;

    /** 导出结果：书名用于拼下载文件名，正文已按选项拼好（含 BOM）。 */
    public record TxtFile(String title, String text) {
    }

    /**
     * 生成 TXT 全文。
     *
     * <p>返回的字符串在 {@code bom=true} 时已带 BOM 字符，调用方按 UTF-8 编码成字节即可。</p>
     */
    public TxtFile exportTxt(Long novelId, TxtExportOptions options) {
        TxtExportOptions opts = options == null ? new TxtExportOptions() : options;
        validate(opts);

        NovelEntity novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }

        List<ChapterEntity> chapters = chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId)
                .orderByAsc(ChapterEntity::getSortNo)
                .orderByAsc(ChapterEntity::getId));

        List<ChapterEntity> selected = new ArrayList<>();
        for (ChapterEntity chapter : chapters) {
            if (!inScope(chapter, opts)) {
                continue;
            }
            if (!opts.isIncludeEmpty() && !hasBody(chapter)) {
                continue;
            }
            selected.add(chapter);
        }

        StringBuilder sb = new StringBuilder();
        if (opts.isBom()) {
            sb.append(BOM);
        }
        if (selected.isEmpty()) {
            sb.append(EMPTY_HINT).append('\n');
            return new TxtFile(novel.getTitle(), sb.toString());
        }

        // 用「块 + 空行」拼装：卷名块与章节块之间恒为一个空行，避免不同分支拼出连续空行
        List<String> blocks = new ArrayList<>();
        Integer currentVolume = null;
        for (ChapterEntity chapter : selected) {
            if (opts.isWithVolume() && !Objects.equals(currentVolume, volumeOf(chapter))) {
                currentVolume = volumeOf(chapter);
                blocks.add(volumeLine(chapter));
            }
            blocks.add(chapterBlock(chapter));
        }
        blocks.add(summaryLine(novel, opts, selected));

        sb.append(String.join("\n\n", blocks)).append('\n');
        log.info("[novel-txt] exported novelId={} scope={} chapters={} chars={}",
                novelId, opts.getScope(), selected.size(), sb.length());
        return new TxtFile(novel.getTitle(), sb.toString());
    }

    /** 导出范围与必填参数的一致性校验，错误信息点名缺什么。 */
    private void validate(TxtExportOptions opts) {
        String scope = opts.getScope() == null ? TxtExportOptions.SCOPE_ALL : opts.getScope().trim();
        opts.setScope(scope);
        switch (scope) {
            case TxtExportOptions.SCOPE_ALL -> {
            }
            case TxtExportOptions.SCOPE_VOLUME -> {
                if (opts.getVolumeNo() == null || opts.getVolumeNo() < 1) {
                    throw BizException.of(ErrorCode.PARAM_INVALID, "按卷导出需要指定卷序号 volumeNo");
                }
            }
            case TxtExportOptions.SCOPE_RANGE -> {
                if (opts.getFrom() == null || opts.getTo() == null) {
                    throw BizException.of(ErrorCode.PARAM_INVALID, "按章节区间导出需要同时指定 from 与 to");
                }
                if (opts.getFrom() < 1 || opts.getFrom() > opts.getTo()) {
                    throw BizException.of(ErrorCode.PARAM_INVALID, "章节区间不合法：from 必须不大于 to");
                }
            }
            default -> throw BizException.of(ErrorCode.PARAM_INVALID,
                    "导出范围只支持 all / volume / range");
        }
    }

    private boolean inScope(ChapterEntity chapter, TxtExportOptions opts) {
        return switch (opts.getScope()) {
            case TxtExportOptions.SCOPE_VOLUME -> Objects.equals(volumeOf(chapter), opts.getVolumeNo());
            case TxtExportOptions.SCOPE_RANGE -> chapter.getChapterNo() != null
                    && chapter.getChapterNo() >= opts.getFrom()
                    && chapter.getChapterNo() <= opts.getTo();
            default -> true;
        };
    }

    private boolean hasBody(ChapterEntity chapter) {
        return chapter.getContent() != null && !chapter.getContent().isBlank();
    }

    /** 老数据 volumeNo 可能为空，按第 1 卷处理，避免整本书的卷名行集体消失。 */
    private int volumeOf(ChapterEntity chapter) {
        return chapter.getVolumeNo() == null ? 1 : chapter.getVolumeNo();
    }

    private String volumeLine(ChapterEntity chapter) {
        String title = chapter.getVolumeTitle() == null ? "" : chapter.getVolumeTitle().trim();
        return title.isEmpty() ? "第 " + volumeOf(chapter) + " 卷" : title;
    }

    private String chapterBlock(ChapterEntity chapter) {
        String title = chapter.getTitle() == null ? "" : chapter.getTitle().trim();
        String heading;
        if (title.isEmpty()) {
            heading = "第 " + chapterNo(chapter) + " 章";
        } else if (HAS_CHAPTER_PREFIX.matcher(title).find()) {
            heading = title;
        } else {
            heading = "第 " + chapterNo(chapter) + " 章 " + title;
        }
        String body = normalize(chapter.getContent());
        return body.isEmpty() ? heading : heading + "\n\n" + body;
    }

    private int chapterNo(ChapterEntity chapter) {
        return chapter.getChapterNo() == null ? 0 : chapter.getChapterNo();
    }

    /** 统一换行、去掉首尾空行；段落之间的空行保持原样。 */
    private String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        int end = text.length();
        while (start < end && (text.charAt(start) == '\n' || text.charAt(start) == ' ' || text.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;
        }
        return text.substring(start, end);
    }

    /**
     * 末尾统计行。
     *
     * <p>全书导出用 {@code t_novel.word_count}（与书架展示一致）；按卷/区间导出时
     * 这个值是全书总量，会误导读者，改为按导出章节累加。</p>
     */
    private String summaryLine(NovelEntity novel, TxtExportOptions opts, List<ChapterEntity> exported) {
        int count = exported.size();
        int words;
        String scopeLabel;
        if (TxtExportOptions.SCOPE_VOLUME.equals(opts.getScope())) {
            words = sumWords(exported);
            scopeLabel = "本卷共 ";
        } else if (TxtExportOptions.SCOPE_RANGE.equals(opts.getScope())) {
            words = sumWords(exported);
            scopeLabel = "本次导出 ";
        } else {
            Integer total = novel.getWordCount();
            words = total == null || total < 0 ? sumWords(exported) : total;
            scopeLabel = "全书共 ";
        }
        return "（" + scopeLabel + count + " 章，约 " + words + " 字）";
    }

    private int sumWords(List<ChapterEntity> chapters) {
        int total = 0;
        for (ChapterEntity chapter : chapters) {
            Integer words = chapter.getWordCount();
            total += words == null || words <= 0 ? WordCounts.countChinese(chapter.getContent()) : words;
        }
        return total;
    }
}
