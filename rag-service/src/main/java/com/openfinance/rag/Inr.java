package com.openfinance.rag;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Formats rupee amounts with Indian digit grouping: 1234567.5 -> "₹12,34,567.50". */
public final class Inr {

    private Inr() {
    }

    public static String format(BigDecimal amount) {
        BigDecimal v = amount.setScale(2, RoundingMode.HALF_UP);
        String sign = v.signum() < 0 ? "-" : "";
        String[] parts = v.abs().toPlainString().split("\\.");
        String whole = parts[0];
        StringBuilder grouped = new StringBuilder();
        if (whole.length() > 3) {
            String head = whole.substring(0, whole.length() - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) {
                    grouped.append(',');
                }
                grouped.append(head.charAt(i));
            }
            grouped.append(',').append(whole.substring(whole.length() - 3));
        } else {
            grouped.append(whole);
        }
        return sign + "₹" + grouped + "." + parts[1];
    }
}
