package com.openfinance.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/** In CI the database tests must run, not quietly skip: ETHERM_REQUIRE_PG_TESTS=true makes a missing database a failure. */
@EnabledIfEnvironmentVariable(named = "ETHERM_REQUIRE_PG_TESTS", matches = "true")
class DatabaseRequiredTest {

    @Test
    void theDatabaseTestsHaveADatabase() {
        assertThat(System.getenv("ETHERM_TEST_PG_ADMIN_URL")).as("ETHERM_TEST_PG_ADMIN_URL").isNotBlank();
    }
}
