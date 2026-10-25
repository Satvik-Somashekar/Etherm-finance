package com.openfinance.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Real public prices the synthetic portfolios are valued with: AMFI mutual fund NAVs and NSE
 * closing prices, about one point a month. Dates between two points are linearly interpolated
 * and dates outside the span use the nearest point, so a trade on any day has a price that is
 * never invented, only estimated between two real ones. See {@code resources/reference/README.md}.
 */
public final class PriceBook {

    public record Fund(String isin, String amfiCode, String amc, String scheme, String category) {
    }

    public record Stock(String symbol, String isin, String name) {
    }

    private final List<Fund> funds = new ArrayList<>();
    private final List<Stock> stocks = new ArrayList<>();
    private final Map<String, TreeMap<LocalDate, BigDecimal>> navs = new LinkedHashMap<>();
    private final Map<String, TreeMap<LocalDate, BigDecimal>> closes = new LinkedHashMap<>();

    public static PriceBook load() {
        PriceBook book = new PriceBook();
        for (String[] r : rows("funds.csv")) {
            book.funds.add(new Fund(r[0], r[1], r[2], r[3], r[4]));
        }
        for (String[] r : rows("stocks.csv")) {
            book.stocks.add(new Stock(r[0], r[1], r[2]));
        }
        for (String[] r : rows("fund_nav.csv")) {
            book.navs.computeIfAbsent(r[0], k -> new TreeMap<>()).put(LocalDate.parse(r[1]), new BigDecimal(r[2]));
        }
        for (String[] r : rows("stock_close.csv")) {
            book.closes.computeIfAbsent(r[0], k -> new TreeMap<>()).put(LocalDate.parse(r[1]), new BigDecimal(r[2]));
        }
        return book;
    }

    public List<Fund> funds() {
        return funds;
    }

    public List<Stock> stocks() {
        return stocks;
    }

    /** NAV with four decimals, like the AMFI file. */
    public BigDecimal nav(String isin, LocalDate date) {
        return interpolate(navs.get(isin), date, 4);
    }

    /** Closing price in rupees and paise. */
    public BigDecimal close(String symbol, LocalDate date) {
        return interpolate(closes.get(symbol), date, 2);
    }

    private static BigDecimal interpolate(TreeMap<LocalDate, BigDecimal> points, LocalDate date, int scale) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("no prices loaded");
        }
        var before = points.floorEntry(date);
        var after = points.ceilingEntry(date);
        if (before == null) {
            return after.getValue();
        }
        if (after == null || before.getKey().equals(after.getKey())) {
            return before.getValue();
        }
        BigDecimal span = BigDecimal.valueOf(ChronoUnit.DAYS.between(before.getKey(), after.getKey()));
        BigDecimal into = BigDecimal.valueOf(ChronoUnit.DAYS.between(before.getKey(), date));
        BigDecimal step = after.getValue().subtract(before.getValue()).multiply(into).divide(span, 10, RoundingMode.HALF_UP);
        return before.getValue().add(step).setScale(scale, RoundingMode.HALF_UP);
    }

    private static List<String[]> rows(String file) {
        try (InputStream in = PriceBook.class.getResourceAsStream("/reference/" + file)) {
            if (in == null) {
                throw new IllegalStateException("missing reference data: " + file);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String[]> out = new ArrayList<>();
            reader.readLine(); // header
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank()) {
                    out.add(line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
