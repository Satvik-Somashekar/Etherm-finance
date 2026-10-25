package com.openfinance.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfinance.core.model.FiType;
import com.openfinance.ingestion.consent.ConsentService;
import com.openfinance.ingestion.consent.CreateConsentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Loads generated FI fetch responses from {@code etherm.ingestion.seed-dir} at startup. */
@Component
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final IngestionService ingestion;
    private final ConsentService consents;
    private final ObjectMapper mapper;
    private final String seedDir;
    private final boolean defaultConsent;
    private final LocalDate dataFrom;
    private final LocalDate dataTo;

    public SeedLoader(IngestionService ingestion, ConsentService consents, ObjectMapper mapper,
                      @Value("${etherm.ingestion.seed-dir:}") String seedDir,
                      @Value("${etherm.ingestion.default-consent.enabled:true}") boolean defaultConsent,
                      @Value("${etherm.ingestion.default-consent.data-from:2025-10-01}") LocalDate dataFrom,
                      @Value("${etherm.ingestion.default-consent.data-to:2026-09-30}") LocalDate dataTo) {
        this.ingestion = ingestion;
        this.consents = consents;
        this.mapper = mapper;
        this.seedDir = seedDir;
        this.defaultConsent = defaultConsent;
        this.dataFrom = dataFrom;
        this.dataTo = dataTo;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (seedDir.isBlank()) {
            return;
        }
        Path dir = Path.of(seedDir);
        if (!Files.isDirectory(dir)) {
            log.warn("Seed directory {} not found; starting with no data", dir.toAbsolutePath());
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path f : files) {
            var summary = ingestion.ingest(mapper.readTree(f.toFile()));
            log.info("Seeded {}: {} accounts, {} transactions", summary.customerId(), summary.accounts(), summary.transactions());
            if (defaultConsent) {
                approveDefaultConsent(summary.customerId());
            }
        }
    }

    /**
     * Gives each seeded customer one active consent over every FI type, so the demo works without
     * walking through the connect flow first. Its id is predictable: {@code CN-DEFAULT-<user>}.
     */
    private void approveDefaultConsent(String customerId) {
        String id = "CN-DEFAULT-" + customerId.substring(0, customerId.indexOf('@'));
        consents.create(new CreateConsentRequest(customerId, EnumSet.allOf(FiType.class), Set.of(), "101",
                "Wealth management service", dataFrom, dataTo, 365), id);
        var fetch = consents.approve(id);
        log.info("Default consent {} active: fetched {} accounts", id, fetch.accounts());
    }
}
