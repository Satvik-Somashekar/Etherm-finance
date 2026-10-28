-- Etherm secure store: the database half of the prototype (the Java half is com.openfinance.store).
--
-- Run once per database by an administrator (a superuser, or a role with CREATEROLE that is a member of
-- etherm_owner). It creates three roles if they do not exist yet, cluster-wide:
--   etherm_owner    owns the schema; cannot log in. Migrations run as it.
--   etherm_app      what the services connect as. Not the owner, no BYPASSRLS, so every row it reads or writes
--                   passes the policies below. It can only append to the ledger.
--   etherm_auditor  reads the whole ledger and verifies it; sees no financial data.
-- Passwords are not set here; the operator sets them from a secret store (ALTER ROLE ... PASSWORD).
--
-- What the database enforces, whatever the application does:
--   1. A session sees only the customer named by the transaction-local setting etherm.customer_id. No setting,
--      no rows.
--   2. A transaction row is visible only while a consent covers it: the consent is ACTIVE, not expired, of the
--      customer, for that FI type, and the date is inside the consented range. Rows cannot be written outside one.
--   3. Consents go ACTIVE -> REVOKED and never back.
--   4. Every consent change, data fetch, data deletion and key deletion is appended to a per-customer hash chain
--      by triggers, so the application cannot skip it. Nobody, owner included, can UPDATE, DELETE or TRUNCATE the
--      ledger through SQL; a superuser who disables the trigger and edits a row breaks the chain, which
--      etherm.verify_ledger() reports.
-- Amounts and counterparties are encrypted by the application before they arrive (AES-256-GCM under a
-- per-customer key, itself wrapped by a master key the database never sees), so a database dump or backup holds
-- ciphertext. Deleting a customer's wrapped key (crypto-shredding) makes every copy of their data unreadable.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'etherm_owner') THEN
        CREATE ROLE etherm_owner NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'etherm_app') THEN
        CREATE ROLE etherm_app LOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'etherm_auditor') THEN
        CREATE ROLE etherm_auditor LOGIN;
    END IF;
END
$$;
-- Re-asserted on every run, in case a role existed with more power.
ALTER ROLE etherm_app NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE etherm_auditor NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE etherm_owner NOSUPERUSER NOBYPASSRLS NOLOGIN;

CREATE SCHEMA etherm AUTHORIZATION etherm_owner;
REVOKE ALL ON SCHEMA etherm FROM PUBLIC;
GRANT USAGE ON SCHEMA etherm TO etherm_app, etherm_auditor;

SET ROLE etherm_owner;
ALTER DEFAULT PRIVILEGES IN SCHEMA etherm REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- The customer this transaction acts for, or null.
CREATE FUNCTION etherm.current_customer() RETURNS text
    LANGUAGE sql STABLE
    AS $$ SELECT nullif(current_setting('etherm.customer_id', true), '') $$;

-- ------------------------------------------------------------------------------------------------- tables

