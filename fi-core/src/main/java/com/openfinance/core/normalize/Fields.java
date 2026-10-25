package com.openfinance.core.normalize;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Strict readers for ReBIT fields, which arrive as strings. A missing required field is an error. */
final class Fields {

    private Fields() {
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new NormalizationException("missing field '" + field + "'");
        }
        return v.asText();
    }

    static String optText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static BigDecimal decimal(JsonNode node, String field) {
        String raw = text(node, field);
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new NormalizationException("field '" + field + "' is not a decimal: " + raw);
        }
    }

    /** Accepts an ISO date or an ISO timestamp; only the date part is kept. */
    static LocalDate date(JsonNode node, String field) {
        String raw = text(node, field);
        try {
            return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw);
        } catch (RuntimeException e) {
            throw new NormalizationException("field '" + field + "' is not a date: " + raw);
        }
    }

    static Iterable<JsonNode> array(JsonNode node, String pointer) {
        JsonNode v = node.at(pointer);
        if (v.isMissingNode() || v.isNull()) {
            return java.util.List.of();
        }
        if (!v.isArray()) {
            throw new NormalizationException(pointer + " must be an array");
        }
        return v;
    }
}
