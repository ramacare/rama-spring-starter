package org.rama.demo.mongo;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.mongo.IndexAwareMongoTemplate;
import org.rama.service.GenericMongoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for starter#34: the deferred auto-index feature tracked nothing because
 * {@code IndexAwareMongoTemplate} was registered as a second, non-primary {@code MongoTemplate}
 * bean. Every consumer declares its parameter as {@code MongoTemplate mongoTemplate}, so Spring
 * resolved the ambiguity by bean name and handed them Boot's plain template — leaving the
 * tracking template inert and no index ever created.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "rama.mongo.enabled=true",
        "rama.mongo.deferred-indexes-enabled=true",
        // No server is contacted at startup — MongoTemplate connects lazily.
        "spring.data.mongodb.uri=mongodb://localhost:27017/ramademo-probe"
})
class MongoDeferredIndexWiringIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private GenericMongoService genericMongoService;

    @Test
    void mongoTemplateInjection_shouldResolveToTheTrackingTemplate() {
        assertThat(context.getBean(MongoTemplate.class))
                .as("the primary MongoTemplate must be the tracking one, or no query is ever tracked")
                .isInstanceOf(IndexAwareMongoTemplate.class);
    }

    @Test
    void genericMongoService_shouldQueryThroughTheTrackingTemplate() {
        assertThat(injectedTemplate(genericMongoService))
                .as("GenericMongoService is the entry point consumers query through")
                .isInstanceOf(IndexAwareMongoTemplate.class);
    }

    private Object injectedTemplate(Object bean) {
        Field field = ReflectionUtils.findField(bean.getClass(), "mongoTemplate");
        assertThat(field).as("mongoTemplate field on %s", bean.getClass()).isNotNull();
        ReflectionUtils.makeAccessible(field);
        return ReflectionUtils.getField(field, bean);
    }
}
