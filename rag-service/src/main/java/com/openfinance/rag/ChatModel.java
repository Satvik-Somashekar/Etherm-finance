package com.openfinance.rag;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

/** A model that can call tools. One request in, the raw response out, so the loop stays in {@link ChatService}. */
public interface ChatModel {

    /** Sends one generateContent-style request body and returns the response JSON. */
    JsonNode generate(Map<String, Object> body) throws IOException, InterruptedException;

    String name();
}
