package com.openfinance.core.oracle;

import java.util.List;

/**
 * A rule's finding. The action text comes from the rule, not the model, and stays at allocation and
 * risk level: it never names a security or says buy or sell.
 */
public record Recommendation(
        String ruleId,
        String finding,
        List<ResultObject> numbers,
        List<Assumption> assumptions,
        List<String> recordIds,
        SuggestedAction suggestedAction,
        String disclaimer) {

    public Recommendation {
        numbers = numbers == null ? List.of() : List.copyOf(numbers);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        recordIds = recordIds == null ? List.of() : List.copyOf(recordIds);
    }

    /** An input the rule or projection used that is not in the customer's data; the user may change it. */
    public record Assumption(String name, String value, String unit, boolean adjustable, String source) {
    }

    public record SuggestedAction(Code code, String text) {
        public enum Code {
            REBALANCE,
            REDUCE_CONCENTRATION,
            BUILD_EMERGENCY_BUFFER,
            REDEPLOY_MATURING_AMOUNT,
            REVIEW_DEPOSIT_COVER,
            REVIEW_INSURANCE_COVER,
            CONSIDER_LONG_TERM_TIMING,
            CONSIDER_LOSS_HARVESTING,
            PUT_IDLE_CASH_TO_WORK,
            PLAN_FOR_OBLIGATION
        }
    }
}
