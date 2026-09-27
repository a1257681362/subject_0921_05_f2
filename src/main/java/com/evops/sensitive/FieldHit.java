package com.evops.sensitive;

/** 某字段上的一次命中：字段名 + 命中详情 */
public class FieldHit {

    private final String fieldName;
    private final SensitiveHit hit;

    public FieldHit(String fieldName, SensitiveHit hit) {
        this.fieldName = fieldName;
        this.hit = hit;
    }

    public String getFieldName() { return fieldName; }
    public SensitiveHit getHit() { return hit; }
}
