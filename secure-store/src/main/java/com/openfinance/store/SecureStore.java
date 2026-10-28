package com.openfinance.store;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * The application side of the secure store (the database side is {@code db/schema.sql}). Connects as
 * {@code etherm_app}, and runs every unit of work in one transaction that first names its customer with
 * {@code set_config('etherm.customer_id', ?, true)}. The setting is transaction-local, so a pooled connection never
 * carries one customer's identity into the next request; without it the database returns no rows at all.
 * Amounts and counterparties are sealed with the customer's data key before they are sent.
 */
public final class SecureStore {

    public record Consent(String consentId, String customerId, Set<String> fiTypes, LocalDate from, LocalDate to,
                          Instant expiresAt) {
    }

    public record Txn(String recordId, String consentId, String fiType, LocalDate date, String category,
                      String direction, BigDecimal amount, String counterparty) {
    }

    /** One entry of the consent ledger, as the customer or an auditor sees it. */
    public record LedgerEntry(long n, String consentId, String event, String detail, Instant at) {
    }

    /** A chain as {@code etherm.verify_ledger()} reports it; {@code firstBroken} is null when it is intact. */
    public record ChainCheck(String customerId, long entries, Long firstBroken, byte[] head) {
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private final String url;
    private final Properties login;
    private final KeyRing keys;

    public SecureStore(String url, String user, String password, KeyRing keys) {
        this.url = url;
        this.login = new Properties();
        login.setProperty("user", user);
        login.setProperty("password", password);
        this.keys = keys;
    }

    /** Creates the customer's data key. Must run once before anything is stored for them. */
    public void enrol(String customerId) {
        byte[] dataKey = KeyRing.newDataKey();
        try {
            as(customerId, c -> {
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO etherm.customer_keys (customer_id, kek_id, wrapped_key) VALUES (?, ?, ?)")) {
                    s.setString(1, customerId);
                    s.setString(2, keys.id());
                    s.setBytes(3, keys.wrap(customerId, dataKey));
                    s.executeUpdate();
                }
                return null;
            });
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    public void grant(Consent consent) {
        as(consent.customerId(), c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO etherm.consents "
                    + "(consent_id, customer_id, fi_types, data_from, data_to, expires_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                Array types = c.createArrayOf("text", consent.fiTypes().stream().sorted().toArray());
                s.setString(1, consent.consentId());
                s.setString(2, consent.customerId());
                s.setArray(3, types);
                s.setDate(4, Date.valueOf(consent.from()));
                s.setDate(5, Date.valueOf(consent.to()));
                s.setTimestamp(6, Timestamp.from(consent.expiresAt()));
                s.executeUpdate();
            }
            return null;
        });
    }

    /** Deletes the data the consent brought in, then marks it revoked; both are recorded in the ledger. */
    public void revoke(String customerId, String consentId) {
        as(customerId, c -> {
            try (PreparedStatement d = c.prepareStatement("DELETE FROM etherm.transactions WHERE consent_id = ?");
                 PreparedStatement u = c.prepareStatement(
                         "UPDATE etherm.consents SET status = 'REVOKED' WHERE consent_id = ? AND status = 'ACTIVE'")) {
                d.setString(1, consentId);
                d.executeUpdate();
                u.setString(1, consentId);
                if (u.executeUpdate() != 1) {
                    throw new SQLException("no active consent " + consentId + " for this customer");
                }
            }
            return null;
        });
    }

    public void store(String customerId, List<Txn> txns) {
        as(customerId, c -> {
            byte[] dataKey = dataKey(c, customerId);
            // One statement for the whole batch, so the ledger records one DATA_STORED entry per consent, not one per row.
            int n = txns.size();
            String[] ids = new String[n], consents = new String[n], types = new String[n], categories = new String[n],
                    directions = new String[n];
            Date[] dates = new Date[n];
            byte[][] amounts = new byte[n][], counterparties = new byte[n][];
            for (int i = 0; i < n; i++) {
                Txn t = txns.get(i);
                ids[i] = t.recordId();
                consents[i] = t.consentId();
                types[i] = t.fiType();
                dates[i] = Date.valueOf(t.date());
                categories[i] = t.category();
                directions[i] = t.direction();
                amounts[i] = FieldCipher.encrypt(dataKey, t.amount().toPlainString(), context(customerId, t.recordId(), "amount"));
                counterparties[i] = FieldCipher.encrypt(dataKey, t.counterparty() == null ? "" : t.counterparty(),
                        context(customerId, t.recordId(), "counterparty"));
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO etherm.transactions (record_id, customer_id, consent_id, "
                    + "fi_type, txn_date, category, direction, amount, counterparty) SELECT r, ?, k, f, d, g, x, a, p FROM "
                    + "unnest(?::text[], ?::text[], ?::text[], ?::date[], ?::text[], ?::text[], ?::bytea[], ?::bytea[]) "
                    + "AS u(r, k, f, d, g, x, a, p)")) {
                s.setString(1, customerId);
                s.setArray(2, c.createArrayOf("text", ids));
                s.setArray(3, c.createArrayOf("text", consents));
                s.setArray(4, c.createArrayOf("text", types));
                s.setArray(5, c.createArrayOf("date", dates));
                s.setArray(6, c.createArrayOf("text", categories));
                s.setArray(7, c.createArrayOf("text", directions));
                s.setArray(8, c.createArrayOf("bytea", amounts));
                s.setArray(9, c.createArrayOf("bytea", counterparties));
                s.executeUpdate();
            } finally {
                Arrays.fill(dataKey, (byte) 0);
            }
            return null;
        });
    }

