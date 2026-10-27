package com.openfinance.core.compute;

/**
 * What a suggestion is about. The Knowledge Base section the customer picked decides this for generic
 * questions, so a question asked inside Insurance is answered from insurance records only.
 */
public enum AdviceDomain {
    /** Everyday spending and saving; the default. */
    SPENDING,
    INSURANCE,
    MUTUAL_FUNDS,
    EQUITIES,
    EPF,
    /** Net worth mix and an emergency cushion, across all accounts. */
    OVERALL;

    /** The domain a plan asks for; a plan whose subject is not a domain name (or is null) is about spending. */
    public static AdviceDomain of(QueryPlan plan) {
        if (plan.subject() != null) {
            for (AdviceDomain d : values()) {
                if (d.name().equals(plan.subject())) {
                    return d;
                }
            }
        }
        return SPENDING;
    }
}
