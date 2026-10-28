package com.openfinance.ingestion.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfinance.generator.SyntheticDataGenerator;
import com.openfinance.ingestion.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sign-in, and what a signed-in customer can and cannot reach in ingestion. */
// The sign-in rate limit is raised here so the lockout, not the per-address limit, is what these tests meet.
@SpringBootTest(properties = {"etherm.ingestion.seed-dir=", "etherm.session.demo-passcode=correct horse",
        "etherm.security.rate-limits[/api/session]=1000", "etherm.security.rate-limits[/**]=1000"})
@AutoConfigureMockMvc
class SessionAndAccessTest {

    private static final String A = "user001@etherm-aa";
    private static final String B = "user002@etherm-aa";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    IngestionService ingestion;

    @BeforeEach
    void load() {
        var generator = new SyntheticDataGenerator(42, LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30));
        ingestion.ingest(generator.generate(0));
        ingestion.ingest(generator.generate(1));
    }

    private org.springframework.test.web.servlet.ResultActions signIn(String customer, String passcode) throws Exception {
        return mvc.perform(post("/api/session").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new SessionController.SignIn(customer, passcode))));
    }

    private String token(String customer) throws Exception {
        String body = signIn(customer, "correct horse").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("token").asText();
    }

    @Test
    void theRightPasscodeGivesATokenForThatCustomerOnly() throws Exception {
        signIn(A, "correct horse").andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value(A))
                .andExpect(jsonPath("$.token").isString());
        signIn(A, "wrong").andExpect(status().isUnauthorized());
        // An unknown customer gets the same answer as a wrong passcode.
        String unknown = signIn("nobody@etherm-aa", "correct horse").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String wrong = signIn(B, "wrong").andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(unknown).get("detail")).isEqualTo(json.readTree(wrong).get("detail"));
    }

    @Test
    void fiveFailuresLockTheCustomerOutEvenWithTheRightPasscode() throws Exception {
        String victim = "user002@etherm-aa";
        for (int i = 0; i < 5; i++) {
            signIn(victim, "guess" + i).andExpect(status().isUnauthorized());
        }
        signIn(victim, "correct horse").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Too many failed attempts")));
    }

    @Test
    void aCustomerSeesTheirOwnProvidersAndConsentsButNotAnotherCustomers() throws Exception {
        String a = token(A);
        mvc.perform(get("/api/customers/" + A + "/fips").header("Authorization", a)).andExpect(status().isOk());
        mvc.perform(get("/api/customers/" + B + "/fips").header("Authorization", a)).andExpect(status().isForbidden());
        mvc.perform(get("/api/consents").param("customerId", B).header("Authorization", a)).andExpect(status().isForbidden());
        mvc.perform(post("/api/consents").header("Authorization", a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + B + "\",\"fiTypes\":[\"DEPOSIT\"],\"dataFrom\":\"2026-01-01\",\"dataTo\":\"2026-09-30\"}"))
                .andExpect(status().isForbidden());

        // B's own consent, then A tries to read, approve and revoke it by id.
        String b = token(B);
        String created = mvc.perform(post("/api/consents").header("Authorization", b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + B + "\",\"fiTypes\":[\"DEPOSIT\"],\"dataFrom\":\"2026-01-01\",\"dataTo\":\"2026-09-30\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("consentId").asText();
        mvc.perform(get("/api/consents/" + id).header("Authorization", a)).andExpect(status().isForbidden());
        mvc.perform(post("/api/consents/" + id + "/approve").header("Authorization", a)).andExpect(status().isForbidden());
        mvc.perform(post("/api/consents/" + id + "/revoke").header("Authorization", a)).andExpect(status().isForbidden());
        mvc.perform(get("/api/consents/" + id + "/accounts").header("Authorization", a)).andExpect(status().isForbidden());
        mvc.perform(post("/api/consents/" + id + "/approve").header("Authorization", b)).andExpect(status().isOk());
    }

    @Test
    void servicePathsAndUnsignedRequestsAreRefused() throws Exception {
        mvc.perform(get("/api/customers/" + A + "/fips")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customers").header("Authorization", token(A))).andExpect(status().isForbidden());
        mvc.perform(post("/api/fi/fetch-responses").header("Authorization", token(A)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
    }
}
