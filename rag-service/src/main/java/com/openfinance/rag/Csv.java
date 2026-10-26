package com.openfinance.rag;

import java.math.BigDecimal;
import java.util.List;

/** Minimal RFC 4180 writer. Text cells from records are data, so ones a spreadsheet would run as a formula are defused. */
final class Csv {

    private static final String EOL = "\r\n";

    private final StringBuilder out = new StringBuilder();

    void row(List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(quote(cells.get(i)));
        }
        out.append(EOL);
    }

    @Override
    public String toString() {
        return out.toString();
    }

    /** A text cell: quoted when needed, and prefixed with an apostrophe if it starts like a formula. */
    static String text(String value) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    /** A number as plain digits with two decimals, never in scientific notation. */
    static String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String quote(String cell) {
        if (cell == null) {
            return "";
        }
        if (cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0) {
            return '"' + cell.replace("\"", "\"\"") + '"';
        }
        return cell;
    }
}
