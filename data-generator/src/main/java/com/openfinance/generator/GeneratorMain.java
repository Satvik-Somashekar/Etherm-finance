package com.openfinance.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Writes one FI fetch response per synthetic user to an output directory.
 *
 * <pre>
 * mvn -pl data-generator exec:java -Dexec.args="--users 5 --seed 42 --out data/synthetic"
 * </pre>
 */
public final class GeneratorMain {

    private GeneratorMain() {
    }

    public static void main(String[] args) throws IOException {
        int users = 5;
        long seed = 42;
        Path out = Path.of("data/synthetic");
        LocalDate from = LocalDate.of(2025, 10, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);

        for (int i = 0; i < args.length - 1; i += 2) {
            switch (args[i]) {
                case "--users" -> users = Integer.parseInt(args[i + 1]);
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                case "--out" -> out = Path.of(args[i + 1]);
                case "--from" -> from = LocalDate.parse(args[i + 1]);
                case "--to" -> to = LocalDate.parse(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }

        SyntheticDataGenerator generator = new SyntheticDataGenerator(seed, from, to);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.createDirectories(out);
        for (int i = 0; i < users; i++) {
            ObjectNode response = generator.generate(i);
            Path file = out.resolve(String.format("user%03d.json", i + 1));
            mapper.writeValue(file.toFile(), response);
            System.out.println("Wrote " + file);
        }
    }
}
