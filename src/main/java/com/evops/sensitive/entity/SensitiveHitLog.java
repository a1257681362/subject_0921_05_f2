package com.evops.sensitive.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 敏感词命中记录（只追加）。拦截时加工单未落地，bizId 为空、仅记 orderNo；
 * 打码时与加工单同事务写入。数据供兄弟组取数分析。
 */
@Getter
@Setter
@TableName("t_sensitive_hit_log")
public class SensitiveHitLog extends BaseEntity {
    /** 业务类型：PROCESS_ORDER */
    private String bizType;
    /** 加工单ID（拦截时为空） */
    private Long bizId;
    private String orderNo;
    /** 命中字段：customerName / remark */
    private String fieldName;
    /** 词库中的词 */
    private String hitWord;
    /** 级别：BLOCK / MASK */
    private String wordLevel;
    /** 命中起始下标（原文，含） */
    private Integer startIndex;
    /** 命中结束下标（原文，不含） */
    private Integer endIndex;
    /** 原文命中片段（含插空分隔符） */
    private String surfaceText;
    /** 处置：BLOCK 拦截 / MASK 打码 */
    private String actionTaken;
}
