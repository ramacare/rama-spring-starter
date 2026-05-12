package org.rama.autoconfigure;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ClickHousePropertiesTest {

    @Test
    void shouldBindFromProperties() {
        Map<String, Object> source = Map.of(
                "rama.revision.clickhouse.enabled", "true",
                "rama.revision.clickhouse.url", "jdbc:ch://ch.example:8123/audit",
                "rama.revision.clickhouse.username", "writer",
                "rama.revision.clickhouse.password", "secret",
                "rama.revision.clickhouse.table-name", "revision",
                "rama.revision.clickhouse.batch-size", "5000",
                "rama.revision.clickhouse.flush-interval", "PT3S");

        ClickHouseProperties props = Binder.get(
                new org.springframework.core.env.StandardEnvironment() {{
                    getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("test", source));
                }})
                .bind("rama.revision.clickhouse", ClickHouseProperties.class)
                .get();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getUrl()).isEqualTo("jdbc:ch://ch.example:8123/audit");
        assertThat(props.getUsername()).isEqualTo("writer");
        assertThat(props.getPassword()).isEqualTo("secret");
        assertThat(props.getTableName()).isEqualTo("revision");
        assertThat(props.getBatchSize()).isEqualTo(5000);
        assertThat(props.getFlushInterval()).hasSeconds(3);
    }

    @Test
    void shouldHaveSensibleDefaults() {
        ClickHouseProperties props = new ClickHouseProperties();
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getTableName()).isEqualTo("revision");
        assertThat(props.getBatchSize()).isEqualTo(1000);
        assertThat(props.getFlushInterval()).hasSeconds(5);
    }
}
