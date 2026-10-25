package com.openfinance.normalization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openfinance.generator.SyntheticDataGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "etherm.services.ingestion=http://ingestion")
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class NormalizationControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MockRestServiceServer ingestion;

    @Autowired
    ObjectMapper mapper;

    /** The shape ingestion returns from GET /api/consents/{id}/accounts. */
    private String fetchedAccounts() {
        var response = new SyntheticDataGenerator(42, LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30)).generate(0);
        ArrayNode out = mapper.createArrayNode();
        response.get("FI").forEach(fip -> fip.get("data").forEach(d -> {
            ObjectNode raw = out.addObject();
            raw.put("customerId", "user001@etherm-aa");
            raw.put("consentId", "CN-1");
            raw.put("fipId", fip.get("fipID").asText());
            raw.put("linkRefNumber", d.get("linkRefNumber").asText());
            raw.put("maskedAccNumber", d.get("maskedAccNumber").asText());
            raw.put("fiType", "X");
            raw.set("account", d.at("/decryptedFI/account"));
        }));
        return out.toString();
    }

    @Test
    void normalisesEveryFetchedAccount() throws Exception {
        ingestion.expect(requestTo("http://ingestion/api/consents/CN-1/accounts"))
                .andRespond(withSuccess(fetchedAccounts(), MediaType.APPLICATION_JSON));

        mvc.perform(get("/api/consents/CN-1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[?(@.fiType=='INSURANCE_POLICIES')].currentValue").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(0))));
    }

    @Test
    void recordsCarryTheSharedModel() throws Exception {
        ingestion.expect(requestTo("http://ingestion/api/consents/CN-1/accounts"))
                .andRespond(withSuccess(fetchedAccounts(), MediaType.APPLICATION_JSON));

        mvc.perform(get("/api/consents/CN-1/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recordId").exists())
                .andExpect(jsonPath("$[?(@.category=='SALARY')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.kind=='HOLDING')]").isNotEmpty());
    }

    @Test
    void revokedConsentStaysForbidden() throws Exception {
        ingestion.expect(requestTo("http://ingestion/api/consents/CN-2/accounts"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"detail\":\"consent CN-2 is REVOKED\"}"));

        mvc.perform(get("/api/consents/CN-2/records")).andExpect(status().isForbidden());
    }
}
