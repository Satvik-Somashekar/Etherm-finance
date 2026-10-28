package com.openfinance.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfinance.generator.SyntheticDataGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "etherm.ingestion.seed-dir=")
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.openfinance.ingestion.ServiceCaller.class)
class IngestionControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void ingestsFetchResponseAndListsCustomer() throws Exception {
        var payload = new SyntheticDataGenerator(42, LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30)).generate(4);

        mvc.perform(post("/api/fi/fetch-responses").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value("user005@etherm-aa"))
                .andExpect(jsonPath("$.accounts").value(6))
                .andExpect(jsonPath("$.accountsByType.INSURANCE_POLICIES").value(2));

        mvc.perform(get("/api/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("user005@etherm-aa")));

        mvc.perform(get("/api/customers/user005@etherm-aa/fips"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].maskedAccNumber").exists())
                .andExpect(jsonPath("$[0].account").doesNotExist());
    }

    @Test
    void invalidPayloadReturns422WithProblems() throws Exception {
        mvc.perform(post("/api/fi/fetch-responses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ver\":\"2.0.0\",\"FI\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems[0]").value("customerId is required"));
    }
}
