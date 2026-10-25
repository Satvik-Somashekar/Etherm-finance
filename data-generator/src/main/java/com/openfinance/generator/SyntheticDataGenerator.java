package com.openfinance.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates one decrypted FI fetch response per persona, covering the five FI types Etherm
 * aggregates: deposit, mutual_funds, insurance_policies, epf and equities.
 *
 * <p>Field names follow the ReBIT FI schema conventions (Account / Profile / Summary /
 * Transactions, amounts as decimal strings). Payloads are not encrypted; in a live AA flow the
 * {@code decryptedFI} node would be the result of decrypting {@code encryptedFI}.
 *
 * <p>Output is fully determined by the seed, so evaluation questions can be answered against
 * a fixed dataset.
 */
public class SyntheticDataGenerator {

    static final String SCHEMA_VERSION = "2.0.0";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private static final String[] FIRST = {"Aarav", "Diya", "Ishaan", "Meera", "Kabir", "Ananya", "Rohan", "Saanvi", "Vihaan", "Tara"};
    private static final String[] LAST = {"Rao", "Iyer", "Sharma", "Menon", "Patel", "Gupta", "Reddy", "Nair", "Joshi", "Das"};
    private static final String[] EMPLOYERS = {"Northwind Systems Pvt Ltd", "Lakeview Analytics LLP", "Bluecrest Technologies Ltd", "Saffron Retail Pvt Ltd"};
    /**
     * How the monthly spending budget is split, from the MoSPI Household Consumption Expenditure
     * Survey 2023-24, Statement 4, urban India (share of average monthly per-capita spending).
     * Food (groceries) is cereals, pulses, sugar and salt, milk, vegetables, fruits, egg/fish/meat,
     * edible oil and spices. Dining is beverages, refreshments and processed food, which includes
     * cooked meals. Utilities is "fuel and light". Transport is "conveyance", split evenly between
     * vehicle fuel and fares. Shopping is clothing and footwear, durables and miscellaneous goods.
     * Rent is modelled separately per persona, and education, medical and services are left out.
     */
    private record SpendLine(String key, String narration, double hcesShare, int minTxns, int maxTxns) {
    }

    private static final SpendLine[] SPEND = {
            new SpendLine("groceries", "UPI/FRESHMART/groceries", 28.59, 4, 6),
            new SpendLine("food", "UPI/QUICKBITE/food order", 11.09, 4, 7),
            new SpendLine("utilities", "UPI/POWERGRID/electricity bill", 5.59, 1, 1),
            new SpendLine("fuel", "UPI/CITYFUELS/fuel", 4.23, 1, 3),
            new SpendLine("transport", "UPI/METRORIDE/travel", 4.23, 4, 8),
            new SpendLine("shopping", "UPI/URBANCART/shopping", 19.45, 1, 3),
    };
    private static final double SPEND_SHARE_TOTAL = java.util.Arrays.stream(SPEND).mapToDouble(SpendLine::hcesShare).sum();

    private final ObjectMapper mapper = new ObjectMapper();
    private final PriceBook prices = PriceBook.load();
    private final long seed;
    private final LocalDate from;
    private final LocalDate to;

    public SyntheticDataGenerator(long seed, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        this.seed = seed;
        this.from = from;
        this.to = to;
    }

    public Persona persona(int index) {
        Random rng = rngFor(index, "persona");
        String first = FIRST[index % FIRST.length];
        String last = LAST[(index * 3 + 1) % LAST.length];
        BigDecimal salary = BigDecimal.valueOf(60_000 + rng.nextInt(14) * 10_000L).setScale(2);
        BigDecimal rent = salary.multiply(new BigDecimal("0.25")).setScale(-3, RoundingMode.HALF_UP).setScale(2);
        return new Persona(
                String.format("user%03d@etherm-aa", index + 1),
                first + " " + last,
                LocalDate.of(1988 + rng.nextInt(12), 1 + rng.nextInt(12), 1 + rng.nextInt(28)),
                String.format("%s%s%c%04d%c", "ABC", "P", last.charAt(0), 1000 + rng.nextInt(9000), (char) ('A' + rng.nextInt(26))),
                String.format("9%09d", rng.nextInt(1_000_000_000)),
                (first + "." + last).toLowerCase() + "@example.com",
                EMPLOYERS[index % EMPLOYERS.length],
                String.format("1%011d", (long) rng.nextInt(1_000_000_000) * 100 + index),
                salary,
                rent);
    }

