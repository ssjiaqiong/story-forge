package com.storyforge.infra.search;

/**
 * 一条检索结果。
 */
public record SearchHit(String title, String url, String snippet) {
}
