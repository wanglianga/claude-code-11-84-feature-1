package com.fire.fieldgov.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 字段目录中的一条字段定义（field_id + version 唯一）。 */
public record FieldDef(
        String fieldId,
        int version,
        String displayName,
        String definition,
        Map<String, Object> valueDomain,
        String unit,
        String granularity,
        String eventTimeMeaning,
        List<String> applicableDistricts,
        String sensitivity,
        LocalDate validFrom,
        LocalDate validTo,
        String status,
        String namespace,
        String supersededBy) {
}