    /** Builds the full fetch response for persona {@code index}. */
    public ObjectNode generate(int index) {
        Persona p = persona(index);
        Random rng = rngFor(index, "envelope");

        List<Movement> autoDebits = new ArrayList<>();
        ArrayNode fi = mapper.createArrayNode();

        // Everything that moves money in or out of the bank account is generated first, so the
        // deposit account can book it.
        ObjectNode mf = mutualFunds(p, index, autoDebits);
        List<ObjectNode> policies = insurancePolicies(p, index, autoDebits);
        ObjectNode equities = equities(p, index, autoDebits);
        ObjectNode deposit = deposit(p, index, autoDebits);

        fi.add(fipEntry("ETHERM-BANK-FIP", deposit));
        fi.add(fipEntry("ETHERM-RTA-FIP", mf));
        ObjectNode insurer = fipEntry("ETHERM-INSURER-FIP", policies.get(0));
        for (int i = 1; i < policies.size(); i++) {
            ((ArrayNode) insurer.get("data")).add(dataEntry(policies.get(i)));
        }
        fi.add(insurer);
        fi.add(fipEntry("ETHERM-EPFO-FIP", epf(p, index)));
        fi.add(fipEntry("ETHERM-DEPOSITORY-FIP", equities));

        ObjectNode root = mapper.createObjectNode();
        root.put("ver", SCHEMA_VERSION);
        root.put("timestamp", to.plusDays(1).atStartOfDay().format(TS));
        root.put("txnid", uuid(rng).toString());
        // Simulation addition: in a live AA flow the customer is resolved from the consent/session.
        root.put("customerId", p.customerId());
        root.set("FI", fi);
        return root;
    }

    // ---------------------------------------------------------------- deposit

    private ObjectNode deposit(Persona p, int index, List<Movement> autoDebits) {
        Random rng = rngFor(index, "deposit");
        List<Txn> txns = new ArrayList<>();

        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            addIfInRange(txns, new Txn(m.atDay(1).atTime(9, 15), "CREDIT", "FT", p.monthlySalary(),
                    "NEFT/SALARY/" + p.employer().toUpperCase()));
            addIfInRange(txns, new Txn(m.atDay(5).atTime(10, 2), "DEBIT", "UPI", p.monthlyRent(),
                    "UPI/RENT/landlord"));
            // The month's non-rent budget is a share of salary, split across categories in HCES
            // proportions, then spread over a handful of payments in each category.
            double budget = p.monthlySalary().doubleValue() * (0.30 + 0.10 * rng.nextDouble()) * (0.85 + 0.30 * rng.nextDouble());
            for (SpendLine line : SPEND) {
                double categoryTotal = budget * line.hcesShare() / SPEND_SHARE_TOTAL;
                int count = line.minTxns() + rng.nextInt(line.maxTxns() - line.minTxns() + 1);
                for (BigDecimal amt : split(rng, categoryTotal, count)) {
                    LocalDateTime at = m.atDay(1 + rng.nextInt(m.lengthOfMonth())).atTime(8 + rng.nextInt(14), rng.nextInt(60));
                    addIfInRange(txns, new Txn(at, "DEBIT", "UPI", amt, line.narration()));
                }
            }
            if (rng.nextBoolean()) {
                LocalDateTime at = m.atDay(1 + rng.nextInt(m.lengthOfMonth())).atTime(18, 30);
                addIfInRange(txns, new Txn(at, "DEBIT", "ATM", BigDecimal.valueOf(1000L * (2 + rng.nextInt(4))).setScale(2),
                        "ATM/CASH WITHDRAWAL"));
            }
            if (m.getMonthValue() % 3 == 0) {
                addIfInRange(txns, new Txn(m.atEndOfMonth().atTime(23, 0), "CREDIT", "OTHERS",
                        between(rng, new BigDecimal("400"), new BigDecimal("1800")), "INT.PD/SAVINGS INTEREST"));
            }
        }
        for (Movement d : autoDebits) {
            addIfInRange(txns, new Txn(d.at(), d.type(), "OTHERS", d.amount(), d.narration()));
        }
        txns.sort(Comparator.comparing(Txn::at));