CREATE TABLE etherm.customer_keys (
    customer_id text PRIMARY KEY,
    -- Which master key wrapped it, so master keys can be rotated.
    kek_id      text        NOT NULL,
    wrapped_key bytea       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE etherm.consents (
    consent_id  text PRIMARY KEY,
    customer_id text        NOT NULL,
    fi_types    text[]      NOT NULL CHECK (cardinality(fi_types) > 0),
    data_from   date        NOT NULL,
    data_to     date        NOT NULL,
    expires_at  timestamptz NOT NULL,
    status      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    CHECK (data_from <= data_to)
);

CREATE TABLE etherm.transactions (
    record_id    text PRIMARY KEY,
    customer_id  text  NOT NULL,
    consent_id   text  NOT NULL REFERENCES etherm.consents ON DELETE RESTRICT,
    fi_type      text  NOT NULL,
    txn_date     date  NOT NULL,
    category     text  NOT NULL,
    direction    text  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Ciphertext from the application: version byte, 12-byte nonce, AES-256-GCM output.
    amount       bytea NOT NULL,
    counterparty bytea NOT NULL
);
CREATE INDEX transactions_customer_date ON etherm.transactions (customer_id, txn_date);

CREATE TABLE etherm.consent_ledger (
    customer_id text        NOT NULL,
    -- Position in this customer's chain, from 1.
    n           bigint      NOT NULL,
    consent_id  text,
    event       text        NOT NULL CHECK (event IN ('GRANTED', 'REVOKED', 'DATA_STORED', 'DATA_DELETED', 'KEY_SHREDDED')),
    detail      jsonb       NOT NULL DEFAULT '{}',
    at          timestamptz NOT NULL,
    prev_hash   bytea       NOT NULL,
    hash        bytea       NOT NULL,
    PRIMARY KEY (customer_id, n)
);

-- ------------------------------------------------------------------------------------------------- ledger

CREATE FUNCTION etherm.ledger_hash(prev bytea, customer text, consent text, event text, detail jsonb, at timestamptz)
    RETURNS bytea
    LANGUAGE sql IMMUTABLE
    AS $$
        SELECT sha256(prev || convert_to(jsonb_build_array(customer, consent, event, detail,
            to_char(at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))::text, 'UTF8'))
    $$;

-- Fills in position, time and hashes; the caller's values for them are ignored.
CREATE FUNCTION etherm.ledger_chain() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    DECLARE
        last etherm.consent_ledger;
    BEGIN
        -- One writer per customer at a time, so two inserts cannot both chain onto the same row.
        PERFORM pg_advisory_xact_lock(hashtextextended('etherm.ledger:' || NEW.customer_id, 0));
        SELECT * INTO last FROM etherm.consent_ledger WHERE customer_id = NEW.customer_id ORDER BY n DESC LIMIT 1;
        NEW.n := coalesce(last.n, 0) + 1;
        NEW.at := clock_timestamp();
        NEW.prev_hash := coalesce(last.hash, '\x0000000000000000000000000000000000000000000000000000000000000000'::bytea);
        NEW.hash := etherm.ledger_hash(NEW.prev_hash, NEW.customer_id, NEW.consent_id, NEW.event, NEW.detail, NEW.at);
        RETURN NEW;
    END
    $$;

CREATE FUNCTION etherm.ledger_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    BEGIN
        RAISE EXCEPTION 'the consent ledger is append-only (% refused)', TG_OP USING ERRCODE = 'insufficient_privilege';
    END
    $$;

CREATE TRIGGER chain BEFORE INSERT ON etherm.consent_ledger FOR EACH ROW EXECUTE FUNCTION etherm.ledger_chain();
CREATE TRIGGER append_only BEFORE UPDATE OR DELETE ON etherm.consent_ledger FOR EACH ROW EXECUTE FUNCTION etherm.ledger_append_only();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON etherm.consent_ledger FOR EACH STATEMENT EXECUTE FUNCTION etherm.ledger_append_only();

-- Recomputes every chain the caller can see. One row per customer: how many entries, and the first position whose
-- hash does not follow from the entry before it (null when the chain is intact).
CREATE FUNCTION etherm.verify_ledger()
    RETURNS TABLE (customer_id text, entries bigint, first_broken bigint, head bytea)
    LANGUAGE plpgsql STABLE
    AS $$
    DECLARE
        r etherm.consent_ledger;
        expected_prev bytea;
        current text;
    BEGIN
        FOR r IN SELECT * FROM etherm.consent_ledger l ORDER BY l.customer_id, l.n LOOP
            IF current IS DISTINCT FROM r.customer_id THEN
                IF current IS NOT NULL THEN
                    RETURN NEXT;
                END IF;
                current := r.customer_id;
                customer_id := r.customer_id;
                entries := 0;
                first_broken := NULL;
                expected_prev := '\x0000000000000000000000000000000000000000000000000000000000000000'::bytea;
            END IF;
            entries := entries + 1;
            IF first_broken IS NULL AND (r.n <> entries OR r.prev_hash <> expected_prev
                    OR r.hash <> etherm.ledger_hash(r.prev_hash, r.customer_id, r.consent_id, r.event, r.detail, r.at)) THEN
                first_broken := r.n;
            END IF;
            expected_prev := r.hash;
            head := r.hash;
        END LOOP;
        IF current IS NOT NULL THEN
            RETURN NEXT;
        END IF;
    END
    $$;

-- Ledger entries written by triggers on the data tables, so no code path can change data without recording it.
CREATE FUNCTION etherm.log_consent() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    BEGIN
        IF TG_OP = 'INSERT' THEN
            INSERT INTO etherm.consent_ledger (customer_id, consent_id, event, detail)
            VALUES (NEW.customer_id, NEW.consent_id, 'GRANTED', jsonb_build_object('fi_types', NEW.fi_types,
                    'data_from', NEW.data_from, 'data_to', NEW.data_to, 'expires_at', NEW.expires_at));
        ELSIF OLD.status = 'ACTIVE' AND NEW.status = 'REVOKED' THEN
            INSERT INTO etherm.consent_ledger (customer_id, consent_id, event) VALUES (NEW.customer_id, NEW.consent_id, 'REVOKED');
        END IF;
        RETURN NEW;
    END
    $$;

CREATE FUNCTION etherm.consent_forward_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    BEGIN
        IF OLD.status = 'REVOKED' OR (NEW.consent_id, NEW.customer_id, NEW.fi_types, NEW.data_from, NEW.data_to, NEW.expires_at)
                IS DISTINCT FROM (OLD.consent_id, OLD.customer_id, OLD.fi_types, OLD.data_from, OLD.data_to, OLD.expires_at) THEN
            RAISE EXCEPTION 'a consent can only go from ACTIVE to REVOKED' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END
    $$;

CREATE FUNCTION etherm.log_rows() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    BEGIN
        IF TG_OP = 'INSERT' THEN
            INSERT INTO etherm.consent_ledger (customer_id, consent_id, event, detail)
            SELECT customer_id, consent_id, 'DATA_STORED', jsonb_build_object('rows', count(*)) FROM changed GROUP BY customer_id, consent_id;
        ELSE
            INSERT INTO etherm.consent_ledger (customer_id, consent_id, event, detail)
            SELECT customer_id, consent_id, 'DATA_DELETED', jsonb_build_object('rows', count(*)) FROM changed GROUP BY customer_id, consent_id;
        END IF;
        RETURN NULL;
    END
    $$;

CREATE FUNCTION etherm.log_shred() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    BEGIN
        INSERT INTO etherm.consent_ledger (customer_id, event, detail)
        VALUES (OLD.customer_id, 'KEY_SHREDDED', jsonb_build_object('kek_id', OLD.kek_id));
        RETURN OLD;
    END
    $$;

CREATE TRIGGER forward_only BEFORE UPDATE ON etherm.consents FOR EACH ROW EXECUTE FUNCTION etherm.consent_forward_only();
CREATE TRIGGER logged AFTER INSERT OR UPDATE ON etherm.consents FOR EACH ROW EXECUTE FUNCTION etherm.log_consent();
CREATE TRIGGER logged_insert AFTER INSERT ON etherm.transactions REFERENCING NEW TABLE AS changed
    FOR EACH STATEMENT EXECUTE FUNCTION etherm.log_rows();
CREATE TRIGGER logged_delete AFTER DELETE ON etherm.transactions REFERENCING OLD TABLE AS changed
    FOR EACH STATEMENT EXECUTE FUNCTION etherm.log_rows();
CREATE TRIGGER logged AFTER DELETE ON etherm.customer_keys FOR EACH ROW EXECUTE FUNCTION etherm.log_shred();

-- ------------------------------------------------------------------------------------------- row security

ALTER TABLE etherm.customer_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE etherm.consents ENABLE ROW LEVEL SECURITY;
ALTER TABLE etherm.transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE etherm.consent_ledger ENABLE ROW LEVEL SECURITY;
-- FORCE applies the policies to the owner too, so even migrations cannot read across customers by accident.
ALTER TABLE etherm.customer_keys FORCE ROW LEVEL SECURITY;
ALTER TABLE etherm.consents FORCE ROW LEVEL SECURITY;
ALTER TABLE etherm.transactions FORCE ROW LEVEL SECURITY;
ALTER TABLE etherm.consent_ledger FORCE ROW LEVEL SECURITY;

CREATE POLICY own ON etherm.customer_keys TO etherm_app
    USING (customer_id = etherm.current_customer()) WITH CHECK (customer_id = etherm.current_customer());
CREATE POLICY own ON etherm.consents TO etherm_app
    USING (customer_id = etherm.current_customer()) WITH CHECK (customer_id = etherm.current_customer());
CREATE POLICY consented ON etherm.transactions TO etherm_app
    USING (customer_id = etherm.current_customer() AND EXISTS (
        SELECT 1 FROM etherm.consents c
        WHERE c.consent_id = transactions.consent_id AND c.customer_id = transactions.customer_id AND c.status = 'ACTIVE'
          AND c.expires_at > now() AND transactions.fi_type = ANY (c.fi_types)
          AND transactions.txn_date BETWEEN c.data_from AND c.data_to))
    WITH CHECK (customer_id = etherm.current_customer() AND EXISTS (
        SELECT 1 FROM etherm.consents c
        WHERE c.consent_id = transactions.consent_id AND c.customer_id = transactions.customer_id AND c.status = 'ACTIVE'
          AND c.expires_at > now() AND transactions.fi_type = ANY (c.fi_types)
          AND transactions.txn_date BETWEEN c.data_from AND c.data_to));
CREATE POLICY own_read ON etherm.consent_ledger FOR SELECT TO etherm_app USING (customer_id = etherm.current_customer());
CREATE POLICY own_append ON etherm.consent_ledger FOR INSERT TO etherm_app WITH CHECK (customer_id = etherm.current_customer());
CREATE POLICY audit ON etherm.consent_ledger FOR SELECT TO etherm_auditor USING (true);

-- ------------------------------------------------------------------------------------------------- grants

GRANT SELECT, INSERT, DELETE ON etherm.customer_keys TO etherm_app;
GRANT SELECT, INSERT ON etherm.consents TO etherm_app;
GRANT UPDATE (status) ON etherm.consents TO etherm_app;
GRANT SELECT, INSERT, DELETE ON etherm.transactions TO etherm_app;
GRANT SELECT, INSERT ON etherm.consent_ledger TO etherm_app;
GRANT SELECT ON etherm.consent_ledger TO etherm_auditor;
GRANT EXECUTE ON FUNCTION etherm.current_customer(), etherm.ledger_hash(bytea, text, text, text, jsonb, timestamptz),
    etherm.ledger_chain(), etherm.log_consent(), etherm.consent_forward_only(), etherm.log_rows(), etherm.log_shred(),
    etherm.ledger_append_only(), etherm.verify_ledger() TO etherm_app;
GRANT EXECUTE ON FUNCTION etherm.ledger_hash(bytea, text, text, text, jsonb, timestamptz), etherm.verify_ledger() TO etherm_auditor;

RESET ROLE;
