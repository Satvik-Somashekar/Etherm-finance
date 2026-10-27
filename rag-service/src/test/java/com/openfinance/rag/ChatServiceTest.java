package com.openfinance.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The chat model decides what to look up; every number in the reply still comes from Java. */
class ChatServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private QueryService queries;
    private final Queue<String> scripted = new LinkedList<>();
    private final List<Map<String, Object>> requests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        UpstreamClients upstream = mock(UpstreamClients.class);
        List<FinancialRecord> records = new ArrayList<>();
        for (int m = 7; m <= 9; m++) {
            records.add(tx("sal" + m, Category.SALARY, Direction.CREDIT, m, "100000"));
            records.add(tx("rent" + m, Category.RENT, Direction.DEBIT, m, "30000"));
            records.add(tx("groc" + m, Category.GROCERIES, Direction.DEBIT, m, "10000"));
        }
        when(upstream.retrieve(any(), any())).thenReturn(new UpstreamClients.Retrieved(List.of("CN-1"), records.size(), records));
        queries = new QueryService(upstream, new TemplatePhraser(), Clock.systemUTC(),
                new PhrasingConfig.AsOf(LocalDate.of(2026, 10, 25)));
    }

    private static FinancialRecord tx(String id, Category c, Direction d, int month, String amount) {
        return new FinancialRecord(id, "C1", "A1", "FIP", FiType.DEPOSIT, RecordKind.TRANSACTION, c, d,
                LocalDate.of(2026, month, 5), new BigDecimal(amount), c.name(), "X", null);
    }

    /** Each scripted string is one model response: JSON as the API would return it. */
    private ChatService service() {
        ChatModel model = new ChatModel() {
            @Override
            public JsonNode generate(Map<String, Object> body) throws java.io.IOException {
                requests.add(body);
                return JSON.readTree(scripted.remove());
            }

            @Override
            public String name() {
                return "fake";
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return new ChatService(queries, provider);
    }

    private static String call(String tool, String args) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"" + tool
                + "\",\"args\":" + args + "}}]}}]}";
    }

    private static String say(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + JSON.valueToTree(text) + "}]}}]}";
    }

    private QueryResponse ask(ChatService service, String question, Set<FiType> scope) {
        return service.chat(new ChatRequest(question, null, List.of("CN-1"), scope, List.of()));
    }

    @Test
    void aFreeQuestionIsAnsweredFromALookupWithJavaFiguresInserted() {
        scripted.add(call("get_savings", "{\"period\":\"last_quarter\"}"));
        scripted.add(say("You earned {c1_income} and spent {c1_spending} {c1_period}, so {c1_saved} was left over."));
        QueryResponse r = ask(service(), "I want you to compare my expenses and income", null);
        // Three months of salary 3,00,000 against rent and groceries of 1,20,000 leaves 1,80,000.
        assertThat(r.answer()).isEqualTo("You earned ₹3,00,000.00 and spent ₹1,20,000.00 last quarter, so ₹1,80,000.00 was left over.");
        assertThat(r.phrasedBy()).isEqualTo("fake agent");
        assertThat(r.figures()).extracting(f -> f.key()).contains("income", "spending", "saved");
        assertThat(r.citations()).hasSize(9);
    }

    @Test
    void aReplyWithItsOwnNumberIsSentBackOnceThenAccepted() {
        scripted.add(call("get_spending", "{}"));
        scripted.add(say("You spent about 1,20,000 in total."));
        scripted.add(say("You spent {c1_total} in total."));
        QueryResponse r = ask(service(), "How much have I spent?", null);
        assertThat(r.answer()).isEqualTo("You spent ₹1,20,000.00 in total.");
        assertThat(requests).hasSize(3);
    }

    @Test
    void aModelThatKeepsWritingNumbersFallsBackToTemplateWording() {
        scripted.add(call("get_spending", "{}"));
        scripted.add(say("You spent 1,20,000."));
        scripted.add(say("Still 1,20,000."));
        QueryResponse r = ask(service(), "How much have I spent?", null);
        assertThat(r.answer()).contains("₹1,20,000.00").doesNotContain("Still");
    }

    @Test
    void numbersFromEarlierCheckedAnswersMayBeRepeated() {
        scripted.add(say("As I said, that was ₹1,20,000.00 over the three months."));
        QueryResponse r = service().chat(new ChatRequest("remind me", null, List.of("CN-1"), null,
                List.of(new ChatRequest.Turn("user", "How much did I spend?"),
                        new ChatRequest.Turn("assistant", "You spent ₹1,20,000.00."))));
        assertThat(r.answer()).contains("₹1,20,000.00");
    }

    @Test
    void aLookupOutsideTheSelectedSectionIsNotRunAndNotOffered() {
        scripted.add(call("get_savings", "{}"));
        scripted.add(say("That is outside this section, so I cannot look at it."));
        QueryResponse r = ask(service(), "Compare my expenses and income", Set.of(FiType.MUTUAL_FUNDS));
        assertThat(r.figures()).isEmpty();
        assertThat(r.citations()).isEmpty();
        assertThat(requests.get(0).get("tools").toString()).doesNotContain("get_savings").contains("get_holdings");
    }

    @Test
    void exportsKeepTheirFixedPath() {
        QueryResponse r = ask(service(), "Export my transactions for last quarter", null);
        assertThat(r.export()).isNotNull();
        assertThat(requests).isEmpty();
    }

    @Test
    void aFailingModelFallsBackToTheTypedQuestionPath() {
        scripted.add("not json");
        QueryResponse r = ask(service(), "How much did I spend on groceries last quarter?", null);
        assertThat(r.phrasedBy()).isEqualTo("template");
        assertThat(r.answer()).contains("₹30,000.00");
    }
}
