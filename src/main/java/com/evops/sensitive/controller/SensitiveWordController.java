package com.evops.sensitive.controller;

import com.evops.common.ApiResponse;
import com.evops.sensitive.SensitiveHit;
import com.evops.sensitive.SensitiveWordLibrary;
import com.evops.sensitive.SensitiveWordService;
import com.evops.sensitive.dto.ScanRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词接口：文本扫描（命中位置返回前端标记）、词库热更新、词库状态。
 */
@RestController
@RequestMapping("/api/sensitive-words")
public class SensitiveWordController {

    private final SensitiveWordService sensitiveWordService;
    private final SensitiveWordLibrary wordLibrary;

    public SensitiveWordController(SensitiveWordService sensitiveWordService,
                                   SensitiveWordLibrary wordLibrary) {
        this.sensitiveWordService = sensitiveWordService;
        this.wordLibrary = wordLibrary;
    }

    /** 扫描文本：返回全部命中（词、级别、原文下标区间、命中片段），前端按区间标记 */
    @PostMapping("/scan")
    public ApiResponse<Map<String, Object>> scan(@Valid @RequestBody ScanRequest request) {
        List<SensitiveHit> hits = sensitiveWordService.scan(request.getText());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hits", hits);
        data.put("hitCount", hits.size());
        return ApiResponse.ok(data);
    }

    /** 词库热更新：立即重读词库文件并重建 DFA */
    @PostMapping("/reload")
    public ApiResponse<Map<String, Object>> reload() {
        int wordCount = wordLibrary.reload();
        return ApiResponse.ok(status(wordCount));
    }

    /** 词库状态：词条数、文件路径、最近加载的文件时间 */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(status(wordLibrary.wordCount()));
    }

    private Map<String, Object> status(int wordCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", wordLibrary.isEnabled());
        data.put("wordCount", wordCount);
        data.put("wordFile", wordLibrary.wordFilePath());
        data.put("lastLoadedModified", wordLibrary.lastLoadedModified());
        return data;
    }
}