    /** The customer's transactions that an active consent covers today, decrypted, oldest first. */
    public List<Txn> transactions(String customerId) {
        return as(customerId, c -> {
            byte[] dataKey = dataKey(c, customerId);
            List<Txn> out = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT record_id, consent_id, fi_type, txn_date, category, direction, "
                    + "amount, counterparty FROM etherm.transactions ORDER BY txn_date, record_id");
                 ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String id = r.getString(1);
                    String counterparty = FieldCipher.decryptText(dataKey, r.getBytes(8), context(customerId, id, "counterparty"));
                    out.add(new Txn(id, r.getString(2), r.getString(3), r.getDate(4).toLocalDate(), r.getString(5), r.getString(6),
                            new BigDecimal(FieldCipher.decryptText(dataKey, r.getBytes(7), context(customerId, id, "amount"))),
                            counterparty.isEmpty() ? null : counterparty));
                }
            } finally {
                Arrays.fill(dataKey, (byte) 0);
            }
            return out;
        });
    }

    /**
     * Erasure. Deletes the customer's live rows and their data key. Copies of the ciphertext elsewhere (backups,
     * replicas, dumps) can no longer be decrypted, because the only wrapped copy of the key is gone. The ledger keeps
     * its pseudonymous entries (ids and events, no financial data), ending with KEY_SHREDDED.
     */
    public void shred(String customerId) {
        as(customerId, c -> {
            try (PreparedStatement d = c.prepareStatement("DELETE FROM etherm.transactions");
                 PreparedStatement r = c.prepareStatement("UPDATE etherm.consents SET status = 'REVOKED' WHERE status = 'ACTIVE'");
                 PreparedStatement k = c.prepareStatement("DELETE FROM etherm.customer_keys")) {
                d.executeUpdate();
                r.executeUpdate();
                k.executeUpdate();
            }
            return null;
        });
    }

    public List<LedgerEntry> ledger(String customerId) {
        return as(customerId, c -> {
            List<LedgerEntry> out = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT n, consent_id, event, detail::text, at FROM etherm.consent_ledger ORDER BY n");
                 ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new LedgerEntry(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getTimestamp(5).toInstant()));
                }
            }
            return out;
        });
    }

    /** Verifies every chain the connecting role can see: an auditor sees all, the app sees one customer's. */
    public static List<ChainCheck> verify(String url, String user, String password) {
        try (Connection c = DriverManager.getConnection(url, user, password);
             PreparedStatement s = c.prepareStatement("SELECT customer_id, entries, first_broken, head FROM etherm.verify_ledger()");
             ResultSet r = s.executeQuery()) {
            List<ChainCheck> out = new ArrayList<>();
            while (r.next()) {
                long broken = r.getLong(3);
                Long firstBroken = r.wasNull() ? null : broken;
                out.add(new ChainCheck(r.getString(1), r.getLong(2), firstBroken, r.getBytes(4)));
            }
            return out;
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    private byte[] dataKey(Connection c, String customerId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT kek_id, wrapped_key FROM etherm.customer_keys WHERE customer_id = ?")) {
            s.setString(1, customerId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("no data key for " + customerId + " (not enrolled, or shredded)");
                }
                return keys.unwrap(customerId, r.getString(1), r.getBytes(2));
            }
        }
    }

    private static String context(String customerId, String recordId, String column) {
        return "etherm.transactions|" + customerId + "|" + recordId + "|" + column;
    }

    <T> T as(String customerId, Work<T> work) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("a customer is required");
        }
        try (Connection c = DriverManager.getConnection(url, login)) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement("SELECT set_config('etherm.customer_id', ?, true)")) {
                    s.setString(1, customerId);
                    s.execute();
                }
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    /** A database error, with the SQL state kept for callers that need to tell a refusal from an outage. */
    public static final class StoreException extends RuntimeException {
        private final String sqlState;

        StoreException(SQLException cause) {
            super(cause.getMessage(), cause);
            this.sqlState = cause.getSQLState();
        }

        public String sqlState() {
            return sqlState;
        }
    }
}
