package com.evops.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.lens.entity.Lens;
import com.evops.lens.mapper.LensMapper;
import com.evops.lens.service.LensService;
import com.evops.order.dto.OrderActionRequest;
import com.evops.order.dto.OrderCreateRequest;
import com.evops.order.dto.OrderUpdateRequest;
import com.evops.order.dto.ReworkRateReconcileRequest;
import com.evops.order.dto.ReworkRateStats;
import com.evops.order.dto.RxRequest;
import com.evops.order.entity.ProcessOrder;
import com.evops.order.entity.ProcessOrderRx;
import com.evops.order.entity.ProcessOrderStatusLog;
import com.evops.order.mapper.ProcessOrderMapper;
import com.evops.order.mapper.ProcessOrderRxMapper;
import com.evops.order.mapper.ProcessOrderStatusLogMapper;
import com.evops.sensitive.SensitiveHitLogService;
import com.evops.sensitive.SensitiveWordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 加工单：建档（含左右眼处方）、修改、取镜状态流转、冲销/恢复、按单号检索。
 * 主子表写入全部在同一事务；禁止物理删除，处方历史以 VOID 行保留，流转全程留日志。
 */
@Service
public class ProcessOrderService {

    public static final String CREATED = "CREATED";
    public static final String PROCESSING = "PROCESSING";
    public static final String READY = "READY";
    public static final String PICKED_UP = "PICKED_UP";
    public static final String CANCELLED = "CANCELLED";

    private static final String ACTIVE = "ACTIVE";
    private static final String VOID = "VOID";
    private static final BigDecimal QUARTER = new BigDecimal("0.25");
    private static final Set<String> EYES = new HashSet<>(Arrays.asList("L", "R"));

    @Resource
    private ProcessOrderMapper orderMapper;
    @Resource
    private ProcessOrderRxMapper rxMapper;
    @Resource
    private ProcessOrderStatusLogMapper statusLogMapper;
    @Resource
    private LensMapper lensMapper;
    @Resource
    private SensitiveWordService sensitiveWordService;
    @Resource
    private SensitiveHitLogService sensitiveHitLogService;

