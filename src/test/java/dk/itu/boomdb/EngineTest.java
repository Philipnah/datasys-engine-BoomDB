package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EngineTest {
    @Test
    void teamName() {
        assertEquals("Team BoomDB", new Engine().teamName());
    }
}
