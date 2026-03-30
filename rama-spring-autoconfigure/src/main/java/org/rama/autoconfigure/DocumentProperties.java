package org.rama.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "document")
public class DocumentProperties {
    private String gotenbergServer = "http://localhost:3000";
    private String placeholderPattern = "\\{\\{(.+?)\\}\\}";
    private String sectionStartPattern = "\\{\\{[^\\{\\}]*startsec[^\\{\\}]*\\}\\}";
    private String sectionEndPattern = "\\{\\{[\\s]*placeholder[^\\{\\}]*endsec[^\\{\\}]*\\}\\}";
    private String sectionItemPattern = "\\{\\{[\\s]*placeholder[^\\{\\}]*\\}\\}";
    private String repeatAttributeProperty = "RepeatAttribute";
    private String maximumPagesProperty = "MaximumPages";
}
