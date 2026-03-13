package com.llexsimulator.fix;

import uk.co.real_logic.artio.dictionary.FixDictionary;

/**
 * Resolves the generated Artio FIX dictionary without creating a direct source
 * dependency on generated code at IDE import time.
 */
public final class ArtioDictionaryResolver {

    static final String GENERATED_DICTIONARY_CLASS = "uk.co.real_logic.artio.FixDictionaryImpl";

    private ArtioDictionaryResolver() {}

    @SuppressWarnings("unchecked")
    public static Class<? extends FixDictionary> resolve() {
        try {
            Class<?> dictionaryClass = Class.forName(
                    GENERATED_DICTIONARY_CLASS,
                    true,
                    ArtioDictionaryResolver.class.getClassLoader());
            if (!FixDictionary.class.isAssignableFrom(dictionaryClass)) {
                throw new IllegalStateException(
                        "Resolved Artio dictionary class '" + GENERATED_DICTIONARY_CLASS
                                + "' does not implement FixDictionary");
            }
            return (Class<? extends FixDictionary>)dictionaryClass;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Generated Artio FIX dictionary class '" + GENERATED_DICTIONARY_CLASS
                            + "' is missing. Run './gradlew generateArtioSources' or './gradlew build' first.",
                    e);
        }
    }
}

