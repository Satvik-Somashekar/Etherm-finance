package com.openfinance.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The filters and {@link Caller} together, on a tiny app with one customer endpoint and one service-only one. */
@SpringBootTest(classes = AccessFiltersTest.App.class, properties = {
        "etherm.security.service-paths=/internal/**",
        "etherm.security.max-body-bytes=100",
        "etherm.security.rate-limits[/customers/**]=3"})
@AutoConfigureMockMvc
class AccessFiltersTest {

    @SpringBootApplication
    static class App {
        @RestController
        static class Endpoints {
            @GetMapping("/customers/{id}/data")
            String data(@PathVariable String id) {
                Caller.current().requireCustomer(id);
                return "ok";
            }

            @GetMapping("/internal/all")
            String all() {
                return "ok";
            }

            @PostMapping("/echo")
            String echo(@RequestBody String body) {
                return body;
            }
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    private String customer(String id) {
        return "Bearer " + tokens.issueCustomer(id, Duration.ofMinutes(5));
    }

    @Test
    void withoutATokenNothingButHealthIsReachable() throws Exception {
        mvc.perform(get("/customers/a/data")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get("/customers/a/data").header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerReachesOnlyTheirOwnData() throws Exception {
        mvc.perform(get("/customers/a/data").header("Authorization", customer("a"))).andExpect(status().isOk());
        mvc.perform(get("/customers/b/data").header("Authorization", customer("a"))).andExpect(status().isForbidden());
    }

    @Test
    void serviceOnlyPathsRefuseCustomers() throws Exception {
        mvc.perform(get("/internal/all").header("Authorization", customer("a"))).andExpect(status().isForbidden());
        mvc.perform(get("/internal/all").header("Authorization", "Bearer " + tokens.issueService("x", Duration.ofMinutes(1))))
                .andExpect(status().isOk());
    }

    @Test
    void oversizedBodiesAreRefused() throws Exception {
        mvc.perform(post("/echo").header("Authorization", customer("a")).contentType(MediaType.TEXT_PLAIN).content("x".repeat(101)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/echo").header("Authorization", customer("a")).contentType(MediaType.TEXT_PLAIN).content("x".repeat(100)))
                .andExpect(status().isOk());
    }

    @Test
    void aCustomerIsRateLimitedAndEveryResponseCarriesSecurityHeaders() throws Exception {
        String token = customer("ratelimited");
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/customers/ratelimited/data").header("Authorization", token)).andExpect(status().isOk())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get("/customers/ratelimited/data").header("Authorization", token))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void anotherServiceIsNotRateLimited() throws Exception {
        String service = "Bearer " + tokens.issueService("rag-service", Duration.ofMinutes(1));
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/customers/anyone/data").header("Authorization", service)).andExpect(status().isOk());
        }
    }
}
