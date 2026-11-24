package com.fire.fieldgov.reliability;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgreementStatsTest {

    private List<AgreementStats.Pair> pairs(int[][] ab) {
        List<AgreementStats.Pair> out = new ArrayList<>();
        for (int[] p : ab) out.add(new AgreementStats.Pair(p[0], p[1]));
        return out;
    }

    @Test
    void perfectAgreementYieldsUnity() {
        var r = AgreementStats.analyze(pairs(new int[][]{{1, 1}, {2, 2}, {3, 3}, {4, 4}, {2, 2}, {3, 3}}));
        assertEquals(1.0, r.exactAgreement(), 1e-9);
        assertEquals(1.0, r.weightedAgreementQuadratic(), 1e-9);
        assertEquals(1.0, r.kappaQuadratic(), 1e-9);
        assertEquals(0.0, r.meanSignedDiff(), 1e-9);
        assertEquals("NO_SIGNIFICANT_BIAS", r.biasDirection());
    }

    @Test
    void weightsPenalizeDistanceLinearlyAndQuadratically() {
        var one = List.of(new AgreementStats.Pair(1, 2));
        // (1,2): linear 2/3, quadratic 8/9 ; (1,4): both 0
        assertEquals(2.0 / 3.0, AgreementStats.weightedAgreement(one, 4, 1), 1e-12);
        assertEquals(8.0 / 9.0, AgreementStats.weightedAgreement(one, 4, 2), 1e-12);
        var far = List.of(new AgreementStats.Pair(1, 4));
        assertEquals(0.0, AgreementStats.weightedAgreement(far, 4, 2), 1e-12);
        // 记录层保留 4 位小数
        var rec = AgreementStats.analyze(one);
        assertEquals(0.6667, rec.weightedAgreementLinear(), 1e-4);
    }

    @Test
    void strongVersusWeakDatasetsAreSeparated() {
        // 与种子 std.li_iso_level 同构的高一致数据
        var strong = pairs(new int[][]{
                {1, 1}, {1, 1}, {2, 3}, {2, 3}, {3, 2},
                {2, 2}, {2, 2}, {3, 2}, {4, 4}, {3, 3}, {4, 3}, {4, 4},
                {1, 2}, {1, 1}, {2, 1}, {3, 3}, {3, 3}, {2, 2},
                {2, 2}, {2, 2}, {3, 3}, {3, 3}, {3, 4}, {3, 3},
                {1, 1}, {1, 1}, {2, 2}, {3, 3}, {3, 3}, {2, 2},
                {2, 2}, {2, 2}, {3, 3}, {3, 3}, {3, 3}, {4, 4}});
        var rs = AgreementStats.analyze(strong);
        assertTrue(rs.kappaQuadratic() > 0.7, "strong kappa should be > .7, was " + rs.kappaQuadratic());
        assertNotNull(rs.kappaQuadraticCi95());
        assertTrue(rs.kappaQuadraticCi95().lower() > 0.3);

        // 与种子 std.li_photo_grade 同构的低一致数据
        var weak = pairs(new int[][]{
                {1, 2}, {1, 3}, {2, 1}, {2, 3}, {2, 4}, {3, 2}, {3, 4}, {3, 1}, {4, 3}, {4, 2},
                {1, 1}, {2, 2}, {3, 3}, {4, 4}, {2, 3}, {3, 2}, {1, 4}, {4, 3}});
        var rw = AgreementStats.analyze(weak);
        assertTrue(rw.kappaQuadratic() < 0.4, "weak kappa should be < .4, was " + rw.kappaQuadratic());
        // 二次加权一致率因“部分给分”仍可能偏高，但其 Kappa 很低 —— 证明必须看机会校正指标
        assertTrue(rw.weightedAgreementQuadratic() > rw.kappaQuadratic());
    }

    @Test
    void systematicBiasDirectionAndSignificance() {
        List<AgreementStats.Pair> lenient = new ArrayList<>();
        for (int i = 0; i < 24; i++) lenient.add(new AgreementStats.Pair(3, 2)); // B 恒低一级
        var r = AgreementStats.analyze(lenient);
        assertEquals(-1.0, r.meanSignedDiff(), 1e-9);
        assertTrue(r.biasSignificant());
        assertEquals("DOWNWARD", r.biasDirection());
        assertNotNull(r.biasCi95());
        assertTrue(r.biasCi95().upper() < 0);
    }

    @Test
    void gatePassesStrongAndBlocksWeak() {
        var t = new ReliabilityGate.Thresholds(12, 0.85, 0.60, 0.40, 0.50);
        var strong = pairs(new int[][]{
                {1, 1}, {1, 1}, {2, 3}, {2, 3}, {3, 2},
                {2, 2}, {2, 2}, {3, 2}, {4, 4}, {3, 3}, {4, 3}, {4, 4},
                {1, 2}, {1, 1}, {2, 1}, {3, 3}, {3, 3}, {2, 2},
                {2, 2}, {2, 2}, {3, 3}, {3, 3}, {3, 4}, {3, 3},
                {1, 1}, {1, 1}, {2, 2}, {3, 3}, {3, 3}, {2, 2},
                {2, 2}, {2, 2}, {3, 3}, {3, 3}, {3, 3}, {4, 4}});
        var vs = ReliabilityGate.evaluate("f", AgreementStats.analyze(strong), t);
        assertTrue(vs.rankingEligible(), String.join(";", vs.blockingReasons()));

        var weak = pairs(new int[][]{
                {1, 2}, {1, 3}, {2, 1}, {2, 3}, {2, 4}, {3, 2}, {3, 4}, {3, 1}, {4, 3}, {4, 2},
                {1, 1}, {2, 2}, {3, 3}, {4, 4}, {2, 3}, {3, 2}, {1, 4}, {4, 3}});
        var vw = ReliabilityGate.evaluate("f2", AgreementStats.analyze(weak), t);
        assertFalse(vw.rankingEligible());
        assertEquals("NOT_ELIGIBLE", vw.status());
        assertFalse(vw.blockingReasons().isEmpty());

        var empty = ReliabilityGate.evaluate("f3", AgreementStats.analyze(List.of()), t);
        assertFalse(empty.rankingEligible());
        assertEquals("INSUFFICIENT_DATA", empty.status());
    }

    @Test
    void hierarchicalModelRejectsSparseAndFitsCrossedData() {
        var req = new HierarchicalModel.MinRequirements(16, 5, 3, 6, 2);

        var sparse = HierarchicalModel.fit(
                List.of(new HierarchicalModel.Obs(1, "S1", "I1"),
                        new HierarchicalModel.Obs(2, "S2", "I2")), 0, req);
        assertFalse(sparse.estimable());
        assertFalse(sparse.missingRequirements().isEmpty());

        // 6 场所 × 3 检查员的强场所信号交叉数据
        double[] siteLevel = {1, 1.6, 2.2, 2.8, 3.4, 4};
        double[] inspShift = {0.0, -0.15, 0.15};
        List<HierarchicalModel.Obs> data = new ArrayList<>();
        for (int s = 0; s < 6; s++) {
            for (int j = 0; j < 3; j++) {
                data.add(new HierarchicalModel.Obs(
                        Math.max(1, Math.min(4, siteLevel[s] + inspShift[j])),
                        "S" + s, "I" + j));
            }
        }
        var fit = HierarchicalModel.fit(data, 8, req);
        assertTrue(fit.estimable(), String.join(";", fit.missingRequirements()));
        assertEquals(6, fit.siteCount());
        assertEquals(3, fit.inspectorCount());
        assertTrue(fit.shareSite() > fit.shareInspector());
        assertEquals(1.0, fit.shareSite() + fit.shareInspector() + fit.shareResidual(), 1e-6);
        assertEquals(6, fit.siteEffects().size());
        assertEquals(3, fit.inspectorEffects().size());
    }
}
