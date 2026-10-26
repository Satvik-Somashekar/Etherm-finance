package com.openfinance.core.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openfinance.core.compute.DateRange;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.FiType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The contracts must serialise cleanly and every tool must ship a parseable schema. */
class OracleContractsTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void everyToolHasASchemaWithMatchingName() throws Exception {
        for (ToolName tool : ToolName.values()) {
            try (InputStream in = getClass().getResourceAsStream("/oracle/tools/" + tool.wireName() + ".json")) {
                assertThat(in).as("schema for " + tool).isNotNull();
                JsonNode schema = mapper.readTree(in);
                assertThat(schema.get("name").asText()).isEqualTo(tool.wireName());
                assertThat(schema.get("description").asText()).isNotBlank();
                assertThat(schema.at("/input_schema/type").asText()).isEqualTo("object");
            }
        }
    }

    @Test
    void queryPlanSchemaListsEveryEnumValue() throws Exception {
        JsonNode schema = mapper.readTree(getClass().getResourceAsStream("/oracle/tools/run_query_plan.json"));
        assertThat(values(schema.at("/input_schema/properties/filter/properties/categories/items/enum")))
                .containsExactlyInAnyOrderElementsOf(names(Category.values()));
        assertThat(values(schema.at("/input_schema/properties/filter/properties/fiTypes/items/enum")))
                .containsExactlyInAnyOrderElementsOf(names(FiType.values()));
        assertThat(values(schema.at("/input_schema/properties/groupBy/items/enum")))
                .containsExactlyInAnyOrderElementsOf(names(AgentQueryPlan.Dimension.values()));
        assertThat(values(schema.at("/input_schema/properties/aggregate/items/enum")))
                .containsExactlyInAnyOrderElementsOf(names(AgentQueryPlan.Aggregation.values()));
    }

    @Test
    void queryPlanRoundTrips() throws Exception {
        AgentQueryPlan plan = new AgentQueryPlan(
                new AgentQueryPlan.Filter(Set.of(FiType.DEPOSIT), null, null, Set.of(Category.GROCERIES), null,
                        new DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), "last quarter"),
                        new AgentQueryPlan.AmountRange(BigDecimal.ONE, null)),
                List.of(AgentQueryPlan.Dimension.MONTH),
                List.of(AgentQueryPlan.Aggregation.SUM, AgentQueryPlan.Aggregation.AVG),
                null,
                new AgentQueryPlan.Rank(3, AgentQueryPlan.Rank.Direction.LARGEST));
        assertThat(mapper.readValue(mapper.writeValueAsString(plan), AgentQueryPlan.class)).isEqualTo(plan);
    }

    @Test
    void emptyAggregateDefaultsToSum() {
        AgentQueryPlan plan = new AgentQueryPlan(null, null, null, null, null);
        assertThat(plan.aggregate()).containsExactly(AgentQueryPlan.Aggregation.SUM);
        assertThat(plan.groupBy()).isEmpty();
    }

    @Test
    void recommendationAndToolResultRoundTrip() throws Exception {
        ResultObject number = new ResultObject("r1", "Equity share of net worth", new BigDecimal("62.5"),
                ResultObject.Unit.PERCENT, null, List.of("rec-1", "rec-2"), null);
        Recommendation rec = new Recommendation("concentration", "Most of your wealth is in equities.",
                List.of(number),
                List.of(new Recommendation.Assumption("threshold", "50", "PERCENT", true, "Etherm default")),
                List.of("rec-1"),
                new Recommendation.SuggestedAction(Recommendation.SuggestedAction.Code.REBALANCE,
                        "Consider reviewing your mix."),
                "Decision support, not advice.");
        ToolResult result = new ToolResult("call-1", ToolName.RUN_RULES, List.of("consent-1"), List.of(number),
                List.of(rec), null, List.of());
        assertThat(mapper.readValue(mapper.writeValueAsString(result), ToolResult.class)).isEqualTo(result);
    }

    @Test
    void emptyResultsKeepTheirReason() throws Exception {
        ResultObject empty = new ResultObject("r2", "Credit score", null, null, null, null,
                ResultObject.EmptyReason.NOT_IN_DATA);
        ResultObject back = mapper.readValue(mapper.writeValueAsString(empty), ResultObject.class);
        assertThat(back.value()).isNull();
        assertThat(back.emptyReason()).isEqualTo(ResultObject.EmptyReason.NOT_IN_DATA);
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static List<String> values(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }
}
