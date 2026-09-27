package com.evops.sensitive;

import org.springframework.stereotype.Component;

/**
 * 文本归一化：全角转半角、全角空格转普通空格、ASCII 大小写折叠。
 * 所有规则均为等长映射，归一化前后下标一一对应，命中位置可直接回映原文。
 * 分隔符（非字母非数字）不删除，由 DFA 匹配时跳过，保证“分隔符插空也能命中”。
 */
@Component
public class TextNormalizer {

    private static final int FULL_WIDTH_START = 0xFF01; // ！
    private static final int FULL_WIDTH_END = 0xFF5E;   // ～
    private static final int FULL_WIDTH_SHIFT = 0xFEE0;
    private static final char FULL_WIDTH_SPACE = 0x3000; // 全角空格

    /** 归一化整段文本；null/空串原样兜底为空串 */
    public NormalizedText normalize(String raw) {
        String source = raw == null ? "" : raw;
        int len = source.length();
        char[] chars = new char[len];
        int[] originIndex = new int[len];
        for (int i = 0; i < len; i++) {
            chars[i] = normalizeChar(source.charAt(i));
            originIndex[i] = i;
        }
        return new NormalizedText(source, new String(chars), originIndex);
    }

    /** 归一化词库词条：先等长归一，再剔除词条内部的分隔符（“假 药” ≡ “假药”） */
    public String normalizeWord(String word) {
        if (word == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = normalizeChar(word.charAt(i));
            if (!isSeparator(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private char normalizeChar(char c) {
        if (c == FULL_WIDTH_SPACE) {
            return ' ';
        }
        char normalized = c;
        if (c >= FULL_WIDTH_START && c <= FULL_WIDTH_END) {
            normalized = (char) (c - FULL_WIDTH_SHIFT);
        }
        // 大小写折叠放在全角转换之后：全角字母（Ｖ→V）也要折成小写
        if (normalized >= 'A' && normalized <= 'Z') {
            normalized = (char) (normalized + 32);
        }
        return normalized;
    }

    /** 分隔符：非字母非数字。中文属 letter 不受影响；空格、标点、符号、emoji 均为分隔符 */
    public boolean isSeparator(char c) {
        return !Character.isLetterOrDigit(c);
    }
}
