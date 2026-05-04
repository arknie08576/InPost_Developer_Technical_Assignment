package com.inpostatlas;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "atlas.database.path=:memory:",
        "atlas.output.directory=./build/test-output"
})
class InpostAtlasApplicationTests {

    @Test
    void contextLoads() {
    }
}
