package com.openfinance.core.oracle;

/** What the model is told about one tool: its name, purpose and JSON schema for the arguments. */
public record ToolDefinition(String name, String description, String inputSchemaJson) {
}
