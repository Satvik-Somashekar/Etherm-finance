package com.openfinance.store;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The secure store against a real PostgreSQL 16. Each run creates a throwaway database from {@code db/schema.sql}
 * and gives the two login roles random passwords. Needs ETHERM_TEST_PG_ADMIN_URL, a JDBC URL with a superuser's
 * user and password (CI starts one; see .github/workflows/ci.yml). Every check is made through the roles the
 * services and auditors would use, and the administrator connection plays the attacker with a database dump or
 * superuser access.
 */
@EnabledIfEnvironmentVariable(named = "ETHERM_TEST_PG_ADMIN_URL", matches = ".+")
class SecureStoreTest {

    private static final Pattern URL = Pattern.compile("^(jdbc:postgresql://[^/]+)/([^?]*)(\\?.*)?$");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);
    private static final Instant NEXT_YEAR = Instant.now().plusSeconds(365L * 86_400);

    private static String server;
    private static String adminParams;
    private static String database;
    private static String appUrl;
    private static String appPassword;
    private static String auditorPassword;
    private static KeyRing keys;
    private static SecureStore store;

    @BeforeAll
    static void createDatabase() throws SQLException, IOException {
        Matcher m = URL.matcher(System.getenv("ETHERM_TEST_PG_ADMIN_URL"));
        assertThat(m.matches()).as("ETHERM_TEST_PG_ADMIN_URL must look like jdbc:postgresql://host:port/db?user=..&password=..").isTrue();
        server = m.group(1);
        adminParams = m.group(3) == null ? "" : m.group(3);
        database = "etherm_store_test_" + HexFormat.of().formatHex(random(6));
        appPassword = HexFormat.of().formatHex(random(16));
        auditorPassword = HexFormat.of().formatHex(random(16));
        try (Connection c = DriverManager.getConnection(server + "/" + m.group(2) + adminParams); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + database);
        }
        String schema;
        try (InputStream in = SecureStoreTest.class.getResourceAsStream("/db/schema.sql")) {
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute(schema);
            s.execute("ALTER ROLE etherm_app PASSWORD '" + appPassword + "'");
            s.execute("ALTER ROLE etherm_auditor PASSWORD '" + auditorPassword + "'");
        }
        appUrl = server + "/" + database;
        keys = KeyRing.of("test-1", random(32));
        store = new SecureStore(appUrl, "etherm_app", appPassword, keys);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        Matcher m = URL.matcher(System.getenv("ETHERM_TEST_PG_ADMIN_URL"));
        assertThat(m.matches()).isTrue();
        try (Connection c = DriverManager.getConnection(server + "/" + m.group(2) + adminParams); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(server + "/" + database + adminParams);
    }

    private static Connection app() throws SQLException {
        return DriverManager.getConnection(appUrl, "etherm_app", appPassword);
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** A customer with one bank consent and three transactions. */
    private static String customer(String name) {
        String id = name + "-" + HexFormat.of().formatHex(random(3)) + "@etherm-aa";
        store.enrol(id);
        store.grant(new SecureStore.Consent("CN-" + id + "-BANK", id, Set.of("DEPOSIT"), FROM, TO, NEXT_YEAR));
        store.store(id, List.of(
                txn(id, "1", LocalDate.of(2026, 7, 1), "SALARY", "CREDIT", "100000.00", "NORTHWIND SYSTEMS"),
                txn(id, "2", LocalDate.of(2026, 7, 5), "RENT", "DEBIT", "25000.00", "RENT"),
                txn(id, "3", LocalDate.of(2026, 7, 9), "GROCERIES", "DEBIT", "1623.21", "FRESHMART")));
        return id;
    }

    private static SecureStore.Txn txn(String customer, String n, LocalDate date, String category, String direction, String amount,
                                       String counterparty) {
        return new SecureStore.Txn(customer + ":" + n, "CN-" + customer + "-BANK", "DEPOSIT", date, category, direction,
                new BigDecimal(amount), counterparty);
    }

    @Test
    void theApplicationRoleIsNeitherSuperuserNorOwnerAndCannotBypassPolicies() throws SQLException {
        try (Connection c = admin(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'etherm_app'")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getBoolean(1)).isFalse();
            assertThat(r.getBoolean(2)).isFalse();
            assertThat(count(c, "SELECT count(*) FROM pg_tables WHERE schemaname = 'etherm' AND tableowner <> 'etherm_owner'")).isZero();
            assertThat(count(c, "SELECT count(*) FROM pg_class WHERE relnamespace = 'etherm'::regnamespace AND relkind = 'r' "
                    + "AND NOT (relrowsecurity AND relforcerowsecurity)")).isZero();
        }
    }

    @Test
    void withoutACustomerTheDatabaseReturnsNothingAndAcceptsNothing() throws SQLException {
        customer("nobody-sees");
        try (Connection c = app()) {
            assertThat(count(c, "SELECT count(*) FROM etherm.transactions")).isZero();
            assertThat(count(c, "SELECT count(*) FROM etherm.consents")).isZero();
            assertThat(count(c, "SELECT count(*) FROM etherm.customer_keys")).isZero();
            assertThatThrownBy(() -> c.createStatement().execute("INSERT INTO etherm.consents (consent_id, customer_id, fi_types, "
                    + "data_from, data_to, expires_at) VALUES ('CN-x', 'x', '{DEPOSIT}', '2026-01-01', '2026-02-01', now() + interval '1 day')"))
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    void oneCustomerCannotReadOrWriteAnothers() {
        String a = customer("asha");
        String b = customer("bhavin");
        assertThat(store.transactions(a)).hasSize(3).allSatisfy(t -> assertThat(t.recordId()).startsWith(a));
        long seen = store.as(a, c -> count(c, "SELECT count(*) FROM etherm.transactions WHERE customer_id = '" + b + "'"));
        assertThat(seen).isZero();
        // Writing a row for b while acting as a, even under b's consent id.
        assertThatThrownBy(() -> store.as(a, c -> c.createStatement().executeUpdate("INSERT INTO etherm.consents (consent_id, "
                + "customer_id, fi_types, data_from, data_to, expires_at) VALUES ('CN-forged', '" + b + "', '{DEPOSIT}', "
                + "'2026-01-01', '2026-02-01', now() + interval '1 day')"))).hasMessageContaining("row-level security");
        assertThatThrownBy(() -> store.store(a, List.of(new SecureStore.Txn(a + ":x", "CN-" + b + "-BANK", "DEPOSIT",
                LocalDate.of(2026, 7, 1), "RENT", "DEBIT", BigDecimal.ONE, "RENT")))).hasMessageContaining("row-level security");
        assertThatThrownBy(() -> store.revoke(a, "CN-" + b + "-BANK")).hasMessageContaining("no active consent");
        assertThat(store.transactions(b)).hasSize(3);
    }

    @Test
    void rowsExistOnlyInsideAnActiveConsent() throws Exception {
        String id = customer("consent");
        // Outside the consented dates, or of a type it does not cover: refused on write.
        assertThatThrownBy(() -> store.store(id, List.of(txn(id, "early", LocalDate.of(2025, 12, 31), "RENT", "DEBIT", "1", "RENT"))))
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> store.store(id, List.of(new SecureStore.Txn(id + ":mf", "CN-" + id + "-BANK", "MUTUAL_FUNDS",
                LocalDate.of(2026, 7, 1), "MF_PURCHASE", "DEBIT", BigDecimal.TEN, "AMC")))).hasMessageContaining("row-level security");

        // An expired consent hides its rows without anyone deleting them.
        store.grant(new SecureStore.Consent("CN-" + id + "-SHORT", id, Set.of("DEPOSIT"), FROM, TO, Instant.now().plusSeconds(2)));
        store.store(id, List.of(new SecureStore.Txn(id + ":short", "CN-" + id + "-SHORT", "DEPOSIT", LocalDate.of(2026, 8, 1),
                "FOOD", "DEBIT", BigDecimal.ONE, "QUICKBITE")));
        assertThat(store.transactions(id)).hasSize(4);
        Thread.sleep(2500);
        assertThat(store.transactions(id)).hasSize(3);

        // Revoking deletes the consent's rows and cannot be undone.
        store.revoke(id, "CN-" + id + "-BANK");
        assertThat(store.transactions(id)).isEmpty();
        try (Connection c = admin()) {
            assertThat(count(c, "SELECT count(*) FROM etherm.transactions WHERE consent_id = 'CN-" + id + "-BANK'")).isZero();
        }
        assertThatThrownBy(() -> store.as(id, c -> c.createStatement().executeUpdate(
                "UPDATE etherm.consents SET status = 'ACTIVE' WHERE consent_id = 'CN-" + id + "-BANK'")))
                .hasMessageContaining("only go from ACTIVE to REVOKED");
        assertThatThrownBy(() -> store.as(id, c -> c.createStatement().executeUpdate(
                "UPDATE etherm.consents SET data_to = '2030-01-01' WHERE consent_id = 'CN-" + id + "-SHORT'")))
                .hasMessageContaining("permission denied");
    }

    @Test
    void aDumpHoldsNoAmountsOrNamesAndMovedCiphertextIsRefused() throws SQLException {
        String id = customer("dump");
        try (Connection c = admin(); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT amount, counterparty FROM etherm.transactions WHERE customer_id = '" + id + "'")) {
                while (r.next()) {
                    String raw = new String(r.getBytes(1), StandardCharsets.ISO_8859_1) + new String(r.getBytes(2), StandardCharsets.ISO_8859_1);
                    assertThat(raw).doesNotContain("25000").doesNotContain("100000").doesNotContain("RENT").doesNotContain("FRESHMART");
                }
            }
            try (ResultSet r = s.executeQuery("SELECT wrapped_key FROM etherm.customer_keys WHERE customer_id = '" + id + "'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBytes(1)).hasSize(1 + 12 + 32 + 16);
            }
            // A superuser swaps rent's amount into the groceries row: decryption refuses instead of showing it.
            s.executeUpdate("UPDATE etherm.transactions SET amount = (SELECT amount FROM etherm.transactions WHERE record_id = '"
                    + id + ":2') WHERE record_id = '" + id + ":3'");
        }
        assertThatThrownBy(() -> store.transactions(id)).isInstanceOf(FieldCipher.TamperedException.class);
    }

    @Test
    void theLedgerRecordsEveryChangeCannotBeRewrittenAndShowsTampering() throws SQLException {
        String id = customer("ledger");
        store.revoke(id, "CN-" + id + "-BANK");
        assertThat(store.ledger(id)).extracting(SecureStore.LedgerEntry::event)
                .containsExactly("GRANTED", "DATA_STORED", "DATA_DELETED", "REVOKED");
        assertThat(store.ledger(id).get(1).detail()).contains("\"rows\": 3");

        // The application can append but not change or remove, and the hashes it sends are ignored.
        store.as(id, c -> c.createStatement().executeUpdate("INSERT INTO etherm.consent_ledger (customer_id, n, event, at, prev_hash, hash) "
                + "VALUES ('" + id + "', 99, 'REVOKED', '2000-01-01', '\\x00', '\\x00')"));
        assertThat(store.ledger(id)).extracting(SecureStore.LedgerEntry::n).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThatThrownBy(() -> store.as(id, c -> c.createStatement().executeUpdate("UPDATE etherm.consent_ledger SET event = 'GRANTED'")))
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> store.as(id, c -> c.createStatement().executeUpdate("DELETE FROM etherm.consent_ledger")))
                .hasMessageContaining("permission denied");
        // Nor for anyone else's chain.
        assertThatThrownBy(() -> store.as(id, c -> c.createStatement().executeUpdate(
                "INSERT INTO etherm.consent_ledger (customer_id, event) VALUES ('someone-else', 'REVOKED')")))
                .hasMessageContaining("row-level security");

        // The owner and even a superuser are stopped by the trigger.
        try (Connection c = admin(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("UPDATE etherm.consent_ledger SET event = 'GRANTED' WHERE customer_id = '" + id + "'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> s.execute("TRUNCATE etherm.consent_ledger")).hasMessageContaining("append-only");
        }
        assertThat(chain(id).firstBroken()).isNull();
        assertThat(chain(id).entries()).isEqualTo(5);

        // A superuser who switches the trigger off and edits history is caught by the next verification.
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE etherm.consent_ledger DISABLE TRIGGER append_only");
            s.executeUpdate("UPDATE etherm.consent_ledger SET detail = '{\"rows\": 1}' WHERE customer_id = '" + id + "' AND n = 2");
            s.execute("ALTER TABLE etherm.consent_ledger ENABLE TRIGGER append_only");
        }
        assertThat(chain(id).firstBroken()).isEqualTo(2L);
    }

    @Test
    void theAuditorSeesEveryChainButNoFinancialData() throws SQLException {
        String a = customer("audit-a");
        String b = customer("audit-b");
        List<SecureStore.ChainCheck> chains = SecureStore.verify(appUrl, "etherm_auditor", auditorPassword);
        assertThat(chains).extracting(SecureStore.ChainCheck::customerId).contains(a, b);
        try (Connection c = DriverManager.getConnection(appUrl, "etherm_auditor", auditorPassword)) {
            assertThatThrownBy(() -> count(c, "SELECT count(*) FROM etherm.transactions")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> count(c, "SELECT count(*) FROM etherm.customer_keys")).hasMessageContaining("permission denied");
        }
        // The application, with no customer named, verifies nothing.
        assertThat(SecureStore.verify(appUrl, "etherm_app", appPassword)).isEmpty();
    }

    @Test
    void shreddingTheKeyLeavesOldCopiesUnreadable() throws SQLException {
        String id = customer("shred");
        byte[] backedUp;
        try (Connection c = admin(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT amount FROM etherm.transactions WHERE record_id = '" + id + ":2'")) {
            assertThat(r.next()).isTrue();
            backedUp = r.getBytes(1);
        }
        store.shred(id);

        assertThatThrownBy(() -> store.transactions(id)).hasMessageContaining("not enrolled, or shredded");
        try (Connection c = admin()) {
            assertThat(count(c, "SELECT count(*) FROM etherm.customer_keys WHERE customer_id = '" + id + "'")).isZero();
            assertThat(count(c, "SELECT count(*) FROM etherm.transactions WHERE customer_id = '" + id + "'")).isZero();
        }
        // The backed-up ciphertext still exists, but nothing that could open it does: the master key alone does not.
        assertThat(backedUp).isNotEmpty();
        assertThat(store.ledger(id)).extracting(SecureStore.LedgerEntry::event).endsWith("REVOKED", "KEY_SHREDDED");
        assertThat(chain(id).firstBroken()).isNull();
    }

    @Test
    void theCustomerSettingEndsWithItsTransaction() throws SQLException {
        String id = customer("pooled");
        try (Connection c = app()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('etherm.customer_id', '" + id + "', true)");
            }
            assertThat(count(c, "SELECT count(*) FROM etherm.transactions")).isEqualTo(3);
            c.commit();
            // Same connection, next transaction, as a pool would hand it out: nothing is visible.
            assertThat(count(c, "SELECT count(*) FROM etherm.transactions")).isZero();
            c.commit();
        }
    }

    private static SecureStore.ChainCheck chain(String customerId) {
        return SecureStore.verify(appUrl, "etherm_auditor", auditorPassword).stream()
                .filter(x -> x.customerId().equals(customerId)).findFirst().orElseThrow();
    }
}
