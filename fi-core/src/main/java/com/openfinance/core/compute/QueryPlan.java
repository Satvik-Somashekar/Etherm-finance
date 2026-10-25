package com.openfinance.core.compute;

import com.openfinance.core.model.Category;
import com.openfinance.core.model.FiType;

import java.util.Set;

/**
 * What a question asks for, in structured form. The language model never builds this; the
 * question parser does, deterministically.
 *
 * @param period      the time window for flow questions; null means the whole consented range
 * @param compare     also compute the same figure for the preceding period of equal length
 * @param subject     a free-text narrowing such as a policy type, used by some intents
 */
public record QueryPlan(
        Intent intent,
        Set<FiType> fiTypes,
        Set<Category> categories,
        DateRange period,
        boolean compare,
        int topN,
        String subject) {

    public QueryPlan {
        fiTypes = fiTypes == null ? Set.of() : Set.copyOf(fiTypes);
        categories = categories == null ? Set.of() : Set.copyOf(categories);
    }

    public static QueryPlan of(Intent intent) {
        return new QueryPlan(intent, Set.of(), Set.of(), null, false, 5, null);
    }

    public QueryPlan withPeriod(DateRange range) {
        return new QueryPlan(intent, fiTypes, categories, range, compare, topN, subject);
    }

    public QueryPlan withCategories(Set<Category> cats) {
        return new QueryPlan(intent, fiTypes, cats, period, compare, topN, subject);
    }

    public QueryPlan withFiTypes(Set<FiType> types) {
        return new QueryPlan(intent, types, categories, period, compare, topN, subject);
    }

    public QueryPlan withCompare(boolean value) {
        return new QueryPlan(intent, fiTypes, categories, period, value, topN, subject);
    }

    public QueryPlan withTopN(int n) {
        return new QueryPlan(intent, fiTypes, categories, period, compare, n, subject);
    }

    public QueryPlan withSubject(String s) {
        return new QueryPlan(intent, fiTypes, categories, period, compare, topN, s);
    }
}
