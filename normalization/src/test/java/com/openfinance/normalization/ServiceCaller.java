package com.openfinance.normalization;

import com.openfinance.security.TokenService;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;

/** Tests that exercise the endpoints themselves call as another Etherm service; access rules have their own tests. */
@TestConfiguration
public class ServiceCaller {

    @Bean
    MockMvcBuilderCustomizer serviceToken(TokenService tokens) {
        return builder -> builder.defaultRequest(MockMvcRequestBuilders.get("/")
                .header("Authorization", "Bearer " + tokens.issueService("test", Duration.ofMinutes(10))));
    }
}
