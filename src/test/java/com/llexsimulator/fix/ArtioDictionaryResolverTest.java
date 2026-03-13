package com.llexsimulator.fix;

import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.dictionary.FixDictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ArtioDictionaryResolverTest {

    @Test
    void resolvesGeneratedFix44Dictionary() throws Exception {
        Class<? extends FixDictionary> dictionaryClass = ArtioDictionaryResolver.resolve();

        assertNotNull(dictionaryClass);
        assertEquals("uk.co.real_logic.artio.FixDictionaryImpl", dictionaryClass.getName());
        assertEquals("FIX.4.4", dictionaryClass.getDeclaredConstructor().newInstance().beginString());
    }
}

