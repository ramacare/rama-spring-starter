package org.rama.autoconfigure.quartz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Contributes the starter's Quartz defaults ({@code rama-quartz-defaults.properties}) to the
 * {@link ConfigurableEnvironment} before any configuration class is parsed.
 *
 * <p><strong>Why not {@code @PropertySource}?</strong> The defaults used to ride on
 * {@code @PropertySource} on {@code RamaStarterAutoConfiguration}. A {@code @PropertySource} is
 * only added to the environment when its configuration class is <em>parsed</em>, and this
 * auto-configuration deliberately declares
 * {@code @AutoConfiguration(afterName = "…QuartzAutoConfiguration")}, so it is parsed last.
 * By then Boot has already evaluated
 * {@code QuartzAutoConfiguration.JdbcStoreTypeConfiguration}, a nested member
 * {@code @Configuration} guarded by
 * {@code @ConditionalOnProperty("spring.quartz.job-store-type" = "jdbc")} — a
 * {@code PARSE_CONFIGURATION}-phase condition. The property was not there yet, so the nested
 * class was skipped, no {@code DataSource} was attached, and Quartz silently fell back to
 * {@code RAMJobStore} — which then blew up on the clustered {@code jobStore.*} defaults from the
 * very same file (those <em>do</em> arrive in time, because {@code QuartzProperties} binds at
 * bean-creation time). Net effect: {@code spring.quartz.job-store-type=jdbc} in the starter's
 * defaults never took effect, and every consumer had to restate it by hand.
 *
 * <p>An {@code EnvironmentPostProcessor} runs during environment preparation, long before any
 * condition is evaluated, which is the only phase early enough. Same family of defect as
 * starter#46 / starter#47 — a condition on a nested member {@code @Configuration} reading state
 * that auto-configuration ordering cannot put in place in time.
 *
 * <p>The property source is appended <em>last</em>, so anything the consumer sets — command line,
 * {@code application.properties}, config server — wins. One adjustment is made on the way in: if
 * the consumer has selected a non-JDBC job store, the JDBC-only defaults ({@code isClustered},
 * {@code tablePrefix}, {@code lockHandler.class}) are withheld. {@code RAMJobStore} has no setter
 * for them and Quartz refuses to start; because property layering can override a value but never
 * remove one, a consumer previously had no way to ask for an in-memory scheduler at all.
 *
 * <p>Choosing the JDBC driver delegate is <em>not</em> done here — that needs to know which engine
 * the {@code DataSource} actually points at, which is a question for
 * {@link RamaQuartzDriverDelegateAutoConfiguration}, not for a string match on a URL that may not
 * even be in the environment yet.
 *
 * <p>Set {@code rama.quartz.apply-defaults=false} to contribute nothing at all.
 */
public class RamaQuartzDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "ramaQuartzDefaults";
    static final String ENABLED_PROPERTY = "rama.quartz.apply-defaults";
    static final String DEFAULTS_RESOURCE = "rama-quartz-defaults.properties";
    static final String JOB_STORE_TYPE = "spring.quartz.job-store-type";

    /**
     * Defaults that only make sense against the JDBC job store. {@code RAMJobStore} has no setter
     * for any of them and Quartz treats an unknown property as a fatal misconfiguration.
     */
    private static final String[] JDBC_ONLY_KEYS = {
            "spring.quartz.jdbc.initialize-schema",
            "spring.quartz.properties.org.quartz.jobStore.tablePrefix",
            "spring.quartz.properties.org.quartz.jobStore.isClustered",
            "spring.quartz.properties.org.quartz.jobStore.lockHandler.class",
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        postProcess(environment);
    }

    private void postProcess(ConfigurableEnvironment environment) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        if (!environment.getProperty(ENABLED_PROPERTY, Boolean.class, Boolean.TRUE)) {
            return;
        }

        Map<String, Object> defaults = loadDefaults();
        if (defaults.isEmpty()) {
            return;
        }

        String jobStoreType = environment.getProperty(JOB_STORE_TYPE,
                String.valueOf(defaults.getOrDefault(JOB_STORE_TYPE, "")));
        boolean jdbc = "jdbc".equalsIgnoreCase(jobStoreType);

        if (!jdbc) {
            for (String key : JDBC_ONLY_KEYS) {
                defaults.remove(key);
            }
        }

        // Last means lowest precedence: every consumer-supplied source is consulted first.
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    private Map<String, Object> loadDefaults() {
        Resource resource = new ClassPathResource(DEFAULTS_RESOURCE, getClass().getClassLoader());
        if (!resource.exists()) {
            return new LinkedHashMap<>();
        }
        Properties properties;
        try {
            properties = PropertiesLoaderUtils.loadProperties(resource);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load " + DEFAULTS_RESOURCE, ex);
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        properties.forEach((key, value) -> defaults.put(String.valueOf(key), value));
        return defaults;
    }

    @Override
    public int getOrder() {
        // After Boot's own config-data processing, so a consumer's application.properties has
        // already been loaded and `spring.quartz.job-store-type` reflects their choice.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
