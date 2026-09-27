package com.evops.sensitive;

/**
 * 归一化后的文本：text 为归一化结果，originIndex[i] 为归一化后第 i 个字符在原文中的下标。
 * 当前所有归一化规则均为等长映射（originIndex[i] == i），映射表显式保留，
 * 命中位置一律以原文下标对外。
 */
public class NormalizedText {

    private final String raw;
    private final String text;
    private final int[] originIndex;

    NormalizedText(String raw, String text, int[] originIndex) {
        this.raw = raw;
        this.text = text;
        this.originIndex = originIndex;
    }

    /** 原始文本（未归一化） */
    public String raw() {
        return raw;
    }

    /** 归一化后的文本，扫描匹配用它 */
    public String text() {
        return text;
    }

    public int length() {
        return text.length();
    }

    public char charAt(int i) {
        return text.charAt(i);
    }

    /** 归一化下标 → 原文下标 */
    public int originIndex(int normalizedIndex) {
        return originIndex[normalizedIndex];
    }
}
