package org.rama.autoconfigure;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentPropertiesTest {
    @Test
    void baseTemplateProperty_defaultsToBaseTemplate() {
        assertThat(new DocumentProperties().getBaseTemplateProperty()).isEqualTo("BaseTemplate");
    }
}
