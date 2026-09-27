package com.evops.sensitive;

import com.evops.sensitive.entity.SensitiveHitLog;
import com.evops.sensitive.mapper.SensitiveHitLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/**
 * 敏感词命中记录（只追加，供兄弟组取数）。
 * 打码：随加工单主事务写入，同生共死；
 * 拦截：加工单整体回滚，命中记录用独立事务落库，拦截痕迹不丢。
 */
@Service
public class SensitiveHitLogService {

    public static final String BIZ_PROCESS_ORDER = "PROCESS_ORDER";

    @Resource
    private SensitiveHitLogMapper hitLogMapper;

    /** 打码命中：加入调用方事务（加工单保存成功才可见） */
    @Transactional(rollbackFor = Exception.class)
    public void recordMasked(List<FieldHit> fieldHits, Long orderId, String orderNo) {
        for (FieldHit fh : fieldHits) {
            hitLogMapper.insert(toLog(fh, orderId, orderNo, SensitiveWordTrie.LEVEL_MASK));
        }
    }

    /** 拦截命中：独立事务，主事务回滚后仍留痕（加工单未落地，bizId 为空） */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordBlocked(List<FieldHit> fieldHits, String orderNo) {
        for (FieldHit fh : fieldHits) {
            hitLogMapper.insert(toLog(fh, null, orderNo, SensitiveWordTrie.LEVEL_BLOCK));
        }
    }

    private SensitiveHitLog toLog(FieldHit fh, Long orderId, String orderNo, String actionTaken) {
        SensitiveHit hit = fh.getHit();
        SensitiveHitLog log = new SensitiveHitLog();
        log.setBizType(BIZ_PROCESS_ORDER);
        log.setBizId(orderId);
        log.setOrderNo(orderNo);
        log.setFieldName(fh.getFieldName());
        log.setHitWord(hit.getWord());
        log.setWordLevel(hit.getLevel());
        log.setStartIndex(hit.getStart());
        log.setEndIndex(hit.getEnd());
        log.setSurfaceText(hit.getSurface());
        log.setActionTaken(actionTaken);
        return log;
    }
}
