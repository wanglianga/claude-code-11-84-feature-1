package com.fire.fieldgov.model;

/** 单个字段的标准化结果，携带证据链，便于查询编译追踪与审计。 */
public record MappedValue(
        String sourceFieldId,
        String sourceValue,
        String targetFieldId,
        String targetValue,
        String contractId,
        Integer contractVersion,
        String transform,
        String evidence,
        boolean equivalent) {
}
