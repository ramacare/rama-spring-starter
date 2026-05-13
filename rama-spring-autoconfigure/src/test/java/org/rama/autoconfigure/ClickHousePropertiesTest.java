package org.rama.autoconfigure;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ClickHousePropertiesTest {

    @Test
    void defaults_areApplied() {
        ClickHouseProperties props = bind(Map.of());
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getTableName()).isEqualTo("revision");
        assertThat(props.getDrainBatchSize()).isEqualTo(1000);
        assertThat(props.getDrainInterval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void valuesBindFromProperties() {
        ClickHouseProperties props = bind(Map.of(
                "rama.revision.clickhouse.enabled", "true",
                "rama.revision.clickhouse.url", "jdbc:ch://example:8123/db",
                "rama.revision.clickhouse.username", "u",
                "rama.revision.clickhouse.password", "p",
                "rama.revision.clickhouse.table-name", "revision_v2",
                "rama.revision.clickhouse.drain-batch-size", "250",
                "rama.revision.clickhouse.drain-interval", "10s"
        ));
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getUrl()).isEqualTo("jdbc:ch://example:8123/db");
        assertThat(props.getUsername()).isEqualTo("u");
        assertThat(props.getPassword()).isEqualTo("p");
        assertThat(props.getTableName()).isEqualTo("revision_v2");
        assertThat(props.getDrainBatchSize()).isEqualTo(250);
        assertThat(props.getDrainInterval()).isEqualTo(Duration.ofSeconds(10));
    }

    private static ClickHouseProperties bind(Map<String, Object> properties) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("test", properties));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bindOrCreate("rama.revision.clickhouse", ClickHouseProperties.class);
    }
}
