package org.rama.demo.system;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.entity.system.SystemTemplate;
import org.rama.repository.system.SystemTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
@Transactional
class SystemTemplateCodeNameIT {

    @Autowired
    SystemTemplateRepository systemTemplateRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    void systemTemplate_persistsAndReloadsTemplateCodeAndName() {
        SystemTemplate template = new SystemTemplate();
        template.setId("TPL_DISCHARGE");
        template.setTemplateCode("DISCHARGE");
        template.setTemplateName("Discharge Summary");
        template.setTemplate("body");

        // saveAndFlush issues the INSERT — fails here if the DB columns don't exist
        systemTemplateRepository.saveAndFlush(template);
        // clear the persistence context so findById reloads from the DB, not the cache
        entityManager.clear();

        SystemTemplate reloaded = systemTemplateRepository.findById("TPL_DISCHARGE").orElseThrow();
        assertThat(reloaded.getTemplateCode()).isEqualTo("DISCHARGE");
        assertThat(reloaded.getTemplateName()).isEqualTo("Discharge Summary");
    }
}
