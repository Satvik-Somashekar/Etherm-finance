package com.openfinance.rag;

import com.openfinance.core.compute.ComputedAnswer;
import com.openfinance.core.compute.Figure;
import com.openfinance.core.compute.Intent;
import com.openfinance.core.compute.QueryPlan;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiPhraserTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GeminiPhraser phraser(int status, String draft, AtomicReference<String> seen) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + "|key=" + ex.getRequestHeaders().getFirst("x-goog-api-key") + "|" + ex.getRequestURI());
            String json = status == 200
                    ? "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + draft + "\"}]}}]}"
                    : "{\"error\":\"nope\"}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return new GeminiPhraser("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "gemini-test",
                new TemplatePhraser());
    }

    private static ComputedAnswer answer() {
        QueryPlan plan = QueryPlan.of(Intent.SPEND_TOTAL);
        FinancialRecord record = new FinancialRecord("L:1", "u", "L", "F", FiType.DEPOSIT, RecordKind.TRANSACTION,
                Category.FOOD, Direction.DEBIT, LocalDate.of(2026, 8, 1), new BigDecimal("1234.5"), "d", "c", Map.of());
        return new ComputedAnswer(plan,
                List.of(new Figure("total", "Total", new BigDecimal("1234.5"), Figure.Unit.INR, List.of("L:1"))),
                List.of(), List.of(record), 1);
    }

    @Test
    void safeDraftIsFilledWithJavaFigures() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        var p = phraser(200, "You spent {total} in all.", seen).phrase("How much did I spend?", answer());
        assertThat(p.text()).isEqualTo("You spent ₹1,234.50 in all.");
        assertThat(p.phrasedBy()).isEqualTo("gemini-test");
        assertThat(seen.get()).contains("key=test-key").contains("gemini-test:generateContent");
        assertThat(seen.get()).doesNotContain("1234.5");
    }

    @Test
    void draftWithItsOwnNumberFallsBackToTemplate() throws Exception {
        var p = phraser(200, "You spent about 1200 rupees.", new AtomicReference<>()).phrase("q", answer());
        assertThat(p.phrasedBy()).isEqualTo("template");
        assertThat(p.text()).contains("₹1,234.50");
    }

    @Test
    void httpErrorFallsBackToTemplate() throws Exception {
        var p = phraser(429, "", new AtomicReference<>()).phrase("q", answer());
        assertThat(p.phrasedBy()).isEqualTo("template");
    }
}
