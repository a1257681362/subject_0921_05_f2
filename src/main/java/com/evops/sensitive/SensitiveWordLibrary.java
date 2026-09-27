package com.evops.sensitive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 敏感词词库：文件存储 + 热更新。
 * 词库文件每行一条「词|级别」，级别 BLOCK=拦截 / MASK=打码（缺省 MASK），# 开头为注释。
 * 定时检测文件 mtime，变化即重建 DFA 并原子换树，无需重启；
 * 也可通过 reload 接口立即生效。文件缺失时从 classpath 默认词库播种。
 */
@Component
public class SensitiveWordLibrary {

    public static final String DEFAULT_WORDS_CLASSPATH = "sensitive-words-default.txt";

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordLibrary.class);

    private final TextNormalizer normalizer;
    private final Path wordFile;
    private final boolean enabled;

    private final AtomicReference<SensitiveWordTrie> trieRef =
            new AtomicReference<>(emptyTrie());
    private volatile long lastLoadedModified = -1L;

    public SensitiveWordLibrary(TextNormalizer normalizer,
                                @Value("${evops.sensitive.word-file:./data/sensitive-words.txt}") String wordFile,
                                @Value("${evops.sensitive.enabled:true}") boolean enabled) {
        this.normalizer = normalizer;
        this.wordFile = Paths.get(wordFile);
        this.enabled = enabled;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("敏感词扫描已关闭（evops.sensitive.enabled=false）");
            return;
        }
        seedIfMissing();
        reload();
    }

    /** 定时检测词库文件 mtime，有变化即热更新 */
    @Scheduled(fixedDelayString = "${evops.sensitive.reload-interval-ms:30000}")
    public void reloadIfChanged() {
        if (!enabled) {
            return;
        }
        try {
            if (!Files.exists(wordFile)) {
                return;
            }
            long modified = Files.getLastModifiedTime(wordFile).toMillis();
            if (modified != lastLoadedModified) {
                reload();
            }
        } catch (IOException ex) {
            log.warn("检测敏感词库文件失败：{}", ex.getMessage());
        }
    }

    /** 立即重建词库。解析失败时保留旧词库，不中断扫描 */
    public synchronized int reload() {
        try {
            List<String> lines = Files.readAllLines(wordFile, StandardCharsets.UTF_8);
            Map<String, String> wordToLevel = new HashMap<>();
            Map<String, String> wordToRaw = new HashMap<>();
            for (String line : lines) {
                parseLine(line, wordToLevel, wordToRaw);
            }
            SensitiveWordTrie trie = new SensitiveWordTrie(wordToLevel, wordToRaw);
            trieRef.set(trie);
            lastLoadedModified = Files.getLastModifiedTime(wordFile).toMillis();
            log.info("敏感词库已加载：{} 条，文件 {}", trie.wordCount(), wordFile.toAbsolutePath());
            return trie.wordCount();
        } catch (IOException ex) {
            log.error("敏感词库加载失败，保留旧词库：{}", ex.getMessage());
            return trieRef.get().wordCount();
        }
    }

    /** 扫描文本；扫描关闭时永远无命中 */
    public List<SensitiveHit> scan(String raw) {
        if (!enabled || raw == null || raw.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return trieRef.get().scan(normalizer.normalize(raw), normalizer);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int wordCount() {
        return trieRef.get().wordCount();
    }

    public String wordFilePath() {
        return wordFile.toAbsolutePath().toString();
    }

    public long lastLoadedModified() {
        return lastLoadedModified;
    }

    /** 词库文件不存在时，从 classpath 默认词库播种，便于开箱即用 */
    private void seedIfMissing() {
        if (Files.exists(wordFile)) {
            return;
        }
        try {
            if (wordFile.getParent() != null) {
                Files.createDirectories(wordFile.getParent());
            }
            Files.copy(new ClassPathResource(DEFAULT_WORDS_CLASSPATH).getInputStream(),
                    wordFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("敏感词库文件不存在，已从默认词库播种：{}", wordFile.toAbsolutePath());
        } catch (IOException ex) {
            log.error("敏感词库播种失败：{}", ex.getMessage());
        }
    }

    /** 行格式：词|级别。词本身同样归一化并剔除内部分隔符；同词重复时后行覆盖前行 */
    private void parseLine(String line, Map<String, String> wordToLevel, Map<String, String> wordToRaw) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        String word = trimmed;
        String level = SensitiveWordTrie.LEVEL_MASK;
        int pipe = trimmed.lastIndexOf('|');
        if (pipe >= 0) {
            word = trimmed.substring(0, pipe).trim();
            String levelText = trimmed.substring(pipe + 1).trim().toUpperCase();
            if (SensitiveWordTrie.LEVEL_BLOCK.equals(levelText)) {
                level = SensitiveWordTrie.LEVEL_BLOCK;
            }
        }
        String normalized = normalizer.normalizeWord(word);
        if (normalized.isEmpty()) {
            return;
        }
        wordToLevel.put(normalized, level);
        wordToRaw.put(normalized, word);
    }

    private static SensitiveWordTrie emptyTrie() {
        return new SensitiveWordTrie(new HashMap<>(), new HashMap<>());
    }
}
