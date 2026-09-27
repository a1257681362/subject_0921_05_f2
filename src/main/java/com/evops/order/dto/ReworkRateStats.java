package com.evops.order.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 返修率统计结果（以加工单底账为准）：
 * 分母为时段内建档的加工单数，分子为其中有 REWORK 流转记录的单数。
 */
@Getter
@Setter
public class ReworkRateStats {
    private String storeCode;
    private LocalDateTime begin;
    private LocalDateTime end;
    /** 时段内建档加工单数 */
    private int totalOrders;
    /** 其中有返修记录的单数 */
    private int reworkOrders;
    /** 返修总次数（一单可多次返修） */
    private int reworkTimes;
    /** 返修率 = reworkOrders / totalOrders，4 位小数；分母为 0 时为 0 */
    private BigDecimal reworkRate;
    /** 返修加工单号清单（供兄弟组逐单核对） */
    private List<String> reworkOrderNos;
}
