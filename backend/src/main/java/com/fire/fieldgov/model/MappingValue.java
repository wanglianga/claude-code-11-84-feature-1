package com.fire.fieldgov.model;

import java.time.LocalDate;

/** 逐值映射行。equivalent=false 表示无法证明语义等价，编译期必须拒绝。 */
public record MappingValue(
        String sourceValue,
        String targetValue,
        String transform,
        String evidence,
        boolean equivalent) {
}
