package com.ojt.board.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class FlywayMigrationDiscoveryTest {

    @Test
    void discoversEachMigrationVersionExactlyOnce() {
        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:h2:mem:migration-discovery;DB_CLOSE_DELAY=-1", "sa", "")
                .locations("classpath:db/migration")
                .load();

        List<String> versions = Arrays.stream(flyway.info().all())
                .map(info -> info.getVersion().getVersion())
                .toList();
        assertEquals(List.of("1", "2", "3", "4"), versions);
    }
}
