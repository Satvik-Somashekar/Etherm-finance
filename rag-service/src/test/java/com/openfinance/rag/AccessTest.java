package com.openfinance.rag;

import com.openfinance.core.consent.ConsentArtifact;
import com.openfinance.core.consent.ConsentStatus;
import com.openfinance.core.model.FiType;
import com.openfinance.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A signed-in customer can ask only about themself, under consents they hold. */
@SpringBootTest(properties = {"etherm.query.as-of=2026-10-25", "etherm.security.rate-limits[/**]=1000",
        "etherm.security.rate-limits[/api/query]=1000", "etherm.security.rate-limits[/api/chat]=1000"})
@AutoConfigureMockMvc
class AccessTest {

    private static final String A = "user001@etherm-aa";
    private static final String B = "user002@etherm-aa";

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    @MockitoBean
    UpstreamClients upstream;

    private String a;

    private static ConsentArtifact consent(String id, String customer) {
        return new ConsentArtifact(id, customer, ConsentStatus.ACTIVE, "101", "test", Set.of(FiType.DEPOSIT),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"),
                LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30), ConsentArtifact.FetchType.ONETIME);
    }

    @BeforeEach
    void setUp() {
        a = "Bearer " + tokens.issueCustomer(A, Duration.ofMinutes(5));
        when(upstream.consents(A)).thenReturn(List.of(consent("CN-user001-BANK", A)));
        when(upstream.retrieve(any(), any())).thenReturn(new UpstreamClients.Retrieved(List.of("CN-user001-BANK"), 0, List.of()));
    }

    private org.springframework.test.web.servlet.ResultActions ask(String body) throws Exception {
        return mvc.perform(post("/api/query").header("Authorization", a).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void aCustomerCannotAskAboutAnotherCustomer() throws Exception {
        ask("{\"customerId\":\"" + B + "\",\"question\":\"What is my balance?\"}").andExpect(status().isForbidden());
        mvc.perform(post("/api/chat").header("Authorization", a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + B + "\",\"question\":\"What is my balance?\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/customers/" + B + "/overview").header("Authorization", a)).andExpect(status().isForbidden());
        mvc.perform(get("/api/customers/" + B + "/export/transactions.csv").header("Authorization", a)).andExpect(status().isForbidden());
        verify(upstream, never()).retrieve(any(), any());
    }

    @Test
    void aCustomerCannotUseAnotherCustomersConsent() throws Exception {
        ask("{\"consentIds\":[\"CN-user002-BANK\"],\"question\":\"What is my balance?\"}").andExpect(status().isForbidden());
        verify(upstream, never()).retrieve(any(), any());
        ask("{\"consentIds\":[\"CN-user001-BANK\"],\"question\":\"What is my balance?\"}").andExpect(status().isOk());
    }

    @Test
    void withNoCustomerNamedTheSignedInCustomerIsUsedAndTheAnswerIsSigned() throws Exception {
        ask("{\"question\":\"What is my balance?\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.turnSignature").isString());
    }

    @Test
    void oversizedQuestionsAndConversationsAreRefused() throws Exception {
        ask("{\"question\":\"" + "a".repeat(QueryController.MAX_QUESTION + 1) + "\"}").andExpect(status().isBadRequest());
        String turn = "{\"role\":\"user\",\"text\":\"hi\"}";
        mvc.perform(post("/api/chat").header("Authorization", a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hi\",\"turns\":[" + String.join(",", java.util.Collections.nCopies(QueryController.MAX_TURNS + 1, turn)) + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutATokenNothingIsAnswered() throws Exception {
        mvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"balance\"}"))
                .andExpect(status().isUnauthorized());
    }
}
