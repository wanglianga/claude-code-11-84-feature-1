package com.fire.fieldgov.mapping;

import com.fire.fieldgov.catalog.CatalogService;
import com.fire.fieldgov.model.FormInstance;
import com.fire.fieldgov.model.MappedValue;
import com.fire.fieldgov.model.MappingContract;
import com.fire.fieldgov.model.MappingValue;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;

/**
 * 映射引擎：把表单源代码取值翻译为市级标准值。
 * 铁律：
 * 1) 同名不自动合并——只走带证据的契约逐值映射；
 * 2) 契约必须在实例提交的目录版本内（contract.version ≤ catalog_version）且生效区间覆盖业务事件日期；
 * 3) equivalent=false 的取值直接拒绝；DRAFT/无契约 = 语义未决，不得进入正式统计；
 * 4) 数值字段按 transform 换算（如 x*10：厘米→毫米），保留 4 位小数并去尾零。
 */
@Service
public class MappingEngine {

    private final CatalogService catalog;

    public MappingEngine(CatalogService catalog) { this.catalog = catalog; }

    public boolean isStandardField(String fieldId) {
        return fieldId != null && fieldId.startsWith("std.");
    }

    /**
     * 映射单个表单取值。
     * @return 映射结果（含证据链）；equivalent=false 表示被拒绝；无契约时 targetValue=null
     */
    public MappedValue map(FormInstance instance, String sourceFieldId, String sourceValue) {
        LocalDate eventDate = instance.inspectDate();
        if (isStandardField(sourceFieldId)) {
            // 表单直接采用市级标准字段：恒等，无需契约
            return new MappedValue(sourceFieldId, sourceValue, sourceFieldId, sourceValue,
                    "IDENTITY", null, "x*1", "表单直接采用市级标准字段，恒等映射", true);
        }
        MappingContract contract = catalog.allContracts().stream()
                .filter(c -> c.sourceFieldId().equals(sourceFieldId))
                // SUPERSEDED 表示被新版本取代，但对提交时仍生效的旧表单依旧有效；
                // 是否适用由“版本 ≤ 表单目录版本 + 生效区间覆盖事件日期”共同约束，同名不会串义
                .filter(c -> "APPROVED".equals(c.status()) || "SUPERSEDED".equals(c.status()))
                // 提交时目录版本固定：新版本契约不得回溯解释旧表单
                .filter(c -> c.version() <= instance.catalogVersion())
                .filter(c -> !c.effectiveFrom().isAfter(eventDate))
                .filter(c -> c.effectiveTo() == null || !c.effectiveTo().isBefore(eventDate))
                .max(Comparator.comparingInt(MappingContract::version))
                .orElse(null);
        if (contract == null) {
            return new MappedValue(sourceFieldId, sourceValue, null, null,
                    null, null, null, "无生效的已批准映射契约（语义未决/契约尚未批准）", false);
        }
        MappingValue row = contract.values().stream()
                .filter(v -> v.sourceValue().equals(sourceValue))
                .findFirst()
                .orElseGet(() -> contract.values().stream()
                        .filter(v -> "*".equals(v.sourceValue())).findFirst().orElse(null));
        if (row == null) {
            return new MappedValue(sourceFieldId, sourceValue, contract.targetFieldId(), null,
                    contract.contractId(), contract.version(), null,
                    "契约中不存在该取值的逐值证据", false);
        }
        if (!row.equivalent()) {
            return new MappedValue(sourceFieldId, sourceValue, contract.targetFieldId(), null,
                    contract.contractId(), contract.version(), row.transform(),
                    row.evidence(), false);
        }
        String target = row.targetValue();
        if (row.transform() != null && !row.transform().isBlank()) {
            target = applyTransform(row.transform(), sourceValue);
        }
        return new MappedValue(sourceFieldId, sourceValue, contract.targetFieldId(), target,
                contract.contractId(), contract.version(), row.transform(), row.evidence(), true);
    }

    /** 显式校验入口：无法证明等价时抛 GovernanceException，供治理界面/API 直接演示“拒绝”。 */
    public MappedValue mapOrReject(FormInstance instance, String sourceFieldId, String sourceValue) {
        MappedValue v = map(instance, sourceFieldId, sourceValue);
        if (!v.equivalent() || v.targetValue() == null) {
            throw new com.fire.fieldgov.web.GovernanceException(
                    "映射被拒绝：字段 " + sourceFieldId + " 取值 " + sourceValue
                            + " 无法证明语义等价（" + v.evidence() + "）");
        }
        return v;
    }

    /** 解析形如 x*10、x*1 的换算公式。 */
    public String applyTransform(String transform, String sourceValue) {
        String t = transform.replaceAll("\\s+", "");
        BigDecimal x = new BigDecimal(sourceValue);
        BigDecimal result;
        if (t.matches("x\\*[0-9.]+")) {
            result = x.multiply(new BigDecimal(t.substring(2)));
        } else if (t.matches("x/[0-9.]+")) {
            result = x.divide(new BigDecimal(t.substring(2)), 6, RoundingMode.HALF_UP);
        } else if (t.equals("x*1") || t.equals("x")) {
            result = x;
        } else {
            throw new IllegalArgumentException("不支持的换算公式: " + transform);
        }
        return result.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
