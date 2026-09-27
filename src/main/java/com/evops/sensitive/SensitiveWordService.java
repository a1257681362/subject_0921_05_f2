package com.evops.sensitive;

import com.evops.common.BizCode;
import com.evops.common.BizException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 加工单文本入口管控：扫描 customerName / remark，按词级分级处理。
 * BLOCK 拦截：命中记录独立事务留痕后整单拒绝；
 * MASK 打码：命中区间字符替换为 * 后落库，命中记录随主事务写入。
 */
@Service
public class SensitiveWordService {

    public static final char MASK_CHAR = '*';

    @Resource
    private SensitiveWordLibrary wordLibrary;
    @Resource
    private SensitiveHitLogService hitLogService;

    /**
     * 扫描加工单文本字段。含拦截级命中时抛 SENSITIVE_WORD_BLOCKED；
     * 否则返回打码后的字段值与全部命中（由调用方在加工单落库后写命中记录）。
     */
    public MaskResult checkOrderText(String customerName, String remark, String orderNo) {
        List<FieldHit> hits = new ArrayList<>();
        hits.addAll(scanField("customerName", customerName));
        hits.addAll(scanField("remark", remark));

        List<FieldHit> blocked = hits.stream()
                .filter(fh -> SensitiveWordTrie.LEVEL_BLOCK.equals(fh.getHit().getLevel()))
                .collect(Collectors.toList());
        if (!blocked.isEmpty()) {
            hitLogService.recordBlocked(blocked, orderNo);
            throw new BizException(BizCode.SENSITIVE_WORD_BLOCKED, describe(blocked));
        }

        MaskResult result = new MaskResult();
        result.customerName = mask(customerName, hitsOf("customerName", hits));
        result.remark = mask(remark, hitsOf("remark", hits));
        result.hits = hits;
        return result;
    }

    /** 单字段扫描（供 scan 接口对前端实时标记） */
    public List<SensitiveHit> scan(String text) {
        return wordLibrary.scan(text);
    }

    private List<FieldHit> scanField(String fieldName, String value) {
        List<FieldHit> fieldHits = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return fieldHits;
        }
        for (SensitiveHit hit : wordLibrary.scan(value)) {
            fieldHits.add(new FieldHit(fieldName, hit));
        }
        return fieldHits;
    }

    private List<SensitiveHit> hitsOf(String fieldName, List<FieldHit> hits) {
        return hits.stream()
                .filter(fh -> fieldName.equals(fh.getFieldName()))
                .map(FieldHit::getHit)
                .collect(Collectors.toList());
    }

    /** 打码：命中区间 [start, end) 的字符全部替换为 *（含插空分隔符） */
    private String mask(String value, List<SensitiveHit> hits) {
        if (value == null || hits.isEmpty()) {
            return value;
        }
        char[] chars = value.toCharArray();
        for (SensitiveHit hit : hits) {
            for (int i = hit.getStart(); i < hit.getEnd() && i < chars.length; i++) {
                chars[i] = MASK_CHAR;
            }
        }
        return new String(chars);
    }

    private String describe(List<FieldHit> blocked) {
        return blocked.stream()
                .map(fh -> fh.getHit().getWord() + "（" + fh.getFieldName()
                        + "[" + fh.getHit().getStart() + "," + fh.getHit().getEnd() + ")）")
                .collect(Collectors.joining("；"));
    }

    /** 打码结果：字段打码后的值 + 全部命中（含打码级，供落命中记录） */
    public static class MaskResult {
        private String customerName;
        private String remark;
        private List<FieldHit> hits;

        public String getCustomerName() { return customerName; }
        public String getRemark() { return remark; }
        public List<FieldHit> getHits() { return hits; }
    }
}
