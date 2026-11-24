package com.fire.fieldgov.model;

import java.time.LocalDate;
import java.util.Map;

/** 一次表单实例及其字段取值（不可变）。 */
public record FormInstance(
        String instanceId,
        String caseId,
        String templateId,
        String district,
        int catalogVersion,
        LocalDate inspectDate,
        Map<String, String> values) {
}
