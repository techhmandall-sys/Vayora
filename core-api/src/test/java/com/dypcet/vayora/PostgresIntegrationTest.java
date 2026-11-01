package com.dypcet.vayora;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that run the real application against a real PostGIS, migrated by
 * Flyway, a real MongoDB and a real Redis. One container of each is shared by every
 * subclass and lives until the JVM exits. No routing service is configured, so routes
 * are straight lines between stops.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    /**
     * Spring keeps up to 32 test contexts cached, each holding its own pool of 10
     * connections; Postgres's default of 100 runs out once more than a few exist.
     */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=400");

    private static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        MONGO.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
