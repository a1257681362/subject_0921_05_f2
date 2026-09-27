package com.evops.sensitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词 DFA（前缀树）。词条已归一化且不含分隔符；
 * 扫描时字间分隔符直接跳过，因此“假-药”“假　药”均可命中“假药”。
 * 命中区间 [start, end) 为原文下标，跨度含插空分隔符。
 * 构建后不可变，词库热更新整体换树。
 */
public class SensitiveWordTrie {

    public static final String LEVEL_BLOCK = "BLOCK";
    public static final String LEVEL_MASK = "MASK";

    private final Node root = new Node();
    private final int wordCount;

    /**
     * @param wordToLevel 归一化后的词 → 级别（BLOCK/MASK）；wordToRaw 归一化后的词 → 词库原始形态
     */
    public SensitiveWordTrie(Map<String, String> wordToLevel, Map<String, String> wordToRaw) {
        int count = 0;
        for (Map.Entry<String, String> entry : wordToLevel.entrySet()) {
            String word = entry.getKey();
            if (word.isEmpty()) {
                continue;
            }
            Node node = root;
            for (int i = 0; i < word.length(); i++) {
                node = node.children.computeIfAbsent(word.charAt(i), k -> new Node());
            }
            node.level = entry.getValue();
            node.word = wordToRaw.getOrDefault(word, word);
            count++;
        }
        this.wordCount = count;
    }

    public int wordCount() {
        return wordCount;
    }

    /**
     * 扫描全部命中。对每个起点沿树推进：遇到分隔符跳过（不计入词），
     * 每经过一个终止节点记录一次命中（长短词都会报，前端可自行合并）。
     */
    public List<SensitiveHit> scan(NormalizedText text, TextNormalizer normalizer) {
        List<SensitiveHit> hits = new ArrayList<>();
        int len = text.length();
        for (int start = 0; start < len; start++) {
            if (normalizer.isSeparator(text.charAt(start))) {
                continue;
            }
            Node node = root;
            int cursor = start;
            int lastWordChar = -1;
            while (cursor < len) {
                char c = text.charAt(cursor);
                if (normalizer.isSeparator(c)) {
                    // 分隔符插空：跳过，但已走到终止节点的命中先落袋
                    cursor++;
                    continue;
                }
                Node next = node.children.get(c);
                if (next == null) {
                    break;
                }
                node = next;
                lastWordChar = cursor;
                if (node.level != null) {
                    int originStart = text.originIndex(start);
                    int originEnd = text.originIndex(lastWordChar) + 1;
                    hits.add(new SensitiveHit(node.word, node.level, originStart, originEnd,
                            text.raw().substring(originStart, originEnd)));
                }
                cursor++;
            }
        }
        return hits;
    }

    private static class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private String level;
        private String word;
    }
}