        BigDecimal balance = p.monthlySalary().multiply(BigDecimal.valueOf(3));
        ArrayNode txnArray = mapper.createArrayNode();
        int n = 0;
        for (Txn t : txns) {
            balance = "CREDIT".equals(t.type()) ? balance.add(t.amount()) : balance.subtract(t.amount());
            ObjectNode node = txnArray.addObject();
            node.put("txnId", String.format("D%02d%06d", index + 1, ++n));
            node.put("type", t.type());
            node.put("mode", t.mode());
            node.put("amount", money(t.amount()));
            node.put("currentBalance", money(balance));
            node.put("transactionTimestamp", t.at().format(TS));
            node.put("valueDate", t.at().toLocalDate().toString());
            node.put("narration", t.narration());
            node.put("reference", "REF" + Math.abs(rng.nextLong() % 1_000_000_000L));
        }

        ObjectNode account = account("deposit", maskedAcc(rng), index, "DEP", p);
        ObjectNode summary = account.putObject("summary");
        summary.put("currentBalance", money(balance));
        summary.put("currency", "INR");
        summary.put("exchgeRate", "1");
        summary.put("balanceDateTime", to.atTime(23, 59).format(TS));
        summary.put("type", "SAVINGS");
        summary.put("branch", "Vellore Main");
        summary.put("facility", "OD");
        summary.put("ifscCode", "ETHM0000123");
        summary.put("micrCode", "632002001");
        summary.put("openingDate", "2016-07-14");
        summary.put("currentODLimit", "0");
        summary.put("drawingLimit", money(balance));
        summary.put("status", "ACTIVE");
        account.set("transactions", transactions(txnArray));
        return account;
    }

    // ----------------------------------------------------------- mutual funds

    private ObjectNode mutualFunds(Persona p, int index, List<Movement> autoDebits) {
        Random rng = rngFor(index, "mf");
        List<PriceBook.Fund> all = prices.funds();
        List<PriceBook.Fund> held = List.of(all.get(index % all.size()), all.get((index + 2) % all.size()));
        String folio = String.format("%08d/%02d", rng.nextInt(100_000_000), 10 + rng.nextInt(80));

        ArrayNode txnArray = mapper.createArrayNode();
        ArrayNode holdings = mapper.createArrayNode();
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        int n = 0;

        for (PriceBook.Fund fund : held) {
            BigDecimal sip = BigDecimal.valueOf(1000L * (2 + rng.nextInt(5))).setScale(2);
            BigDecimal units = BigDecimal.ZERO;
            BigDecimal cost = BigDecimal.ZERO;
            for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
                LocalDate date = m.atDay(10);
                if (date.isBefore(from) || date.isAfter(to)) {
                    continue;
                }
                BigDecimal nav = prices.nav(fund.isin(), date);
                BigDecimal bought = sip.divide(nav, 3, RoundingMode.HALF_DOWN);
                units = units.add(bought);
                cost = cost.add(sip);
                txnArray.add(mfTxn(index, ++n, fund, folio, "BUY", sip, nav, bought, date, "SIP"));
                autoDebits.add(Movement.debit(date.atTime(6, 0), sip, "ACH/SIP/" + fund.amc().toUpperCase()));
            }
            if (rng.nextInt(3) == 0 && units.signum() > 0) {
                LocalDate date = to.minusDays(20 + rng.nextInt(60));
                BigDecimal nav = prices.nav(fund.isin(), date);
                BigDecimal sold = units.multiply(new BigDecimal("0.20")).setScale(3, RoundingMode.DOWN);
                BigDecimal proceeds = sold.multiply(nav).setScale(2, RoundingMode.HALF_UP);
                cost = cost.multiply(BigDecimal.ONE.subtract(sold.divide(units, 6, RoundingMode.HALF_UP))).setScale(2, RoundingMode.HALF_UP);
                units = units.subtract(sold);
                txnArray.add(mfTxn(index, ++n, fund, folio, "SELL", proceeds, nav, sold, date, "REDEMPTION"));
            }
            BigDecimal closingNav = prices.nav(fund.isin(), to);
            BigDecimal value = units.multiply(closingNav).setScale(2, RoundingMode.HALF_UP);
            totalCost = totalCost.add(cost);
            totalValue = totalValue.add(value);

            String type = schemeType(fund.category());
            ObjectNode h = holdings.addObject();
            h.put("amc", fund.amc());
            h.put("registrar", "ETHERM-RTA");
            h.put("schemeCode", fund.amfiCode());
            h.put("schemeOption", "GROWTH");
            h.put("schemeTypes", type);
            h.put("schemeCategory", type);
            h.put("isin", fund.isin());
            h.put("isinDescription", fund.scheme());
            h.put("amfiCode", fund.amfiCode());
            h.put("folioNo", folio);
            h.put("FatcaStatus", "YES");
            h.put("closingUnits", units.setScale(3, RoundingMode.HALF_UP).toPlainString());
            h.put("lienUnits", "0");
            h.put("lockinUnits", "0");
            h.put("nav", closingNav.toPlainString());
            h.put("navDate", to.toString());
        }

        ObjectNode account = account("mutual_funds", "XXXXXX" + folio.substring(4, 8), index, "MF", p);
        ObjectNode summary = account.putObject("summary");
        summary.put("costValue", money(totalCost));
        summary.put("currentValue", money(totalValue));
        summary.putObject("investment").putObject("holdings").set("holding", holdings);
        account.set("transactions", transactions(txnArray));
        return account;
    }

    /** The coarse scheme type the FI schema carries, from AMFI's category text. */
    private static String schemeType(String amfiCategory) {
        String c = amfiCategory.toLowerCase();
        if (c.contains("debt") || c.contains("liquid")) {
            return "DEBT";
        }
        return c.contains("hybrid") || c.contains("balanced") ? "HYBRID" : "EQUITY";
    }

    private ObjectNode mfTxn(int index, int n, PriceBook.Fund fund, String folio, String type, BigDecimal amount,
                             BigDecimal nav, BigDecimal units, LocalDate date, String narration) {
        ObjectNode t = mapper.createObjectNode();
        t.put("txnId", String.format("M%02d%06d", index + 1, n));
        t.put("amc", fund.amc());
        t.put("registrar", "ETHERM-RTA");
        t.put("schemeCode", fund.amfiCode());
        t.put("schemePlan", "DIRECT");
        t.put("isin", fund.isin());
        t.put("amfiCode", fund.amfiCode());
        t.put("folioNo", folio);
        t.put("type", type);
        t.put("amount", money(amount));
        t.put("nav", nav.toPlainString());
        t.put("navDate", date.toString());
        t.put("units", units.setScale(3, RoundingMode.HALF_UP).toPlainString());
        t.put("lock-inFlag", "N");
        t.put("lock-inDays", "0");
        t.put("mode", "PHYSICAL");
        t.put("narration", narration + " - " + fund.scheme());
        t.put("transactionDate", date.toString());
        return t;
    }

    // -------------------------------------------------------------- insurance

    private List<ObjectNode> insurancePolicies(Persona p, int index, List<Movement> autoDebits) {
        Random rng = rngFor(index, "insurance");
        List<ObjectNode> out = new ArrayList<>();
        Object[][] specs = {
                // name, type, insurer, sum assured, annual premium, start-year offset
                {"Secure Term Life Plan", "TERM", "Lotus Life Insurance", BigDecimal.valueOf(10_000_000), between(rng, new BigDecimal("11000"), new BigDecimal("19000")), 4},
                {"Family Health Shield", "HEALTH", "Banyan General Insurance", BigDecimal.valueOf(1_000_000), between(rng, new BigDecimal("14000"), new BigDecimal("26000")), 2},
        };
        int n = 0;
        for (Object[] spec : specs) {
            String name = (String) spec[0];
            String insurer = (String) spec[2];
            BigDecimal premium = ((BigDecimal) spec[4]).setScale(0, RoundingMode.HALF_UP).setScale(2);
            LocalDate start = from.minusYears((int) spec[5]).withDayOfMonth(1).plusMonths(rng.nextInt(12)).plusDays(rng.nextInt(20));
            String policyNumber = String.format("POL%02d%08d", index + 1, rng.nextInt(100_000_000));

            ArrayNode txnArray = mapper.createArrayNode();
            for (LocalDate due = start; !due.isAfter(to); due = due.plusYears(1)) {
                if (due.isBefore(from)) {
                    continue;
                }
                ObjectNode t = txnArray.addObject();
                t.put("txnId", String.format("I%02d%06d", index + 1, ++n));
                t.put("txnDate", due.toString());
                t.put("type", "PREMIUM_PAYMENT");
                t.put("amount", money(premium));
                t.put("narration", "Annual premium - " + name);
                autoDebits.add(Movement.debit(due.atTime(7, 30), premium, "ACH/PREMIUM/" + insurer.toUpperCase()));
            }

            ObjectNode account = account("insurance_policies", "XXXXXX" + policyNumber.substring(policyNumber.length() - 4), index, "INS" + n, p);
            ObjectNode summary = account.putObject("summary");
            summary.put("policyName", name);
            summary.put("policyNumber", policyNumber);
            summary.put("policyType", spec[1].toString());
            summary.put("insurer", insurer);
            summary.put("sumAssured", money((BigDecimal) spec[3]));
            summary.put("tenureYears", "TERM".equals(spec[1]) ? "30" : "1");
            summary.put("premiumAmount", money(premium));
            summary.put("premiumFrequency", "ANNUAL");
            summary.put("policyStartDate", start.toString());
            summary.put("maturityDate", ("TERM".equals(spec[1]) ? start.plusYears(30) : start.plusYears(start.until(to).getYears() + 1)).toString());
            summary.put("policyStatus", "ACTIVE");
            account.set("transactions", transactions(txnArray));
            out.add(account);
        }
        return out;
    }

    // -------------------------------------------------------------------- EPF

    private ObjectNode epf(Persona p, int index) {
        Random rng = rngFor(index, "epf");
        BigDecimal basic = p.monthlySalary().multiply(new BigDecimal("0.40")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal ee = basic.multiply(new BigDecimal("0.12")).setScale(0, RoundingMode.HALF_UP).setScale(2);
        // EPS gets 8.33% of wages capped at Rs 15,000, i.e. at most Rs 1,250; the rest of the
        // employer's 12% goes to EPF.
        BigDecimal eps = basic.min(new BigDecimal("15000")).multiply(new BigDecimal("0.0833")).setScale(0, RoundingMode.HALF_UP).setScale(2);
        BigDecimal er = ee.subtract(eps);

        BigDecimal eeBal = between(rng, new BigDecimal("150000"), new BigDecimal("450000"));
        BigDecimal erBal = eeBal.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pensionBal = between(rng, new BigDecimal("60000"), new BigDecimal("140000"));

        ArrayNode txnArray = mapper.createArrayNode();
        int n = 0;
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            LocalDate credited = m.plusMonths(1).atDay(15);
            if (credited.isAfter(to)) {
                break;
            }
            eeBal = eeBal.add(ee);
            erBal = erBal.add(er);
            pensionBal = pensionBal.add(eps);
            ObjectNode t = txnArray.addObject();
            t.put("txnId", String.format("E%02d%06d", index + 1, ++n));
            t.put("txnDate", credited.toString());
            t.put("type", "CONTRIBUTION");
            t.put("wageMonth", m.toString());
            t.put("employeeShare", money(ee));
            t.put("employerShare", money(er));
            t.put("pensionShare", money(eps));
            t.put("amount", money(ee.add(er)));
            t.put("narration", "Cont. for " + m);
            if (m.getMonthValue() == 3) {
                // Interest for the financial year, credited at 8.25% on the balances.
                BigDecimal rate = new BigDecimal("0.0825");
                BigDecimal eeInt = eeBal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                BigDecimal erInt = erBal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                eeBal = eeBal.add(eeInt);
                erBal = erBal.add(erInt);
                ObjectNode i = txnArray.addObject();
                i.put("txnId", String.format("E%02d%06d", index + 1, ++n));
                i.put("txnDate", m.atEndOfMonth().toString());
                i.put("type", "INTEREST");
                i.put("wageMonth", m.toString());
                i.put("employeeShare", money(eeInt));
                i.put("employerShare", money(erInt));
                i.put("pensionShare", "0.00");
                i.put("amount", money(eeInt.add(erInt)));
                i.put("narration", "Interest FY " + (m.getYear() - 1) + "-" + String.valueOf(m.getYear()).substring(2));
            }
        }

        ObjectNode account = account("epf", "XXXXXXXX" + p.uan().substring(8), index, "EPF", p);
        ((ObjectNode) account.get("profile").get("holders").get("holder").get(0)).put("uan", p.uan());
        ObjectNode summary = account.putObject("summary");
        summary.put("establishmentName", p.employer());
        summary.put("establishmentId", String.format("TNVLR%07d", 1000 + index));
        summary.put("memberId", String.format("TNVLR%07d%07d", 1000 + index, 100 + index));
        summary.put("dateOfJoining", "2019-08-01");
        summary.put("employeeBalance", money(eeBal));
        summary.put("employerBalance", money(erBal));
        summary.put("pensionBalance", money(pensionBal));
        summary.put("currentBalance", money(eeBal.add(erBal)));
        summary.put("balanceDate", to.toString());
        account.set("transactions", transactions(txnArray));
        return account;
    }

    // --------------------------------------------------------------- equities

    private ObjectNode equities(Persona p, int index, List<Movement> autoDebits) {
        Random rng = rngFor(index, "equities");
        List<PriceBook.Stock> all = prices.stocks();
        int count = 3 + rng.nextInt(3);
        ArrayNode holdings = mapper.createArrayNode();
        ArrayNode txnArray = mapper.createArrayNode();
        BigDecimal totalValue = BigDecimal.ZERO;
        int n = 0;

        for (int k = 0; k < count; k++) {
            PriceBook.Stock stock = all.get((index + k) % all.size());
            long units = 0;
            for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
                boolean buy = units == 0 || rng.nextInt(4) == 0;
                boolean sell = !buy && units > 5 && rng.nextInt(10) == 0;
                if (!buy && !sell) {
                    continue;
                }
                LocalDateTime at = tradingDay(m.atDay(3 + rng.nextInt(20))).atTime(10, 15 + rng.nextInt(40));
                if (at.toLocalDate().isAfter(to)) {
                    continue;
                }
                BigDecimal price = prices.close(stock.symbol(), at.toLocalDate());
                long qty = buy ? 2 + rng.nextInt(9) : Math.max(1, units / 3);
                units += buy ? qty : -qty;
                BigDecimal value = price.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                ObjectNode t = txnArray.addObject();
                t.put("txnId", String.format("Q%02d%06d", index + 1, ++n));
                t.put("orderId", String.format("ORD%010d", Math.abs(rng.nextLong() % 10_000_000_000L)));
                t.put("companyName", stock.name());
                t.put("transactionDateTime", at.format(TS));
                t.put("exchange", "NSE");
                t.put("isin", stock.isin());
                t.put("isinDescription", stock.name() + " Equity Shares");
                t.put("equityCategory", "EQUITY");
                t.put("type", buy ? "BUY" : "SELL");
                t.put("units", String.valueOf(qty));
                t.put("rate", money(price));
                t.put("tradeValue", money(value));
                t.put("narration", (buy ? "Bought " : "Sold ") + qty + " " + stock.name());
                // The money for a buy leaves the savings account the day before; a sale's proceeds come back after.
                if (buy) {
                    autoDebits.add(Movement.debit(at.toLocalDate().minusDays(1).atTime(17, 0), value, "NEFT/DEMAT/FUNDS TRANSFER"));
                } else {
                    LocalDate credited = at.toLocalDate().plusDays(1).isAfter(to) ? to : at.toLocalDate().plusDays(1);
                    autoDebits.add(Movement.credit(credited.atTime(17, 0), value, "NEFT/DEMAT/SALE PROCEEDS"));
                }
            }
            BigDecimal lastPrice = prices.close(stock.symbol(), to);
            BigDecimal value = lastPrice.multiply(BigDecimal.valueOf(units)).setScale(2, RoundingMode.HALF_UP);
            totalValue = totalValue.add(value);
            ObjectNode h = holdings.addObject();
            h.put("issuerName", stock.name());
            h.put("isin", stock.isin());
            h.put("isinDescription", stock.name() + " Equity Shares");
            h.put("units", String.valueOf(units));
            h.put("lastTradedPrice", money(lastPrice));
        }

        ObjectNode account = account("equities", String.format("XXXXXXXXXXXX%04d", rng.nextInt(10_000)), index, "EQ", p);
        ObjectNode summary = account.putObject("summary");
        summary.put("currentValue", money(totalValue));
        summary.putObject("investment").putObject("holdings").set("holding", holdings);
        account.set("transactions", transactions(txnArray));
        return account;
    }

    /** Markets are shut at weekends; a trade picked for one happens the following Monday. */
    private static LocalDate tradingDay(LocalDate day) {
        return switch (day.getDayOfWeek()) {
            case SATURDAY -> day.plusDays(2);
            case SUNDAY -> day.plusDays(1);
            default -> day;
        };
    }

    // ---------------------------------------------------------------- helpers

    private ObjectNode account(String type, String masked, int index, String refSuffix, Persona p) {
        ObjectNode account = mapper.createObjectNode();
        account.put("type", type);
        account.put("maskedAccNumber", masked);
        account.put("version", "2.0");
        account.put("linkedAccRef", String.format("LNK-%03d-%s", index + 1, refSuffix));
        ObjectNode holders = account.putObject("profile").putObject("holders");
        holders.put("type", "SINGLE");
        ObjectNode h = holders.putArray("holder").addObject();
        h.put("name", p.name());
        h.put("dob", p.dob().toString());
        h.put("mobile", p.mobile());
        h.put("nominee", "REGISTERED");
        h.put("email", p.email());
        h.put("pan", p.pan());
        h.put("ckycCompliance", "true");
        return account;
    }

    private ObjectNode transactions(ArrayNode txns) {
        ObjectNode t = mapper.createObjectNode();
        t.put("startDate", from.toString());
        t.put("endDate", to.toString());
        t.set("transaction", txns);
        return t;
    }

    private ObjectNode fipEntry(String fipId, ObjectNode account) {
        ObjectNode fip = mapper.createObjectNode();
        fip.put("fipID", fipId);
        fip.putArray("data").add(dataEntry(account));
        return fip;
    }

    private ObjectNode dataEntry(ObjectNode account) {
        ObjectNode d = mapper.createObjectNode();
        d.put("linkRefNumber", account.get("linkedAccRef").asText());
        d.put("maskedAccNumber", account.get("maskedAccNumber").asText());
        d.putObject("decryptedFI").set("account", account);
        return d;
    }

    private void addIfInRange(List<Txn> txns, Txn t) {
        LocalDate d = t.at().toLocalDate();
        if (!d.isBefore(from) && !d.isAfter(to)) {
            txns.add(t);
        }
    }

    private Random rngFor(int index, String stream) {
        return new Random(seed * 1_000_003L + index * 7_919L + stream.hashCode());
    }

    /** Splits {@code total} into {@code count} uneven payments that add up to it exactly, to the paisa. */
    private static List<BigDecimal> split(Random rng, double total, int count) {
        double[] weights = new double[count];
        double sum = 0;
        for (int i = 0; i < count; i++) {
            weights[i] = 0.4 + rng.nextDouble();
            sum += weights[i];
        }
        BigDecimal target = BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
        List<BigDecimal> parts = new ArrayList<>();
        BigDecimal used = BigDecimal.ZERO;
        for (int i = 0; i < count - 1; i++) {
            BigDecimal part = target.multiply(BigDecimal.valueOf(weights[i] / sum)).setScale(2, RoundingMode.HALF_UP).max(new BigDecimal("10.00"));
            parts.add(part);
            used = used.add(part);
        }
        parts.add(target.subtract(used).max(new BigDecimal("10.00")));
        return parts;
    }

    private static BigDecimal between(Random rng, BigDecimal lo, BigDecimal hi) {
        BigDecimal span = hi.subtract(lo);
        return lo.add(span.multiply(BigDecimal.valueOf(rng.nextDouble()))).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal drift(Random rng, BigDecimal v, double vol, int scale) {
        double factor = 1 + 0.008 + rng.nextGaussian() * vol;
        return v.multiply(BigDecimal.valueOf(Math.max(0.7, factor))).setScale(scale, RoundingMode.HALF_UP);
    }

    private static String maskedAcc(Random rng) {
        return String.format("XXXXXXXX%04d", rng.nextInt(10_000));
    }

    private static UUID uuid(Random rng) {
        return new UUID(rng.nextLong(), rng.nextLong());
    }

    static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private record Txn(LocalDateTime at, String type, String mode, BigDecimal amount, String narration) {
    }

    /** Money moving through the customer's bank account because of another account (SIP, premium, trade). */
    private record Movement(LocalDateTime at, String type, BigDecimal amount, String narration) {
        static Movement debit(LocalDateTime at, BigDecimal amount, String narration) {
            return new Movement(at, "DEBIT", amount, narration);
        }

        static Movement credit(LocalDateTime at, BigDecimal amount, String narration) {
            return new Movement(at, "CREDIT", amount, narration);
        }
    }
}
