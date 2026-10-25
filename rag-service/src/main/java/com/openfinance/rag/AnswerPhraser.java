package com.openfinance.rag;

import com.openfinance.core.compute.ComputedAnswer;

/** Writes the sentence. It receives finished figures and must not change or invent any. */
public interface AnswerPhraser {

    Phrasing phrase(String question, ComputedAnswer answer);

    /** @param phrasedBy "template" or the model id that wrote the sentence */
    record Phrasing(String text, String phrasedBy) {
    }
}
