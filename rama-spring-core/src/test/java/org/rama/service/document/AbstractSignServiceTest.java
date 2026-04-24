package org.rama.service.document;

import com.itextpdf.signatures.ITSAClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import xades4j.providers.impl.HttpTsaConfiguration;

import java.lang.reflect.Field;
import java.security.cert.Certificate;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class AbstractSignServiceTest {

    private static byte[] fontBytes(AbstractSignService service) throws Exception {
        Field f = AbstractSignService.class.getDeclaredField("fontBytes");
        f.setAccessible(true);
        return (byte[]) f.get(service);
    }

    private static class NoopSignService extends AbstractSignService {
        NoopSignService(String fontPath, String logoPath) {
            super((ITSAClient) null, (HttpTsaConfiguration) null, fontPath, logoPath);
        }

        NoopSignService(String logoPath) {
            super((ITSAClient) null, (HttpTsaConfiguration) null, logoPath);
        }

        @Override
        protected SigningMaterial resolveSigningMaterial(String alias, String commonName) {
            return new SigningMaterial(new Certificate[0], null);
        }
    }

    @Test
    void constructor_whenFontPathNull_shouldLoadDefaultEmbeddedFont() throws Exception {
        NoopSignService service = new NoopSignService(null, null);
        assertThat(fontBytes(service)).isNotNull().isNotEmpty();
    }

    @Test
    void constructor_whenFontPathBlank_shouldLoadDefaultEmbeddedFont() throws Exception {
        NoopSignService service = new NoopSignService("  ", null);
        assertThat(fontBytes(service)).isNotNull().isNotEmpty();
    }

    @Test
    void constructor_withoutFontPath_shouldLoadDefaultEmbeddedFont() throws Exception {
        NoopSignService service = new NoopSignService(null);
        assertThat(fontBytes(service)).isNotNull().isNotEmpty();
    }

    @Test
    void constructor_withExplicitFontPath_shouldLoadThatFont() throws Exception {
        NoopSignService service = new NoopSignService(AbstractSignService.DEFAULT_FONT_RESOURCE, null);
        assertThat(fontBytes(service)).isNotNull().isNotEmpty();
    }

    @Test
    void constructor_withMissingFontPath_shouldSilentlyLoadNoFont() throws Exception {
        NoopSignService service = new NoopSignService("/fonts/does-not-exist.ttf", null);
        assertThat(fontBytes(service)).isNull();
    }

    @Test
    void defaultFontResource_shouldBeNamespacedPath() {
        assertThat(AbstractSignService.DEFAULT_FONT_RESOURCE).isEqualTo("/org/rama/fonts/THSarabunNew.ttf");
    }
}
