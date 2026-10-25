package com.openfinance.rag;

import com.openfinance.core.compute.BreakdownItem;
import com.openfinance.core.compute.ComputedAnswer;
import com.openfinance.core.compute.Figure;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The computed figures as named placeholders ({@code {total}}, {@code {item1_value}}, ...) and the
 * formatted values that replace them. A phraser writes text with placeholders; only
 * {@link #fill(String)} puts numbers into the sentence, and every number it inserts came from Java.
 */
public final class FactSheet {

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new LinkedHashMap<>();

    public static FactSheet of(ComputedAnswer answer) {
        FactSheet s = new FactSheet();
        for (Figure f : answer.figures()) {
            String formatted = switch (f.unit()) {
                case INR -> Inr.format(f.value());
                case COUNT -> f.value().stripTrailingZeros().toPlainString();
                case PERCENT -> f.value().abs().stripTrailingZeros().toPlainString() + "%";
            };
            s.put(f.key(), formatted, f.label());
        }
        List<BreakdownItem> items = answer.breakdown();
        for (int i = 0; i < Math.min(items.size(), 5); i++) {
            s.put("item" + (i + 1) + "_value", Inr.format(items.get(i).value()), "value of: " + items.get(i).label());
            s.values.put("item" + (i + 1) + "_label", items.get(i).label());
        }
        return s;
    }

    private void put(String key, String value, String description) {
        values.put(key, value);
        descriptions.put(key, description);
    }

    public Map<String, String> descriptions() {
        return descriptions;
    }

    public String value(String key) {
        return values.get(key);
    }

    public String label(int item) {
        return values.get("item" + item + "_label");
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** Replaces every {key} placeholder with its formatted value; unknown placeholders are left as is. */
    public String fill(String template) {
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    /** Text with every known placeholder removed, used to check a model wrote no digits of its own. */
    public String stripPlaceholders(String template) {
        String out = template;
        for (String key : values.keySet()) {
            out = out.replace("{" + key + "}", "");
        }
        return out;
    }

    static boolean isNegative(Figure f) {
        return f != null && f.value().compareTo(BigDecimal.ZERO) < 0;
    }
}
