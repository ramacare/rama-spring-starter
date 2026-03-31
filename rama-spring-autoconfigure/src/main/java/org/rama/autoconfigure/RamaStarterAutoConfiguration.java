package org.rama.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import graphql.scalars.ExtendedScalars;
import graphql.validation.rules.OnValidationErrorStrategy;
import graphql.validation.rules.ValidationRules;
import graphql.validation.schemawiring.ValidationSchemaWiring;
import io.minio.MinioClient;
import liquibase.integration.spring.SpringLiquibase;
import org.bson.types.Decimal128;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.quartz.Scheduler;
import org.rama.entity.Revision;
import org.rama.entity.api.Api;
import org.rama.graphql.directive.EmailConstraint;
import org.rama.listener.global.GlobalAuditablePreInsertListener;
import org.rama.listener.global.GlobalAuditablePreUpdateListener;
import org.rama.listener.global.GlobalPostInsertRevisionListener;
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
import org.rama.repository.system.SystemLogRepository;
import org.rama.repository.system.SystemParameterRepository;
import org.rama.service.*;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@AutoConfiguration(after = {
        org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration.class
})
@EnableConfigurationProperties({RamaStarterProperties.class, RamaStarterLiquibaseProperties.class, AppProperties.class, MinioProperties.class, DocumentProperties.class, MeilisearchProperties.class, EncryptProperties.class})
public class RamaStarterAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.persistence.autoconfigure.EntityScan(basePackageClasses = {Api.class, Revision.class})
    @ConditionalOnProperty(prefix = "rama.jpa", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RamaStarterJpaConfiguration {
    }

    @Bean
    @ConditionalOnMissingBean
    ObjectMapper ramaStarterObjectMapper() {
        return org.rama.entity.JsonConverter.createObjectMapper();
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
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
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
    GenericEntityService genericEntityService(JsonMapper objectMapper) {
        return new GenericEntityService(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    EnvironmentService environmentService(org.springframework.core.env.Environment environment, ObjectProvider<StaticValueResolver> staticValueResolver) {
        return new EnvironmentService(environment, staticValueResolver);
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
    GenericApiService genericApiService(ApiRepository apiRepository, ApiHeaderSetRepository apiHeaderSetRepository, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
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
    @ConditionalOnBean(MasterItemRepository.class)
    MasterItemService masterItemService(MasterItemRepository masterItemRepository) {
        return new MasterItemService(masterItemRepository);
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
    SystemLogService systemLogService(SystemLogRepository systemLogRepository, ObjectMapper objectMapper) {
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
    @ConditionalOnBean(Scheduler.class)
    QuartzService quartzService(Scheduler scheduler, BeanFactory beanFactory) {
        List<String> basePackages = AutoConfigurationPackages.has(beanFactory)
                ? AutoConfigurationPackages.get(beanFactory)
                : Collections.emptyList();
        return new QuartzService(scheduler, basePackages);
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
    @ConditionalOnBean(VaultTemplate.class)
    @ConditionalOnMissingBean
    VaultService vaultService(VaultTemplate vaultTemplate) {
        return new VaultService(vaultTemplate);
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
    GlobalAuditablePreInsertListener globalAuditablePreInsertListener(EnvironmentService environmentService) {
        return new GlobalAuditablePreInsertListener(environmentService);
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalAuditablePreUpdateListener globalAuditablePreUpdateListener(EnvironmentService environmentService) {
        return new GlobalAuditablePreUpdateListener(environmentService);
    }

    @Bean
    @ConditionalOnClass(MongoTemplate.class)
    @ConditionalOnMissingBean
    MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(Arrays.asList(
                new OffsetDateTimeReadConverter(),
                new OffsetDateTimeWriteConverter(),
                new BigIntegerToDecimal128Converter(),
                new Decimal128ToBigIntegerConverter()
        ));
    }

    @Bean
    @ConditionalOnClass(MongoTemplate.class)
    @ConditionalOnBean(MongoTemplate.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    DeferredIndexManager deferredIndexManager(MongoTemplate mongoTemplate) {
        return new DeferredIndexManager(mongoTemplate);
    }

    @Bean
    @ConditionalOnBean({MongoTemplate.class, DeferredIndexManager.class})
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.mongo", name = "deferred-indexes-enabled", havingValue = "true", matchIfMissing = true)
    IndexAwareMongoTemplate ramaStarterIndexAwareMongoTemplate(MongoTemplate mongoTemplate, DeferredIndexManager deferredIndexManager) {
        return new IndexAwareMongoTemplate(mongoTemplate.getMongoDatabaseFactory(), mongoTemplate.getConverter(), deferredIndexManager);
    }

    @Bean
    @ConditionalOnBean(MongoTemplate.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    MongoSyncService mongoSyncService(ApplicationContext applicationContext, MongoTemplate mongoTemplate) {
        return new MongoSyncService(applicationContext, mongoTemplate);
    }

    @Bean
    @ConditionalOnBean(MongoTemplate.class)
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
    DefaultMeilisearchMapper defaultMeilisearchMapper(ObjectMapper objectMapper) {
        return new DefaultMeilisearchMapper(objectMapper);
    }

    @Bean
    @ConditionalOnBean(Client.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.meilisearch", name = "enabled", havingValue = "true", matchIfMissing = true)
    MeilisearchService meilisearchService(ApplicationContext applicationContext, Client client, ObjectMapper objectMapper, MeilisearchErrorHandler errorHandler) {
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
    @ConditionalOnMissingBean(name = "ramaStarterHibernatePropertiesCustomizer")
    HibernatePropertiesCustomizer ramaStarterHibernatePropertiesCustomizer(
            ObjectProvider<GlobalAuditablePreInsertListener> preInsertProvider,
            ObjectProvider<GlobalAuditablePreUpdateListener> preUpdateProvider,
            ObjectProvider<GlobalPostInsertRevisionListener> revisionInsertProvider,
            ObjectProvider<GlobalPostUpdateRevisionListener> revisionUpdateProvider,
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
                        mongoInsertProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_INSERT).appendListener(listener));
                        meilisearchInsertProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_INSERT).appendListener(listener));
                        revisionUpdateProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_UPDATE).appendListener(listener));
                        mongoUpdateProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_UPDATE).appendListener(listener));
                        meilisearchUpdateProvider.ifAvailable(listener -> registry.getEventListenerGroup(EventType.POST_UPDATE).appendListener(listener));
                    }

                    @Override
                    public void disintegrate(SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {
                    }
                })
        );
    }

    @Bean
    @ConditionalOnClass(SpringLiquibase.class)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "rama.liquibase", name = "enabled", havingValue = "true", matchIfMissing = true)
    SpringLiquibase ramaStarterLiquibase(DataSource dataSource, RamaStarterLiquibaseProperties properties) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(properties.getChangeLog());
        liquibase.setShouldRun(properties.isEnabled());
        return liquibase;
    }

    static class OffsetDateTimeWriteConverter implements Converter<OffsetDateTime, Date> {
        @Override
        public Date convert(OffsetDateTime source) {
            return Date.from(source.toInstant().atZone(ZoneOffset.UTC).toInstant());
        }
    }

    static class OffsetDateTimeReadConverter implements Converter<Date, OffsetDateTime> {
        @Override
        public OffsetDateTime convert(Date source) {
            return source.toInstant().atOffset(ZoneOffset.UTC);
        }
    }

    static class BigIntegerToDecimal128Converter implements Converter<BigInteger, Decimal128> {
        @Override
        public Decimal128 convert(BigInteger source) {
            return new Decimal128(new BigDecimal(source));
        }
    }

    static class Decimal128ToBigIntegerConverter implements Converter<Decimal128, BigInteger> {
        @Override
        public BigInteger convert(Decimal128 source) {
            return source.bigDecimalValue().toBigInteger();
        }
    }
}
