package com.fire.fieldgov.model;

import java.time.LocalDate;
import java.util.List;

/** 源代码字段 -> 标准字段的映射契约（带生效区间、审查状态与逐值证据）。 */
public record MappingContract(
        String contractId,
        String sourceFieldId,
        String targetFieldId,
        String sourceDistrict,
        int version,
        String status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String evidenceNote,
        String reviewedBy,
        List<MappingValue> values) {
}
