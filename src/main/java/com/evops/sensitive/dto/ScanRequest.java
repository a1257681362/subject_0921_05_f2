package com.evops.sensitive.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 敏感词扫描请求（前端实时标记用） */
@Getter
@Setter
public class ScanRequest {
    @NotBlank(message = "扫描文本不能为空")
    @Size(max = 2000)
    private String text;
}
