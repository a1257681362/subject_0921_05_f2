package com.evops.sensitive;

/**
 * 一次敏感词命中。start/end 为原文下标（end 不含），跨度包含插空的分隔符，
 * 前端可直接按 [start, end) 标记；surface 为原文命中片段。
 */
public class SensitiveHit {

    /** 词库中的词（原始形态） */
    private final String word;
    /** 级别：BLOCK 拦截 / MASK 打码 */
    private final String level;
    /** 命中起始下标（原文，含） */
    private final int start;
    /** 命中结束下标（原文，不含） */
    private final int end;
    /** 原文命中片段（含插空分隔符） */
    private final String surface;

    public SensitiveHit(String word, String level, int start, int end, String surface) {
        this.word = word;
        this.level = level;
        this.start = start;
        this.end = end;
        this.surface = surface;
    }

    public String getWord() { return word; }
    public String getLevel() { return level; }
    public int getStart() { return start; }
    public int getEnd() { return end; }
    public String getSurface() { return surface; }

    @Override
    public String toString() {
        return word + "(" + level + ")[" + start + "," + end + ")";
    }
}
