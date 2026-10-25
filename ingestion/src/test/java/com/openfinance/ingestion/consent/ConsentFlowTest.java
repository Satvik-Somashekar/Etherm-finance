package com.openfinance.ingestion.consent;

import com.fasterxml.jackson.databind.JsonNode;
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

@SpringBootTest(properties = "etherm.ingestion.seed-dir=")
@AutoConfigureMockMvc
class ConsentFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    IngestionService ingestion;

    @BeforeEach
    void loadFipData() {
        ingestion.ingest(new SyntheticDataGenerator(42, LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30)).generate(0));
    }

    private String createConsent(String body) throws Exception {
        String json = mvc.perform(post("/api/consents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("consentId").asText();
    }

    @Test
    void approvedConsentFetchesOnlyConsentedTypesAndDateRange() throws Exception {
        String id = createConsent("""
                {"customerId":"user001@etherm-aa","fiTypes":["DEPOSIT","INSURANCE_POLICIES"],
                 "dataFrom":"2026-07-01","dataTo":"2026-09-30"}""");

        mvc.perform(get("/api/consents/" + id + "/accounts")).andExpect(status().isForbidden());

        mvc.perform(post("/api/consents/" + id + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").value(3));

        JsonNode accounts = mapper.readTree(mvc.perform(get("/api/consents/" + id + "/accounts"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(accounts).hasSize(3);
        for (JsonNode a : accounts) {
            assertThat(a.get("fiType").asText()).isIn("DEPOSIT", "INSURANCE_POLICIES");
            assertThat(a.get("consentId").asText()).isEqualTo(id);
            for (JsonNode t : a.at("/account/transactions/transaction")) {
                String date = t.has("transactionTimestamp") ? t.get("transactionTimestamp").asText() : t.get("txnDate").asText();
                assertThat(LocalDate.parse(date.substring(0, 10))).isBetween(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
            }
        }
    }

    @Test
    void fipRestrictionLimitsTheFetch() throws Exception {
        String id = createConsent("""
                {"customerId":"user001@etherm-aa","fiTypes":["DEPOSIT","EQUITIES"],"fipIds":["ETHERM-DEPOSITORY-FIP"],
                 "dataFrom":"2025-10-01","dataTo":"2026-09-30"}""");
        mvc.perform(post("/api/consents/" + id + "/approve")).andExpect(jsonPath("$.accounts").value(1));
        mvc.perform(get("/api/consents/" + id + "/accounts")).andExpect(jsonPath("$[0].fiType").value("EQUITIES"));
    }

    @Test
    void revokingDeletesAccessAndCannotBeReapproved() throws Exception {
        String id = createConsent("""
                {"customerId":"user001@etherm-aa","fiTypes":["EPF"],"dataFrom":"2025-10-01","dataTo":"2026-09-30"}""");
        mvc.perform(post("/api/consents/" + id + "/approve")).andExpect(status().isOk());
        mvc.perform(post("/api/consents/" + id + "/revoke")).andExpect(jsonPath("$.status").value("REVOKED"));
        mvc.perform(get("/api/consents/" + id + "/accounts")).andExpect(status().isForbidden());
        mvc.perform(post("/api/consents/" + id + "/approve")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/consents").param("customerId", "user001@etherm-aa"))
                .andExpect(jsonPath("$[?(@.consentId=='" + id + "')].status").value("REVOKED"));
    }

    @Test
    void unknownConsentIs404AndBadRequestIs400() throws Exception {
        mvc.perform(get("/api/consents/CN-nope")).andExpect(status().isNotFound());
        mvc.perform(post("/api/consents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"user001@etherm-aa\",\"fiTypes\":[],\"dataFrom\":\"2025-10-01\",\"dataTo\":\"2026-09-30\"}"))
                .andExpect(status().isBadRequest());
    }
}
