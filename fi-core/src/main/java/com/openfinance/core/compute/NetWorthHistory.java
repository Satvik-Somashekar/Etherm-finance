package com.openfinance.core.compute;

import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Month-end net worth, rebuilt backwards from each account's latest balance by undoing the money
 * that moved in or out afterwards. Holdings are valued at the latest prices throughout, so the
 * history shows the effect of saving, spending and investing, not market movements.
 */
public class NetWorthHistory {

    public static final Set<FiType> ASSET_TYPES =
            EnumSet.of(FiType.DEPOSIT, FiType.MUTUAL_FUNDS, FiType.EPF, FiType.EQUITIES);

    /** What retrieval must return: balances and transactions of the asset accounts. */
    public static RecordFilter filter() {
        return new RecordFilter(ASSET_TYPES, Set.of(RecordKind.BALANCE, RecordKind.TRANSACTION), Set.of(), null, null, null);
    }

    /**
     * @param value     net worth at the end of {@code month}
     * @param recordIds the balance records the value starts from, plus every transaction undone
     */
    public record Point(YearMonth month, LocalDate date, BigDecimal value, List<String> recordIds) {
    }

    /** One point per month from the earliest transaction's month to the latest balance's month. */
    public List<Point> compute(List<FinancialRecord> records) {
        List<FinancialRecord> balances = records.stream()
                .filter(r -> r.kind() == RecordKind.BALANCE && ASSET_TYPES.contains(r.fiType())).toList();
        if (balances.isEmpty()) {
            return List.of();
        }
        Map<String, FinancialRecord> balanceByAccount = balances.stream()
                .collect(Collectors.toMap(FinancialRecord::accountId, r -> r, (a, b) -> a.date().isAfter(b.date()) ? a : b));
        List<FinancialRecord> flows = records.stream()
                .filter(r -> r.kind() == RecordKind.TRANSACTION && balanceByAccount.containsKey(r.accountId()))
                .sorted(Comparator.comparing(FinancialRecord::date).thenComparing(FinancialRecord::recordId))
                .toList();

        LocalDate asOf = balanceByAccount.values().stream().map(FinancialRecord::date)
                .max(Comparator.naturalOrder()).orElseThrow();
        YearMonth last = YearMonth.from(asOf);
        YearMonth first = flows.isEmpty() ? last : YearMonth.from(flows.getFirst().date());

        List<Point> points = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            LocalDate end = m.atEndOfMonth();
            BigDecimal total = BigDecimal.ZERO;
            List<String> ids = new ArrayList<>();
            for (FinancialRecord balance : balanceByAccount.values()) {
                BigDecimal value = balance.amount();
                ids.add(balance.recordId());
                // Undo flows after this month end, up to the balance date.
                for (FinancialRecord t : flows) {
                    if (t.accountId().equals(balance.accountId()) && t.date().isAfter(end)
                            && !t.date().isAfter(balance.date())) {
                        value = value.subtract(signedInflow(t));
                        ids.add(t.recordId());
                    }
                }
                total = total.add(value);
            }
            points.add(new Point(m, end.isAfter(asOf) ? asOf : end, total.setScale(2, RoundingMode.HALF_UP),
                    List.copyOf(ids)));
        }
        return points;
    }

    /** How much a transaction added to the account it is recorded on. */
    static BigDecimal signedInflow(FinancialRecord t) {
        if (t.fiType() == FiType.DEPOSIT) {
            return t.direction() == Direction.DEBIT ? t.amount().negate() : t.amount();
        }
        return switch (t.category()) {
            case MF_PURCHASE, EQUITY_BUY, EPF_CONTRIBUTION, EPF_INTEREST -> t.amount();
            case MF_REDEMPTION, EQUITY_SELL -> t.amount().negate();
            default -> t.direction() == Direction.DEBIT ? t.amount().negate() : t.amount();
        };
    }
}
