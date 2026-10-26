package com.storyforge.common.util;

/**
 * 字数口径：只统计汉字。
 *
 * <p>产品口径是「汉字数」——标点、空格、数字、英文字母都不计入。用
 * {@link Character.UnicodeScript#HAN} 判断，覆盖扩展区汉字与兼容汉字，
 * 避免用正则 {@code [\\u4e00-\\u9fa5]} 漏掉生僻字。</p>
 */
public final class WordCounts {

    private WordCounts() {
    }

    /** 统计文本中的汉字个数。 */
    public static int countChinese(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                count++;
            }
            i += Character.charCount(codePoint);
        }
        return count;
    }

    /** 文案里统一使用的字数说明。 */
    public static String note() {
        return "按汉字计，标点、数字与字母不计";
    }
}
