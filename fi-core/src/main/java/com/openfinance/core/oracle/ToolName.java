package com.openfinance.core.oracle;

/** The five tools the agent may call. Each has a JSON schema in resources/oracle/tools/. */
public enum ToolName {
    RUN_QUERY_PLAN("run_query_plan"),
    FIND_ENTITIES("find_entities"),
    RUN_RULES("run_rules"),
    PROJECT_SCENARIO("project_scenario"),
    GOALS("goals");

    private final String wireName;

    ToolName(String wireName) {
        this.wireName = wireName;
    }

    /** The name the model uses when it calls the tool. */
    public String wireName() {
        return wireName;
    }
}
