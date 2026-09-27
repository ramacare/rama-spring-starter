package org.rama.meilisearch;

import org.junit.jupiter.api.Test;
import org.rama.entity.master.MasterItem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncToMeilisearchEntitiesTest {

    /**
     * Regression guard mirroring {@link MeilisearchIndexInitializerTest} -- MasterItem lives in
     * {@code org.rama.entity.master}, outside any application's own base package, so it must
     * still be found via the starter's own {@code org.rama} fallback.
     */
    @Test
    void classes_includesTheStartersOwnEntities_evenWhenOnlyAnApplicationPackageIsConfigured() {
        SyncToMeilisearchEntities entities = new SyncToMeilisearchEntities(List.of("com.example.app"));

        assertThat(entities.classes()).contains(MasterItem.class);
        assertThat(entities.names()).contains("MasterItem");
    }

    @Test
    void resolve_returnsTheMatchingClass() {
        SyncToMeilisearchEntities entities = new SyncToMeilisearchEntities(List.of(MasterItem.class));

        assertThat(entities.resolve("MasterItem")).isEqualTo(MasterItem.class);
    }

    @Test
    void resolve_throwsOnUnknownEntityName() {
        SyncToMeilisearchEntities entities = new SyncToMeilisearchEntities(List.of(MasterItem.class));

        assertThatThrownBy(() -> entities.resolve("Nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nope")
                .hasMessageContaining("MasterItem");
    }

    @Test
    void constructor_doesNotThrow_whenTheSameClassAppearsTwice() {
        assertThatCode(() -> new SyncToMeilisearchEntities(List.of(MasterItem.class, MasterItem.class)))
                .doesNotThrowAnyException();
    }

    /**
     * The user's explicit ask: two DIFFERENT {@code @SyncToMeilisearch} entities that happen to
     * share a simple class name must fail fast at startup, not silently collide -- Meilisearch's
     * own default index name is this same simple name lowercased, so a real deployment with this
     * collision would already be broken for indexing, just less obviously.
     */
    @Test
    void constructor_throwsOnSimpleNameCollision_betweenDifferentClasses() {
        class Dup {
        }
        Class<?> first = Dup.class;
        Class<?> second = aDifferentClassAlsoNamedDup();

        assertThatThrownBy(() -> new SyncToMeilisearchEntities(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dup");
    }

    private static Class<?> aDifferentClassAlsoNamedDup() {
        class Dup {
        }
        return Dup.class;
    }
}
