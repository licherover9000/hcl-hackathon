package com.threepointers.recoverplus.engine;

import com.threepointers.recoverplus.engine.EngineModels.CustomerFlags;
import com.threepointers.recoverplus.engine.EngineModels.Position;
import com.threepointers.recoverplus.engine.EngineModels.SettlementDecision;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Settlement eligibility and routing (section 6.1.2), computed on the loan's position
 * at the request date.
 *  - On hold: not eligible, ACCOUNT_ON_HOLD (checked first: worked example S0006).
 *  - Eligible only for NPA, or SMA_2 with DPD at or above the configured minimum.
 *  - discount_pct = (total_dues - offer) / total_dues x 100, 0 if negative.
 *  - Route: up to manager limit to COLLECTIONS_MANAGER, up to head limit to
 *    HEAD_OF_COLLECTIONS, above that REJECT with DISCOUNT_ABOVE_LIMIT.
 */
public final class SettlementEvaluator {

    private SettlementEvaluator() {
    }

    public static SettlementDecision evaluate(Position pos, CustomerFlags flags, BigDecimal offer, RulesConfig cfg) {
        BigDecimal discount = discountPct(pos.totalDues(), offer);
        if (flags.onHold()) {
            return new SettlementDecision(false, discount, "REJECT", "ACCOUNT_ON_HOLD");
        }
        boolean bucketOk = "NPA".equals(pos.bucket())
                || ("SMA_2".equals(pos.bucket()) && pos.dpd() >= cfg.settlementMinDpdSma2());
        if (!bucketOk) {
            return new SettlementDecision(false, discount, "REJECT", "NOT_ELIGIBLE_BUCKET");
        }
        if (discount.compareTo(cfg.managerLimitPct()) <= 0) {
            return new SettlementDecision(true, discount, "COLLECTIONS_MANAGER", "");
        }
        if (discount.compareTo(cfg.headLimitPct()) <= 0) {
            return new SettlementDecision(true, discount, "HEAD_OF_COLLECTIONS", "");
        }
        return new SettlementDecision(true, discount, "REJECT", "DISCOUNT_ABOVE_LIMIT");
    }

    public static BigDecimal discountPct(BigDecimal totalDues, BigDecimal offer) {
        if (totalDues.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal pct = totalDues.subtract(offer)
                .multiply(BigDecimal.valueOf(100))
                .divide(totalDues, MathContext.DECIMAL128);
        if (pct.signum() < 0) {
            pct = BigDecimal.ZERO;
        }
        return pct.setScale(2, RoundingMode.HALF_UP);
    }
}
