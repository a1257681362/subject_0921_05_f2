package com.evops.order.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 返修率反向核对请求：兄弟组按我方数据算出的结果传回，
 * 系统以加工单底账重算并比对。reworkOrderNos 可选，提供时逐单比差集。
 */
@Getter
@Setter
public class ReworkRateReconcileRequest {
    private String storeCode;
    private LocalDateTime begin;
    private LocalDateTime end;

    @NotNull(message = "对方统计的建档单数不能为空")
    @Min(value = 0, message = "建档单数不能为负")
    private Integer totalOrders;

    @NotNull(message = "对方统计的返修单数不能为空")
    @Min(value = 0, message = "返修单数不能为负")
    private Integer reworkOrders;

    /** 对方认定的返修加工单号清单（可选） */
    private List<String> reworkOrderNos;
}
