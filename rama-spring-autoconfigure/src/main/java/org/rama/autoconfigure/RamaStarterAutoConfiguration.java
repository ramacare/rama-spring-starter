package org.rama.autoconfigure;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import graphql.scalars.ExtendedScalars;
import graphql.validation.rules.OnValidationErrorStrategy;
import graphql.validation.rules.ValidationRules;
import graphql.validation.schemawiring.ValidationSchemaWiring;
import io.minio.MinioClient;
import liquibase.integration.spring.SpringLiquibase;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.rama.aspect.IdempotencyAspect;
import org.rama.entity.Revision;
import org.rama.entity.api.Api;
import org.rama.entity.security.ApiKey;
import org.rama.entity.system.SystemRequestDedup;
import org.rama.job.system.SystemRequestDedupCleanupJob;
import org.rama.repository.system.SystemRequestDedupRepository;
import org.rama.service.idempotency.IdempotencyProperties;
import org.rama.service.idempotency.IdempotencyService;
import org.rama.service.idempotency.ResponseCodec;
import org.rama.service.idempotency.SignatureResolver;
import org.rama.ftp.FtpProperties;
import org.rama.graphql.StarterGraphqlExceptionResolver;
import org.rama.graphql.directive.AuthDirectiveInstrumentation;
import org.rama.graphql.directive.EmailConstraint;
import org.rama.listener.global.GlobalAuditablePreInsertListener;
import org.rama.listener.global.GlobalAuditablePreUpdateListener;
import org.rama.listener.global.GlobalPostInsertEntityEventListener;
import org.rama.listener.global.GlobalPostInsertRevisionListener;
import org.rama.listener.global.GlobalPostUpdateEntityEventListener;
import org.rama.listener.global.GlobalPostUpdateRevisionListener;
import org.rama.meilisearch.MeilisearchIndexInitializer;
import org.rama.meilisearch.listener.GlobalPostInsertMeilisearchListener;
import org.rama.meilisearch.listener.GlobalPostUpdateMeilisearchListener;
import org.rama.meilisearch.mapper.DefaultMeilisearchMapper;
import org.rama.meilisearch.service.LoggingMeilisearchErrorHandler;
import org.rama.meilisearch.service.MeilisearchErrorHandler;
import org.rama.meilisearch.service.MeilisearchService;
import org.rama.mongo.IndexAwareMongoTemplate;
import org.rama.mongo.indexing.DeferredIndexManager;
import org.rama.mongo.listener.GlobalPostInsertSyncToMongoListener;
import org.rama.mongo.listener.GlobalPostUpdateSyncToMongoListener;
import org.rama.mongo.service.MongoSyncService;
import org.rama.repository.api.ApiHeaderSetRepository;
import org.rama.repository.api.ApiRepository;
import org.rama.repository.asset.AssetFileRepository;
import org.rama.repository.master.MasterIdRepository;
import org.rama.repository.master.MasterItemRepository;
import org.rama.repository.revision.RevisionRepository;
import org.rama.repository.system.ClientConfigRepository;
import org.rama.repository.system.ClientUserConfigRepository;
import org.rama.repository.system.UserConfigRepository;
import org.rama.repository.system.SystemLogRepository;
import org.rama.repository.system.SystemParameterRepository;
import org.rama.service.*;
import org.rama.service.EntityEventService;
import org.rama.service.TransactionRunnerService;
import org.rama.service.document.BarcodeReaderService;
import org.rama.service.document.BarcodeService;
import org.rama.service.document.ImageService;
import org.rama.service.document.PdfService;
import org.rama.service.document.printTemplate.TemplatePreprocessor;
import org.rama.service.document.replacement.ReplacementHooks;
import org.rama.service.document.replacement.ReplacementObjectHook;
import org.rama.service.document.replacement.ReplacementStringHook;
import org.rama.service.document.template.DocxTemplatePreprocessor;
import org.rama.service.document.template.DocxTemplateProcessor;
import org.rama.service.document.template.ReplacementProcessor;
import org.rama.service.document.template.docx.ReplacePlaceholder;
import org.rama.service.document.template.docx.ReplaceSection;
import org.rama.service.document.template.hooks.*;
import org.rama.service.environment.EnvironmentService;
import org.rama.service.environment.StaticValueResolver;
import org.rama.service.environment.StaticValueService;
import org.rama.service.master.MasterIdService;
import org.rama.service.master.MasterItemService;
import org.rama.service.system.ClientConfigService;
import org.rama.service.system.ClientUserConfigService;
import org.rama.service.system.UserConfigService;
import org.rama.service.system.QuartzService;
import org.rama.service.system.SystemLogService;
import org.rama.service.system.SystemParameterService;
import org.rama.util.EncryptionUtil;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;
import org.springframework.core.convert.ConversionService;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.boot.jackson.autoconfigure.JacksonProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@AutoConfiguration(beforeName = {
        // Our ramaSecurityContextAsyncConfigurer (an AsyncConfigurer) must be registered
        // before Boot evaluates TaskExecutionAutoConfiguration's
        // @ConditionalOnMissingBean(AsyncConfigurer.class) / @ConditionalOnBean(AsyncConfigurer.class),
        // so Boot wraps OUR configurer (security-context-propagating) instead of registering its
        // own null-delegate default. See starter#29.
        "org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration"
}, afterName = {
        "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration",
        "org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration",
        // LiquibaseAutoConfiguration must be processed before us so its @ConditionalOnMissingBean
        // sees an empty context, registers the default `liquibase` bean, and our
        // ramaStarterLiquibase can then back off when the consumer has configured
        // spring.liquibase.change-log. See starter#13.
        "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration"
})
@EnableConfigurationProperties({RamaStarterProperties.class, RamaStarterLiquibaseProperties.class, AppProperties.class, MinioProperties.class, DocumentProperties.class, MeilisearchProperties.class, EncryptProperties.class, FtpProperties.class, IdempotencyProperties.class})
@org.springframework.scheduling.annotation.EnableAsync
public class RamaStarterAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.persistence.autoconfigure.EntityScan(basePackageClasses = {Api.class, Revision.class, ApiKey.class, SystemRequestDedup.class})
    @ConditionalOnProperty(prefix = "rama.jpa", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RamaStarterJpaConfiguration {
    }

    /**
     * Fallback mapper for contexts without Boot's Jackson auto-configuration.
     *
     * <p><strong>This bean normally does not register.</strong> The
     * {@link ConditionalOnMissingBean} is typed by the return type, {@link ObjectMapper},
     * and Boot's {@code jacksonJsonMapper} is a {@link JsonMapper} — hence an
     * {@code ObjectMapper} — so the condition matches and this backs off. It materializes
     * only when {@code JacksonAutoConfiguration} is excluded or absent.
     *
     * <p>Consequently the starter's services never receive this instance: they all inject
     * {@link JsonMapper} and get Boot's managed bean, which is framed by
     * {@link #ramaStarterTimeZoneCustomizer}. Built directly rather than through the
     * {@link JsonMapperBuilderCustomizer} chain, so on the fallback path
     * {@code spring.jackson.time-zone} is read here explicitly rather than being silently
     * ignored. See starter#39.
     */
    @Bean
    @ConditionalOnMissingBean
    ObjectMapper ramaStarterObjectMapper(ObjectProvider<JacksonProperties> jacksonProperties) {
        JacksonProperties properties = jacksonProperties.getIfAvailable();
        return org.rama.entity.JsonConverter.createObjectMapper(
                properties != null ? properties.getTimeZone() : null);
    }

    /**
     * Apply the same lenient string-to-scalar coercion to Spring Boot's
     * managed {@link JsonMapper} bean as we apply inside
     * {@link org.rama.entity.JsonConverter}. Jackson 3 defaults to
     * {@code CoercionAction.Fail} for {@code "1" -> int}; consumer code
     * (e.g. JSON columns deserialized into POJOs) relies on Jackson 2's
     * {@code TryConvert} behaviour.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ramaStarterCoercionCustomizer")
    JsonMapperBuilderCustomizer ramaStarterCoercionCustomizer() {
        return builder -> builder.withCoercionConfigDefaults(cfg ->
                cfg.setCoercion(CoercionInputShape.String, CoercionAction.TryConvert));
    }

    /**
     * Frame Boot's managed {@link JsonMapper} in the JVM zone, matching
     * {@link org.rama.entity.JsonConverter} and the rest of the starter's datetime
     * handling. This is the mapper injected into {@code GenericEntityService},
     * {@code GenericApiService}, {@code SystemLogService} and the Meilisearch mapper,
     * so without it every GraphQL mutation input re-frames {@code +07:00} to {@code Z}
     * for the duration of the request — long enough for {@code @PrePersist} listeners
     * and validators to read the wrong wall clock. See starter#39.
     *
     * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE} so it runs <em>before</em> Boot's own
     * customizer (which is {@code Ordered} at 0). Boot applies
     * {@code spring.jackson.time-zone} only when that property is set, so an explicit
     * consumer setting overwrites this default, and an unset one leaves it in place. The
     * property therefore wins structurally, without this bean needing to inspect it —
     * every customizer in the list is applied to the same builder, in order.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ramaStarterTimeZoneCustomizer")
    @Order(Ordered.HIGHEST_PRECEDENCE)
    JsonMapperBuilderCustomizer ramaStarterTimeZoneCustomizer() {
        return builder -> builder.defaultTimeZone(java.util.TimeZone.getDefault());
    }

    @Bean
    @ConditionalOnMissingBean(name = "ramaStarterEncryptionInitializer")
    Object ramaStarterEncryptionInitializer(EncryptProperties encryptProperties) {
        EncryptionUtil.setKey(encryptProperties.getKey());
        return new Object();
    }

    @Bean
    @ConditionalOnMissingBean
    WebClient.Builder ramaStarterWebClientBuilder() {
        return WebClient.builder().exchangeStrategies(ExchangeStrategies.builder()
                .codecs(configurer -> {
                    configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024);
                    configurer.customCodecs().register(
                            new org.springframework.http.codec.xml.JacksonXmlDecoder()
                    );
                })
                .build());
    }

    @Bean
    @ConditionalOnProperty(prefix = "minio", name = "endpoint")
    MinioClient ramaStarterMinioClient(MinioProperties minioProperties) {
        return MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AssetFileRepository.class)
    StorageService storageService(AppProperties appProperties, AssetFileRepository assetFileRepository, ObjectProvider<MinioClient> minioClientProvider, ObjectProvider<ImageService> imageServiceProvider) {
        return new StorageService(appProperties.getFileStoragePath(), appProperties.getFileStorageLocation(), assetFileRepository, minioClientProvider, imageServiceProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    GenericEntityService genericEntityService(JsonMapper objectMapper, EntityManager entityManager) {
        return new GenericEntityService(objectMapper, entityManager);
    }

    @Bean
    @ConditionalOnMissingBean
    EnvironmentService environmentService(org.springframework.core.env.Environment environment, ObjectProvider<StaticValueResolver> staticValueResolver) {
        return new EnvironmentService(environment, staticValueResolver);
    }

    /**
     * Propagate the {@link org.springframework.security.core.context.SecurityContext}
     * into {@code @Async} executions so the global Auditable userstamp listener resolves
     * the originating authenticated user even when the entity flush happens on a
     * thread-pool thread (e.g. {@code RevisionService.saveRevision}, or any consumer
     * {@code @Async} service that persists an {@link org.rama.entity.Auditable}).
     *
     * <p>Without this, {@code SecurityContextHolder} (a plain ThreadLocal by default)
     * is empty on the async thread and {@code createdBy}/{@code updatedBy} fall back to
     * the static-value fallback key (or null) instead of the real user. See starter#29.
     *
     * <p>Backs off if the consumer supplies their own {@link AsyncConfigurer}. Reuses
     * Boot's tuned {@code applicationTaskExecutor} when present; otherwise creates a
     * default {@link ThreadPoolTaskExecutor}.
     */
    @Bean
    @ConditionalOnMissingBean(AsyncConfigurer.class)
    AsyncConfigurer ramaSecurityContextAsyncConfigurer(ObjectProvider<ThreadPoolTaskExecutor> taskExecutorProvider) {
        return new AsyncConfigurer() {
            @Override
            public Executor getAsyncExecutor() {
                ThreadPoolTaskExecutor delegate = taskExecutorProvider.getIfUnique();
                if (delegate == null) {
                    delegate = new ThreadPoolTaskExecutor();
                    delegate.initialize();
                }
                return new DelegatingSecurityContextAsyncTaskExecutor(delegate);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(StaticValueResolver.class)
    @ConditionalOnBean(MasterItemRepository.class)
    @ConditionalOnProperty(prefix = "rama.static-values", name = "enabled", havingValue = "true", matchIfMissing = true)
    StaticValueResolver staticValueResolver(MasterItemService masterItemService, RamaStarterProperties properties) {
        return new StaticValueService(
                masterItemService,
                properties.getStaticValues().getGroupKey(),
                properties.getStaticValues().getCurrentUsernameFallbackKey(),
                properties.getStaticValues().getRefreshTtl()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ApiRepository.class, ApiHeaderSetRepository.class})
    GenericApiService genericApiService(ApiRepository apiRepository, ApiHeaderSetRepository apiHeaderSetRepository, WebClient.Builder webClientBuilder, JsonMapper objectMapper) {
        return new GenericApiService(apiRepository, apiHeaderSetRepository, webClientBuilder, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MasterIdRepository.class)
    MasterIdService masterIdService(MasterIdRepository masterIdRepository) {
        return new MasterIdService(masterIdRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MasterItemRepository.class)
    MasterItemService masterItemService(MasterItemRepository masterItemRepository, JPAQueryFactory queryFactory) {
        return new MasterItemService(masterItemRepository, queryFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RevisionRepository.class)
    @ConditionalOnProperty(prefix = "rama.revision", name = "enabled", havingValue = "true", matchIfMissing = true)
    RevisionService revisionService(RevisionRepository revisionRepository) {
        return new RevisionService(revisionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SystemParameterRepository.class)
    SystemParameterService systemParameterService(SystemParameterRepository systemParameterRepository) {
        return new SystemParameterService(systemParameterRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SystemLogRepository.class)
    SystemLogService systemLogService(SystemLogRepository systemLogRepository, JsonMapper objectMapper) {
        return new SystemLogService(systemLogRepository, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ClientConfigRepository.class, SystemLogRepository.class})
    ClientConfigService clientConfigService(ClientConfigRepository clientConfigRepository, SystemLogRepository systemLogRepository) {
        return new ClientConfigService(clientConfigRepository, systemLogRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ClientUserConfigRepository.class)
    ClientUserConfigService clientUserConfigService(ClientUserConfigRepository clientUserConfigRepository) {
        return new ClientUserConfigService(clientUserConfigRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(UserConfigRepository.class)
    UserConfigService userConfigService(UserConfigRepository userConfigRepository, EnvironmentService environmentService) {
        return new UserConfigService(userConfigRepository, environmentService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Scheduler.class)
    QuartzService quartzService(Scheduler scheduler, BeanFactory beanFactory, RamaStarterProperties properties) {
        List<String> basePackages = new java.util.ArrayList<>(AutoConfigurationPackages.has(beanFactory)
                ? AutoConfigurationPackages.get(beanFactory)
                : Collections.emptyList());
        basePackages.addAll(properties.getQuartz().getAllowedJobPackages());
        return new QuartzService(scheduler, basePackages);
    }

    /**
     * Gated on the property alone. {@code @ConditionalOnProperty} is a
     * {@code PARSE_CONFIGURATION}-phase condition, so it is evaluated while the
     * configuration class is parsed and cannot be affected by bean-definition
     * ordering.
     *
     * <p>This class used to also carry
     * {@code @ConditionalOnBean(SystemRequestDedupRepository.class)}. That is a
     * {@code REGISTER_BEAN}-phase condition, and
     * {@link org.springframework.context.annotation.ConfigurationClassParser}
     * processes member classes before their enclosing class finishes, so a nested
     * configuration is evaluated ahead of the {@code @Bean} methods around it —
     * against a bean factory that may not yet hold the repository definitions the
     * consumer's {@code @EnableJpaRepositories} contributes. When it lost that
     * race the entire configuration was skipped: no aspect, no service, no log
     * line, and {@code @IdempotentMutation} silently guarded nothing.
     *
     * <p>The repository is now resolved through {@link ObjectProvider} inside the
     * {@code @Bean} methods instead. Those run during bean creation, after every
     * definition is registered, so the outcome no longer depends on ordering; a
     * genuinely absent repository produces a startup warning and a descriptive
     * failure on use rather than silence. See starter#46.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "rama.idempotency", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RamaStarterIdempotencyConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "idempotencyClock")
        Clock idempotencyClock() {
            return Clock.systemUTC();
        }

        @Bean
        @ConditionalOnMissingBean
        SignatureResolver signatureResolver(EnvironmentService environmentService,
                                            IdempotencyProperties properties,
                                            @org.springframework.beans.factory.annotation.Qualifier("idempotencyClock") Clock clock) {
            return new SignatureResolver(environmentService, properties, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        ResponseCodec responseCodec() {
            return new ResponseCodec();
        }

        @Bean
        @ConditionalOnMissingBean
        IdempotencyService idempotencyService(ObjectProvider<SystemRequestDedupRepository> repositoryProvider,
                                              EntityManager entityManager,
                                              ResponseCodec responseCodec,
                                              org.springframework.transaction.PlatformTransactionManager transactionManager) {
            return new IdempotencyService(repositoryProvider.getIfAvailable(), entityManager, responseCodec, transactionManager);
        }

        @Bean
        @ConditionalOnMissingBean
        IdempotencyAspect idempotencyAspect(IdempotencyService idempotencyService,
                                            SignatureResolver signatureResolver,
                                            EnvironmentService environmentService,
                                            IdempotencyProperties properties,
                                            @org.springframework.beans.factory.annotation.Qualifier("idempotencyClock") Clock clock) {
            return new IdempotencyAspect(idempotencyService, signatureResolver, environmentService, properties, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        SystemRequestDedupCleanupJob systemRequestDedupCleanupJob(ObjectProvider<SystemRequestDedupRepository> repositoryProvider) {
            return new SystemRequestDedupCleanupJob(repositoryProvider.getIfAvailable());
        }

        /**
         * Gated on the Quartz classes being present, not on a {@code Scheduler} bean.
         *
         * <p>This carried {@code @ConditionalOnBean(Scheduler.class)} and never matched,
         * so the cleanup job was never scheduled and {@code system_request_dedup} grew
         * without bound. {@code @ConditionalOnBean} is a {@code REGISTER_BEAN}-phase
         * condition and this is a {@code @Bean} method of a nested member
         * {@code @Configuration}, which is evaluated ahead of the enclosing class — early
         * enough that {@code QuartzAutoConfiguration} had not yet contributed the
         * {@code Scheduler}, despite this auto-configuration correctly declaring
         * {@code afterName = QuartzAutoConfiguration}. Auto-configuration ordering cannot
         * reach a condition that runs before the ordering applies.
         *
         * <p>{@code @ConditionalOnClass} is order-independent — it inspects the classpath,
         * never the bean factory — and states the real requirement. A {@link JobDetail}
         * bean is inert when no scheduler collects it, so registering it without a
         * {@code Scheduler} present costs nothing. See starter#47, and starter#46 for the
         * same mechanism at class level.
         */
        @Bean
        @ConditionalOnClass(Scheduler.class)
        @ConditionalOnMissingBean(name = "systemRequestDedupCleanupJobDetail")
        JobDetail systemRequestDedupCleanupJobDetail() {
            return JobBuilder.newJob(SystemRequestDedupCleanupJob.class)
                    .withIdentity("system-request-dedup-cleanup", "rama-idempotency")
                    .storeDurably()
                    .build();
        }

        /** @see #systemRequestDedupCleanupJobDetail() for why this is not gated on a {@code Scheduler} bean. */
        @Bean
        @ConditionalOnClass(Scheduler.class)
        @ConditionalOnMissingBean(name = "systemRequestDedupCleanupTrigger")
        Trigger systemRequestDedupCleanupTrigger(JobDetail systemRequestDedupCleanupJobDetail, IdempotencyProperties properties) {
            long intervalMs = Math.max(1_000L, properties.getCleanupInterval().toMillis());
            return TriggerBuilder.newTrigger()
                    .forJob(systemRequestDedupCleanupJobDetail)
                    .withIdentity("system-request-dedup-cleanup-trigger", "rama-idempotency")
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInMilliseconds(intervalMs)
                            .repeatForever())
                    .build();
        }
    }

    @Bean
    @ConditionalOnMissingBean
    BarcodeReaderService barcodeReaderService() {
        return new BarcodeReaderService();
    }

    @Bean
    @ConditionalOnMissingBean
    BarcodeService barcodeService() {
        return new BarcodeService();
    }

    @Bean
    @ConditionalOnMissingBean
    ImageService imageService() {
        return new ImageService();
    }

    @Bean
    @ConditionalOnMissingBean
    PdfService pdfService(WebClient.Builder webClientBuilder, DocumentProperties documentProperties) {
        return new PdfService(webClientBuilder, documentProperties.getGotenbergServer());
    }

    @Bean
    @ConditionalOnMissingBean
    ReplacementHooks replacementHooks(Map<String, ReplacementObjectHook> objectHooks, Map<String, ReplacementStringHook> stringHooks) {
        return new ReplacementHooks(objectHooks, stringHooks);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StorageProvider.class)
    ReplacementProcessor replacementProcessor(ReplacementHooks replacementHooks, StorageProvider storageService) {
        return new ReplacementProcessor(replacementHooks, storageService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StorageProvider.class)
    DocxTemplatePreprocessor docxTemplatePreprocessor(DocumentProperties documentProperties, StorageProvider storageService) {
        return new DocxTemplatePreprocessor(storageService, documentProperties.getPlaceholderPattern());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ReplacementProcessor.class, BarcodeService.class})
    ReplacePlaceholder replacePlaceholder(ReplacementProcessor replacementProcessor, BarcodeService barcodeService) {
        return new ReplacePlaceholder(replacementProcessor, barcodeService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReplacePlaceholder.class)
    ReplaceSection replaceSection(DocumentProperties documentProperties, ReplacePlaceholder replacePlaceholder) {
        return new ReplaceSection(
                documentProperties.getSectionStartPattern(),
                documentProperties.getSectionEndPattern(),
                documentProperties.getSectionItemPattern(),
                replacePlaceholder
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({PdfService.class, ReplacementProcessor.class, BarcodeService.class, ReplacePlaceholder.class, ReplaceSection.class})
    DocxTemplateProcessor docxTemplateProcessor(
            DocumentProperties documentProperties,
            BarcodeService barcodeService,
            PdfService pdfService,
            ReplacementProcessor replacementProcessor,
            ReplacePlaceholder replacePlaceholder,
            ReplaceSection replaceSection
    ) {
        return new DocxTemplateProcessor(
                documentProperties.getPlaceholderPattern(),
                documentProperties.getRepeatAttributeProperty(),
                documentProperties.getMaximumPagesProperty(),
                barcodeService,
                pdfService,
                replacementProcessor,
                replacePlaceholder,
                replaceSection
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DocxTemplatePreprocessor.class)
    TemplatePreprocessor templatePreprocessor(DocxTemplatePreprocessor docxTemplatePreprocessor) {
        return new TemplatePreprocessor(docxTemplatePreprocessor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "checkBoxHooks")
    CheckBoxHooks checkBoxHooks(ConversionService conversionService) {
        return new CheckBoxHooks(conversionService);
    }

    @Bean
    @ConditionalOnMissingBean(name = "joinHooks")
    JoinHooks joinHooks() {
        return new JoinHooks();
    }

    @Bean
    @ConditionalOnMissingBean(name = "generalHooks")
    GeneralHooks generalHooks() {
        return new GeneralHooks();
    }

    @Bean
    @ConditionalOnMissingBean(name = "datetimeHooks")
    DatetimeHooks datetimeHooks() {
        return new DatetimeHooks();
    }

    @Bean
    @ConditionalOnMissingBean(name = "stringArrayHooks")
    StringArrayHooks stringArrayHooks() {
        return new StringArrayHooks();
    }

    @Bean
    @ConditionalOnMissingBean(name = "masterHooks")
    @ConditionalOnBean(MasterItemService.class)
    MasterHooks masterHooks(MasterItemService masterItemService) {
        return new MasterHooks(masterItemService);
    }

    @Bean
    @ConditionalOnMissingBean
    TransactionRunnerService transactionRunnerService() {
        return new TransactionRunnerService();
    }

    @Bean
    @ConditionalOnMissingBean
    EntityEventService entityEventService(org.springframework.context.ApplicationEventPublisher publisher, TransactionRunnerService transactionRunnerService) {
        return new EntityEventService(publisher, transactionRunnerService);
    }

    @Bean
    @ConditionalOnBean(VaultTemplate.class)
    @ConditionalOnMissingBean
    VaultService vaultService(VaultTemplate vaultTemplate) {
        return new VaultService(vaultTemplate);
    }

    @Bean
    @ConditionalOnBean(VaultService.class)
    @ConditionalOnMissingBean
    CertificateService certificateService(VaultService vaultService) {
        return new CertificateService(vaultService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.revision", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostInsertRevisionListener globalPostInsertRevisionListener(ObjectProvider<RevisionService> revisionServiceProvider) {
        return new GlobalPostInsertRevisionListener(revisionServiceProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.revision", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostUpdateRevisionListener globalPostUpdateRevisionListener(ObjectProvider<RevisionService> revisionServiceProvider) {
        return new GlobalPostUpdateRevisionListener(revisionServiceProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalPostInsertEntityEventListener globalPostInsertEntityEventListener(ObjectProvider<EntityEventService> entityEventServiceProvider) {
        return new GlobalPostInsertEntityEventListener(entityEventServiceProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalPostUpdateEntityEventListener globalPostUpdateEntityEventListener(ObjectProvider<EntityEventService> entityEventServiceProvider) {
        return new GlobalPostUpdateEntityEventListener(entityEventServiceProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalAuditablePreInsertListener globalAuditablePreInsertListener(EnvironmentService environmentService) {
        return new GlobalAuditablePreInsertListener(environmentService);
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalAuditablePreUpdateListener globalAuditablePreUpdateListener(EnvironmentService environmentService) {
        return new GlobalAuditablePreUpdateListener(environmentService);
    }

    // Both mongo templates below take MongoDatabaseFactory + MongoConverter rather than the
    // MongoTemplate bean: once ramaStarterIndexAwareMongoTemplate is @Primary, injecting
    // MongoTemplate here would resolve to the tracking template itself and create a cycle
    // (manager -> template -> manager). See starter#34.
    @Bean
    @ConditionalOnClass(MongoTemplate.class)
    @ConditionalOnBean(MongoDatabaseFactory.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    DeferredIndexManager deferredIndexManager(MongoDatabaseFactory mongoDatabaseFactory, MongoConverter mongoConverter, RamaStarterProperties properties) {
        return new DeferredIndexManager(
                new MongoTemplate(mongoDatabaseFactory, mongoConverter),
                properties.getMongo().getDeferredIndexThreshold(),
                properties.getMongo().getDeferredIndexFlushInterval().toMillis());
    }

    /**
     * Primary so that everything querying Mongo -- {@link GenericMongoService}, {@link MongoSyncService},
     * consumer code -- goes through the tracking template. Registered as a plain additional bean it was
     * never injected anywhere (parameter-name resolution picked Boot's {@code mongoTemplate}), so the
     * deferred-index feature tracked nothing. See starter#34.
     */
    @Bean
    @Primary
    @ConditionalOnBean({MongoDatabaseFactory.class, DeferredIndexManager.class})
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.mongo", name = "deferred-indexes-enabled", havingValue = "true", matchIfMissing = true)
    IndexAwareMongoTemplate ramaStarterIndexAwareMongoTemplate(MongoDatabaseFactory mongoDatabaseFactory, MongoConverter mongoConverter, DeferredIndexManager deferredIndexManager) {
        return new IndexAwareMongoTemplate(mongoDatabaseFactory, mongoConverter, deferredIndexManager);
    }

    @Bean
    @ConditionalOnBean(MongoDatabaseFactory.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    MongoSyncService mongoSyncService(ApplicationContext applicationContext, MongoTemplate mongoTemplate) {
        return new MongoSyncService(applicationContext, mongoTemplate);
    }

    @Bean
    @ConditionalOnBean(MongoDatabaseFactory.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    GenericMongoService genericMongoService(MongoTemplate mongoTemplate) {
        return new GenericMongoService(mongoTemplate);
    }

    @Bean
    @ConditionalOnBean(MongoSyncService.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostInsertSyncToMongoListener globalPostInsertSyncToMongoListener(MongoSyncService mongoSyncService) {
        return new GlobalPostInsertSyncToMongoListener(mongoSyncService);
    }

    @Bean
    @ConditionalOnBean(MongoSyncService.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostUpdateSyncToMongoListener globalPostUpdateSyncToMongoListener(MongoSyncService mongoSyncService) {
        return new GlobalPostUpdateSyncToMongoListener(mongoSyncService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "meilisearch", name = "host")
    Client ramaStarterMeilisearchClient(MeilisearchProperties meilisearchProperties) {
        return new Client(new Config(meilisearchProperties.getHost(), meilisearchProperties.getMasterKey()));
    }

    @Bean
    @ConditionalOnMissingBean
    MeilisearchErrorHandler meilisearchErrorHandler(ObjectProvider<SystemLogService> systemLogServiceProvider) {
        return new LoggingMeilisearchErrorHandler(systemLogServiceProvider);
    }

    @Bean
    @ConditionalOnBean(Client.class)
    @ConditionalOnMissingBean
    DefaultMeilisearchMapper defaultMeilisearchMapper(JsonMapper objectMapper) {
        return new DefaultMeilisearchMapper(objectMapper);
    }

    @Bean
    @ConditionalOnBean(Client.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.meilisearch", name = "enabled", havingValue = "true", matchIfMissing = true)
    MeilisearchService meilisearchService(ApplicationContext applicationContext, Client client, JsonMapper objectMapper, MeilisearchErrorHandler errorHandler) {
        return new MeilisearchService(applicationContext, client, objectMapper, errorHandler);
    }

    @Bean
    @ConditionalOnBean(MeilisearchService.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.meilisearch", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostInsertMeilisearchListener globalPostInsertMeilisearchListener(MeilisearchService meilisearchService) {
        return new GlobalPostInsertMeilisearchListener(meilisearchService);
    }

    @Bean
    @ConditionalOnBean(MeilisearchService.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.meilisearch", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostUpdateMeilisearchListener globalPostUpdateMeilisearchListener(MeilisearchService meilisearchService) {
        return new GlobalPostUpdateMeilisearchListener(meilisearchService);
    }

    @Bean
    @ConditionalOnBean(MeilisearchService.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.meilisearch", name = "initialize-indexes", havingValue = "true", matchIfMissing = true)
    MeilisearchIndexInitializer meilisearchIndexInitializer(Client client, MeilisearchService meilisearchService, BeanFactory beanFactory) {
        List<String> basePackages = AutoConfigurationPackages.has(beanFactory)
                ? AutoConfigurationPackages.get(beanFactory)
                : Collections.emptyList();
        return new MeilisearchIndexInitializer(client, meilisearchService, basePackages);
    }

    @Bean
    @ConditionalOnClass(DataFetcherExceptionResolverAdapter.class)
    @ConditionalOnMissingBean(StarterGraphqlExceptionResolver.class)
    @ConditionalOnProperty(prefix = "rama.graphql", name = "enabled", havingValue = "true", matchIfMissing = true)
    StarterGraphqlExceptionResolver starterGraphqlExceptionResolver(Environment environment) {
        return new StarterGraphqlExceptionResolver(environment);
    }

    @Bean
    @ConditionalOnClass(RuntimeWiringConfigurer.class)
    @ConditionalOnMissingBean(name = "ramaStarterRuntimeWiringConfigurer")
    @ConditionalOnProperty(prefix = "rama.graphql", name = "enabled", havingValue = "true", matchIfMissing = true)
    RuntimeWiringConfigurer ramaStarterRuntimeWiringConfigurer() {
        ValidationRules validationRules = ValidationRules.newValidationRules()
                .addRule(new EmailConstraint())
                .onValidationErrorStrategy(OnValidationErrorStrategy.RETURN_NULL)
                .build();
        ValidationSchemaWiring schemaWiring = new ValidationSchemaWiring(validationRules);
        return wiringBuilder -> wiringBuilder.directiveWiring(schemaWiring)
                .scalar(ExtendedScalars.DateTime)
                .scalar(ExtendedScalars.Json)
                .scalar(ExtendedScalars.Date)
                .scalar(ExtendedScalars.GraphQLLong)
                .build();
    }

    @Bean
    @ConditionalOnClass(GraphQlSourceBuilderCustomizer.class)
    @ConditionalOnMissingBean(name = "ramaStarterAuthDirectiveCustomizer")
    @ConditionalOnProperty(prefix = "rama.graphql", name = "enabled", havingValue = "true", matchIfMissing = true)
    GraphQlSourceBuilderCustomizer ramaStarterAuthDirectiveCustomizer() {
        return builder -> builder.instrumentation(List.of(new AuthDirectiveInstrumentation()));
    }

    /**
     * Legacy scalar coercion shim (issue #27). graphql-java 22 tightened
     * built-in scalar coercion ({@code Integer} → {@code String} etc.) — clients
     * written against the pre-Spring-Boot-4 stack relied on the lenient
     * fallbacks. The shim restores that behaviour via graphql-java's official
     * {@code LegacyCoercingInputInterceptor.migratesValues()} migration hook.
     * Default <strong>on</strong> — both source consumers (`ramaservice`,
     * `his-service`) already enabled this via a hand-rolled
     * {@code GraphQlStringCoercionConfig}; flipping the default to true on
     * bundling preserves their behaviour without requiring a property line
     * during migration. Set {@code rama.graphql.legacy-coercion.enabled=false}
     * to opt back into graphql-java's strict default for new spec-clean clients.
     */
    @Bean
    @ConditionalOnClass(GraphQlSourceBuilderCustomizer.class)
    @ConditionalOnMissingBean(name = "ramaStarterLegacyCoercionCustomizer")
    @ConditionalOnProperty(prefix = "rama.graphql.legacy-coercion", name = "enabled", havingValue = "true", matchIfMissing = true)
    GraphQlSourceBuilderCustomizer ramaStarterLegacyCoercionCustomizer() {
        return builder -> builder.instrumentation(List.of(new org.rama.graphql.LegacyScalarCoercionInstrumentation()));
    }

    @Bean
    @ConditionalOnMissingBean(name = "ramaStarterHibernatePropertiesCustomizer")
    HibernatePropertiesCustomizer ramaStarterHibernatePropertiesCustomizer(
            ObjectProvider<GlobalAuditablePreInsertListener> preInsertProvider,
            ObjectProvider<GlobalAuditablePreUpdateListener> preUpdateProvider,
            ObjectProvider<GlobalPostInsertRevisionListener> revisionInsertProvider,
            ObjectProvider<GlobalPostUpdateRevisionListener> revisionUpdateProvider,
            ObjectProvider<GlobalPostInsertEntityEventListener> entityEventInsertProvider,
            ObjectProvider<GlobalPostUpdateEntityEventListener> entityEventUpdateProvider,
            ObjectProvider<GlobalPostInsertSyncToMongoListener> mongoInsertProvider,
            ObjectProvider<GlobalPostUpdateSyncToMongoListener> mongoUpdateProvider,
            ObjectProvider<GlobalPostInsertMeilisearchListener> meilisearchInsertProvider,
            ObjectProvider<GlobalPostUpdateMeilisearchListener> meilisearchUpdateProvider
    ) {
        return hibernateProperties -> hibernateProperties.put("hibernate.integrator_provider", (IntegratorProvider) () ->
                Collections.singletonList(new Integrator() {
                    @Override
                    public void integrate(Metadata metadata, BootstrapContext bootstrapContext, SessionFactoryImplementor sessionFactory) {
                        EventListenerRegistry registry = sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class);
                        if (registry == null) {
                            return;
                        }
                        preInsertProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.PRE_INSERT).prependListener(listener));
                        preUpdateProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.PRE_UPDATE).prependListener(listener));
                        revisionInsertProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_INSERT).appendListener(listener));
                        entityEventInsertProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_INSERT).appendListener(listener));
                        mongoInsertProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_INSERT).appendListener(listener));
                        meilisearchInsertProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_INSERT).appendListener(listener));
                        revisionUpdateProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_UPDATE).appendListener(listener));
                        entityEventUpdateProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_UPDATE).appendListener(listener));
                        mongoUpdateProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_UPDATE).appendListener(listener));
                        meilisearchUpdateProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_UPDATE).appendListener(listener));
                    }

                    @Override
                    public void disintegrate(SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {
                    }
                })
        );
    }

    /**
     * Fallback SpringLiquibase bean for consumers who do NOT configure
     * {@code spring.liquibase.change-log}. It runs the starter's own
     * changelog ({@value RamaStarterLiquibaseProperties#DEFAULT_CHANGE_LOG}
     * by default) so starter tables exist out of the box.
     *
     * <p>When a consumer configures Spring Boot's default Liquibase (by
     * setting {@code spring.liquibase.change-log} or by defining their own
     * {@link SpringLiquibase} bean), this bean backs off. The consumer is
     * then responsible for including {@code rama-spring-starter-master.yaml}
     * (and optionally {@code rama-spring-quartz.changelog.xml}) in their
     * master changelog so the starter's tables still get created. See
     * consumer-manual.md § Liquibase.
     *
     * <p>Why {@code @ConditionalOnMissingBean(SpringLiquibase.class)}: Spring
     * Boot's own default is guarded by the same condition, so unconditionally
     * registering a {@link SpringLiquibase} bean would silently displace it
     * and cause the consumer's {@code spring.liquibase.change-log} to be
     * ignored entirely — see starter#13 for the history.
     */
    @Bean
    @ConditionalOnClass(SpringLiquibase.class)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(SpringLiquibase.class)
    @ConditionalOnProperty(prefix = "rama.liquibase", name = "enabled", havingValue = "true", matchIfMissing = true)
    SpringLiquibase ramaStarterLiquibase(DataSource dataSource, RamaStarterLiquibaseProperties properties) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(properties.getChangeLog());
        liquibase.setShouldRun(properties.isEnabled());
        return liquibase;
    }

    @Bean
    @ConditionalOnClass(name = "org.apache.commons.net.ftp.FTPClient")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.ftp", name = "enabled", havingValue = "true")
    org.rama.ftp.FtpConnectionManager ftpConnectionManager(org.rama.ftp.FtpProperties ftpProperties) {
        return new org.rama.ftp.FtpConnectionManager(ftpProperties);
    }

    @Bean
    @ConditionalOnBean(org.rama.ftp.FtpConnectionManager.class)
    @ConditionalOnMissingBean
    org.rama.ftp.FtpService ftpService(org.rama.ftp.FtpConnectionManager ftpConnectionManager, org.rama.ftp.FtpProperties ftpProperties) {
        return new org.rama.ftp.FtpService(ftpConnectionManager, ftpProperties);
    }

    /**
     * Logs a warning at startup when rama.ftp.enabled=true but commons-net is not on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "rama.ftp", name = "enabled", havingValue = "true")
    static class FtpMissingDependencyCheck {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FtpMissingDependencyCheck.class);

        FtpMissingDependencyCheck() {
            try {
                Class.forName("org.apache.commons.net.ftp.FTPClient");
            } catch (ClassNotFoundException e) {
                log.warn("""

                        ┌──────────────────────────────────────────────────────────────┐
                        │  rama.ftp.enabled=true but commons-net is not on the         │
                        │  classpath. FTP support will NOT be activated.                │
                        │                                                              │
                        │  Add to your pom.xml:                                        │
                        │    <dependency>                                               │
                        │        <groupId>commons-net</groupId>                        │
                        │        <artifactId>commons-net</artifactId>                  │
                        │        <version>3.12.0</version>                             │
                        │    </dependency>                                              │
                        └──────────────────────────────────────────────────────────────┘
                        """);
            }
        }
    }

    // ── API Key authentication (disabled by default) ────────────────────

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(org.rama.repository.security.ApiKeyRepository.class)
    @ConditionalOnProperty(prefix = "rama.api-key", name = "enabled", havingValue = "true")
    org.rama.security.ApiKeyService apiKeyService(
            org.rama.repository.security.ApiKeyRepository apiKeyRepository,
            org.springframework.core.env.Environment environment) {
        String appName = environment.getProperty("spring.application.name", "");
        return new org.rama.security.ApiKeyService(apiKeyRepository, appName);
    }

    @Bean
    @ConditionalOnBean(org.rama.security.ApiKeyService.class)
    @ConditionalOnMissingBean
    org.rama.security.ApiKeyAuthFilter apiKeyAuthFilter(org.rama.security.ApiKeyService apiKeyService) {
        return new org.rama.security.ApiKeyAuthFilter(apiKeyService);
    }

}
