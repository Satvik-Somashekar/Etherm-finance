package com.openfinance.rag;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;

@Configuration
public class PhrasingConfig {

    private static final Logger log = LoggerFactory.getLogger(PhrasingConfig.class);

    /**
     * {@code etherm.phrasing.provider}: {@code template} always uses templates; {@code claude} uses
     * the model when {@code ANTHROPIC_API_KEY} is set. Either way the numbers come from Java.
     */
    @Bean
    @Primary
    AnswerPhraser answerPhraser(TemplatePhraser template,
                                @Value("${etherm.phrasing.provider:template}") String provider,
                                @Value("${etherm.phrasing.model:claude-opus-5-5}") String model) {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if ("claude".equalsIgnoreCase(provider) && key != null && !key.isBlank()) {
            log.info("Phrasing with {} (placeholders only; template fallback)", model);
            return new ClaudePhraser(AnthropicOkHttpClient.fromEnv(), model, template);
        }
        if ("claude".equalsIgnoreCase(provider)) {
            log.warn("etherm.phrasing.provider=claude but ANTHROPIC_API_KEY is not set; using templates");
        }
        return template;
    }

    /** Pins "today" for relative periods; leave unset to use the real date. */
    @Bean
    AsOf asOf(@Value("${etherm.query.as-of:}") String value) {
        return new AsOf(value.isBlank() ? null : LocalDate.parse(value));
    }

    public record AsOf(LocalDate date) {
    }
}
