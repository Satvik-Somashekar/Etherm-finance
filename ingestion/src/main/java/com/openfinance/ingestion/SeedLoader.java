package com.openfinance.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Loads generated FI fetch responses from {@code etherm.ingestion.seed-dir} at startup. */
@Component
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final IngestionService ingestion;
    private final ObjectMapper mapper;
    private final String seedDir;

    public SeedLoader(IngestionService ingestion, ObjectMapper mapper,
                      @Value("${etherm.ingestion.seed-dir:}") String seedDir) {
        this.ingestion = ingestion;
        this.mapper = mapper;
        this.seedDir = seedDir;
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
        }
    }
}