    @Transactional(rollbackFor = Exception.class)
    public ProcessOrder create(OrderCreateRequest req) {
        Lens lens = requireActiveLens(req.getLensId());

        // 文本入口管控：拦截级敏感词整单拒绝；打码级替换为 * 后落库
        SensitiveWordService.MaskResult masked =
                sensitiveWordService.checkOrderText(req.getCustomerName(), req.getRemark(), req.getOrderNo());

        ProcessOrder order = new ProcessOrder();
        order.setOrderNo(req.getOrderNo());
        order.setCustomerName(masked.getCustomerName());
        order.setCustomerPhone(req.getCustomerPhone());
        order.setStoreCode(req.getStoreCode());
        order.setLensId(lens.getId());
        order.setRemark(masked.getRemark());
        order.setOrderStatus(CREATED);
        // uk_order_no 兜底单号重复，uk_order_request 兜底请求号重复提交
        orderMapper.insert(order);

        insertPrescriptions(order.getId(), req.getPrescriptions());

        order = orderMapper.selectById(order.getId());
        writeLog(order.getId(), "CREATE", null, CREATED, "加工单建档", order.getVersionNo());
        // 打码命中记录与加工单同事务，同生共死
        sensitiveHitLogService.recordMasked(masked.getHits(), order.getId(), order.getOrderNo());
        return order;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProcessOrder update(OrderUpdateRequest req) {
        ProcessOrder order = requireOrder(req.getId());
        if (!CREATED.equals(order.getOrderStatus())) {
            throw new BizException(BizCode.ORDER_STATUS_ILLEGAL, "仅已建档状态可修改");
        }
        if (req.getVersionNo() != null && !req.getVersionNo().equals(order.getVersionNo())) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        Long lensId = req.getLensId() == null ? order.getLensId() : requireActiveLens(req.getLensId()).getId();

        // 文本入口管控：仅扫描本次提交的字段（null 表示不修改）
        SensitiveWordService.MaskResult masked =
                sensitiveWordService.checkOrderText(req.getCustomerName(), req.getRemark(), order.getOrderNo());

        order.setLensId(lensId);
        if (req.getCustomerName() != null) order.setCustomerName(masked.getCustomerName());
        if (req.getCustomerPhone() != null) order.setCustomerPhone(req.getCustomerPhone());
        if (req.getStoreCode() != null) order.setStoreCode(req.getStoreCode());
        if (req.getRemark() != null) order.setRemark(masked.getRemark());

        if (orderMapper.updateById(order) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }

        // 处方整组原子替换：旧 ACTIVE 行置 VOID（保留痕迹，不物理删除），再插入新行
        voidActivePrescriptions(order.getId());
        insertPrescriptions(order.getId(), req.getPrescriptions());

        order = orderMapper.selectById(order.getId());
        writeLog(order.getId(), "UPDATE", CREATED, CREATED, "修改加工单及处方", order.getVersionNo());
        sensitiveHitLogService.recordMasked(masked.getHits(), order.getId(), order.getOrderNo());
        return order;
    }

    // ---------------- 取镜状态流转 ----------------

    /** 开始加工：已建档 → 加工中 */
    @Transactional(rollbackFor = Exception.class)
    public ProcessOrder start(Long id, OrderActionRequest req) {
        return transit(id, "START", CREATED, PROCESSING, req);
    }

    /** 加工完成：加工中 → 待取镜 */
    @Transactional(rollbackFor = Exception.class)
    public ProcessOrder finish(Long id, OrderActionRequest req) {
        return transit(id, "FINISH", PROCESSING, READY, req);
    }

    /** 取镜：待取镜 → 已取镜（终态） */
    @Transactional(rollbackFor = Exception.class)
    public ProcessOrder pickup(Long id, OrderActionRequest req) {
        return transit(id, "PICKUP", READY, PICKED_UP, req);
    }

    /** 返修：待取镜 → 加工中（成品复检不通过打回），只追加流转日志 */
    @Transactional(rollbackFor = Exception.class)
    public ProcessOrder rework(Long id, OrderActionRequest req) {
        return transit(id, "REWORK", READY, PROCESSING, req);
    }

    /** 冲销：已建档/加工中 → 已冲销；已取镜不可冲销 */
    @Transactional(rollbackFor = Exception.class)
    public ProcessOrder cancel(Long id, OrderActionRequest req) {
        ProcessOrder order = requireOrder(id);
        checkVersion(order, req);
        if (CANCELLED.equals(order.getOrderStatus())) {
            throw new BizException(BizCode.ORDER_ALREADY_CANCELLED);
        }
        if (PICKED_UP.equals(order.getOrderStatus())) {
            throw new BizException(BizCode.ORDER_PICKED_UP);
        }
        return applyTransit(order, "CANCEL", CANCELLED, req.getRemark());
    }

    /** 恢复：已冲销 → 已建档 */
    @Transactional(rollbackFor = Exception.class)
    public ProcessOrder restore(Long id, OrderActionRequest req) {
        ProcessOrder order = requireOrder(id);
        checkVersion(order, req);
        if (!CANCELLED.equals(order.getOrderStatus())) {
            throw new BizException(BizCode.ORDER_STATUS_ILLEGAL, "仅已冲销加工单可恢复");
        }
        return applyTransit(order, "RESTORE", CREATED, req.getRemark());
    }

    private ProcessOrder transit(Long id, String operType, String expectedFrom,
                                 String toStatus, OrderActionRequest req) {
        ProcessOrder order = requireOrder(id);
        checkVersion(order, req);
        if (!expectedFrom.equals(order.getOrderStatus())) {
            throw new BizException(BizCode.ORDER_STATUS_ILLEGAL,
                    "当前状态[" + order.getOrderStatus() + "]不允许该操作，应为[" + expectedFrom + "]");
        }
        return applyTransit(order, operType, toStatus, req.getRemark());
    }

    private ProcessOrder applyTransit(ProcessOrder order, String operType, String toStatus, String remark) {
        String fromStatus = order.getOrderStatus();
        order.setOrderStatus(toStatus);
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        order = orderMapper.selectById(order.getId());
        writeLog(order.getId(), operType, fromStatus, toStatus, remark, order.getVersionNo());
        return order;
    }

    // ---------------- 查询 ----------------

    public ProcessOrder get(Long id) {
        return requireOrder(id);
    }

    /** 按加工单号精确检索 */
    public ProcessOrder getByOrderNo(String orderNo) {
        ProcessOrder order = orderMapper.selectOne(new LambdaQueryWrapper<ProcessOrder>()
                .eq(ProcessOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(BizCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    public List<ProcessOrder> list(String status, String storeCode) {
        LambdaQueryWrapper<ProcessOrder> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            qw.eq(ProcessOrder::getOrderStatus, status.trim());
        }
        if (storeCode != null && !storeCode.trim().isEmpty()) {
            qw.eq(ProcessOrder::getStoreCode, storeCode.trim());
        }
        qw.orderByDesc(ProcessOrder::getId);
        return orderMapper.selectList(qw);
    }

    public List<ProcessOrderRx> listActivePrescriptions(Long orderId) {        return rxMapper.selectList(new LambdaQueryWrapper<ProcessOrderRx>()
                .eq(ProcessOrderRx::getOrderId, orderId)
                .eq(ProcessOrderRx::getRecordStatus, ACTIVE)
                .orderByAsc(ProcessOrderRx::getEye));
    }

    public List<ProcessOrderStatusLog> listStatusLogs(Long orderId) {
        return statusLogMapper.selectList(new LambdaQueryWrapper<ProcessOrderStatusLog>()
                .eq(ProcessOrderStatusLog::getOrderId, orderId)
                .orderByAsc(ProcessOrderStatusLog::getId));
    }

    // ---------------- 返修率统计与反向核对（以加工单底账为准） ----------------

    /**
     * 返修率：分母为时段内建档的加工单（按 created_time，含已冲销），
     * 分子为其中有 REWORK 流转记录的单数；reworkTimes 为返修总次数。
     */
    public ReworkRateStats reworkRate(String storeCode, LocalDateTime begin, LocalDateTime end) {
        LambdaQueryWrapper<ProcessOrder> qw = new LambdaQueryWrapper<>();
        if (storeCode != null && !storeCode.trim().isEmpty()) {
            qw.eq(ProcessOrder::getStoreCode, storeCode.trim());
        }
        if (begin != null) {
            qw.ge(ProcessOrder::getCreatedTime, begin);
        }
        if (end != null) {
            qw.lt(ProcessOrder::getCreatedTime, end);
        }
        List<ProcessOrder> orders = orderMapper.selectList(qw);

        Map<Long, String> orderNoById = new LinkedHashMap<>();
        for (ProcessOrder order : orders) {
            orderNoById.put(order.getId(), order.getOrderNo());
        }

        Map<Long, Integer> reworkTimesByOrder = new LinkedHashMap<>();
        if (!orderNoById.isEmpty()) {
            List<ProcessOrderStatusLog> reworkLogs = statusLogMapper.selectList(
                    new LambdaQueryWrapper<ProcessOrderStatusLog>()
                            .eq(ProcessOrderStatusLog::getOperType, "REWORK")
                            .in(ProcessOrderStatusLog::getOrderId, orderNoById.keySet()));
            for (ProcessOrderStatusLog log : reworkLogs) {
                reworkTimesByOrder.merge(log.getOrderId(), 1, Integer::sum);
            }
        }

        ReworkRateStats stats = new ReworkRateStats();
        stats.setStoreCode(storeCode);
        stats.setBegin(begin);
        stats.setEnd(end);
        stats.setTotalOrders(orders.size());
        stats.setReworkOrders(reworkTimesByOrder.size());
        int reworkTimes = reworkTimesByOrder.values().stream().mapToInt(Integer::intValue).sum();
        stats.setReworkTimes(reworkTimes);
        stats.setReworkRate(orders.isEmpty() ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(reworkTimesByOrder.size())
                        .divide(BigDecimal.valueOf(orders.size()), 4, RoundingMode.HALF_UP));
        List<String> reworkOrderNos = new ArrayList<>();
        for (Long orderId : reworkTimesByOrder.keySet()) {
            reworkOrderNos.add(orderNoById.get(orderId));
        }
        stats.setReworkOrderNos(reworkOrderNos);
        return stats;
    }

    /**
     * 反向核对：兄弟组回传其返修率结果，系统以加工单底账重算并比对。
     * 对方提供返修单号清单时，额外输出双向差集（我方有对方没有 / 对方有我方没有）。
     */
    public Map<String, Object> reconcileReworkRate(ReworkRateReconcileRequest req) {
        ReworkRateStats ours = reworkRate(req.getStoreCode(), req.getBegin(), req.getEnd());

        boolean totalMatch = ours.getTotalOrders() == req.getTotalOrders();
        boolean reworkMatch = ours.getReworkOrders() == req.getReworkOrders();

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("totalOrdersDiff", ours.getTotalOrders() - req.getTotalOrders());
        diff.put("reworkOrdersDiff", ours.getReworkOrders() - req.getReworkOrders());

        boolean consistent = totalMatch && reworkMatch;
        if (req.getReworkOrderNos() != null) {
            Set<String> theirs = new HashSet<>(req.getReworkOrderNos());
            Set<String> oursSet = new HashSet<>(ours.getReworkOrderNos());
            List<String> missingInTheirs = ours.getReworkOrderNos().stream()
                    .filter(no -> !theirs.contains(no)).collect(Collectors.toList());
            List<String> extraInTheirs = req.getReworkOrderNos().stream()
                    .filter(no -> !oursSet.contains(no)).distinct().collect(Collectors.toList());
            diff.put("missingInTheirs", missingInTheirs);
            diff.put("extraInTheirs", extraInTheirs);
            consistent = consistent && missingInTheirs.isEmpty() && extraInTheirs.isEmpty();
        }

        Map<String, Object> theirsView = new LinkedHashMap<>();
        theirsView.put("totalOrders", req.getTotalOrders());
        theirsView.put("reworkOrders", req.getReworkOrders());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("consistent", consistent);
        result.put("ours", ours);
        result.put("theirs", theirsView);
        result.put("diff", diff);
        return result;
    }

    // ---------------- 处方校验与写入 ----------------

    private void insertPrescriptions(Long orderId, List<RxRequest> prescriptions) {
        List<RxRequest> validated = validatePrescriptions(prescriptions);
        for (RxRequest rx : validated) {
            ProcessOrderRx entity = new ProcessOrderRx();
            entity.setOrderId(orderId);
            entity.setEye(rx.getEye());
            entity.setSphere(rx.getSphere());
            entity.setCylinder(rx.getCylinder());
            entity.setAxis(rx.getAxis());
            entity.setAddPower(rx.getAddPower());
            entity.setPd(rx.getPd());
            entity.setRecordStatus(ACTIVE);
            rxMapper.insert(entity);
        }
    }

    private void voidActivePrescriptions(Long orderId) {
        List<ProcessOrderRx> actives = rxMapper.selectList(new LambdaQueryWrapper<ProcessOrderRx>()
                .eq(ProcessOrderRx::getOrderId, orderId)
                .eq(ProcessOrderRx::getRecordStatus, ACTIVE));
        for (ProcessOrderRx rx : actives) {
            rx.setRecordStatus(VOID);
            rxMapper.updateById(rx);
        }
    }

    /**
     * 左右眼各一条；度数（球镜必填，柱镜/下加光选填）必须为 0.25 的整数倍；
     * 有散光时轴位必填。
     */
    private List<RxRequest> validatePrescriptions(List<RxRequest> prescriptions) {
        Set<String> seenEyes = new HashSet<>();
        List<RxRequest> result = new ArrayList<>();
        for (RxRequest rx : prescriptions) {
            if (rx.getEye() == null || !EYES.contains(rx.getEye().trim().toUpperCase())) {
                throw new BizException(BizCode.VALIDATION_FAILED, "眼别只能为 L（左眼）或 R（右眼）");
            }
            rx.setEye(rx.getEye().trim().toUpperCase());
            if (!seenEyes.add(rx.getEye())) {
                throw new BizException(BizCode.RX_EYE_DUPLICATED);
            }
            if (rx.getSphere() == null) {
                throw new BizException(BizCode.VALIDATION_FAILED, rx.getEye() + "眼球镜度数不能为空");
            }
            assertQuarterStep(rx.getSphere(), rx.getEye() + "眼球镜");
            if (rx.getCylinder() != null) {
                assertQuarterStep(rx.getCylinder(), rx.getEye() + "眼柱镜");
                if (rx.getCylinder().compareTo(BigDecimal.ZERO) != 0 && rx.getAxis() == null) {
                    throw new BizException(BizCode.VALIDATION_FAILED, rx.getEye() + "眼有散光时轴位必填");
                }
            }
            if (rx.getAddPower() != null) {
                assertQuarterStep(rx.getAddPower(), rx.getEye() + "眼下加光");
            }
            result.add(rx);
        }
        if (!seenEyes.equals(EYES)) {
            throw new BizException(BizCode.RX_EYE_REQUIRED);
        }
        return result;
    }

    /** 二十五度一档：度数 ×4 必须为整数 */
    private void assertQuarterStep(BigDecimal value, String label) {
        if (value.remainder(QUARTER).compareTo(BigDecimal.ZERO) != 0) {
            throw new BizException(BizCode.RX_DEGREE_STEP, label + "度数[" + value + "]不在档");
        }
    }

    // ---------------- 公共校验 ----------------

    private ProcessOrder requireOrder(Long id) {
        ProcessOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BizException(BizCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private Lens requireActiveLens(Long lensId) {
        if (lensId == null) {
            throw new BizException(BizCode.VALIDATION_FAILED, "镜片不能为空");
        }
        Lens lens = lensMapper.selectById(lensId);
        if (lens == null) {
            throw new BizException(BizCode.LENS_NOT_FOUND);
        }
        if (!LensService.ACTIVE.equals(lens.getLensStatus())) {
            throw new BizException(BizCode.LENS_NOT_ACTIVE);
        }
        return lens;
    }

    private void checkVersion(ProcessOrder order, OrderActionRequest req) {
        if (req != null && req.getVersionNo() != null
                && !req.getVersionNo().equals(order.getVersionNo())) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
    }

    private void writeLog(Long orderId, String operType, String fromStatus,
                          String toStatus, String remark, Long versionNo) {
        ProcessOrderStatusLog log = new ProcessOrderStatusLog();
        log.setOrderId(orderId);
        log.setOperType(operType);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperRemark(remark);
        log.setOperBy(RequestContext.operatorId());
        log.setRequestNo(RequestContext.requestNo());
        log.setBizTime(RequestContext.bizTime());
        log.setVersionNo(versionNo);
        statusLogMapper.insert(log);
    }
}
