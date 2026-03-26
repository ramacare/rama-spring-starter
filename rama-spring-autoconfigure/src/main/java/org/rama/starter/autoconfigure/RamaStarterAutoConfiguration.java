package org.rama.starter.autoconfigure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import org.rama.starter.crypto.NoOpTextEncryptor;
import org.rama.starter.crypto.TextEncryptor;
import org.rama.starter.entity.Encrypt;
import org.rama.starter.entity.JsonEncryptConverter;
import org.rama.starter.entity.Revision;
import org.rama.starter.entity.api.Api;
import org.rama.starter.graphql.directive.EmailConstraint;
import org.rama.starter.listener.global.GlobalAuditablePreInsertListener;
import org.rama.starter.listener.global.GlobalAuditablePreUpdateListener;
import org.rama.starter.listener.global.GlobalPostInsertRevisionListener;
import org.rama.starter.listener.global.GlobalPostUpdateRevisionListener;
import org.rama.starter.meilisearch.MeilisearchIndexInitializer;
import org.rama.starter.meilisearch.listener.GlobalPostInsertMeilisearchListener;
import org.rama.starter.meilisearch.listener.GlobalPostUpdateMeilisearchListener;
import org.rama.starter.meilisearch.mapper.DefaultMeilisearchMapper;
import org.rama.starter.meilisearch.service.LoggingMeilisearchErrorHandler;
import org.rama.starter.meilisearch.service.MeilisearchErrorHandler;
import org.rama.starter.meilisearch.service.MeilisearchService;
import org.rama.starter.mongo.IndexAwareMongoTemplate;
import org.rama.starter.mongo.indexing.DeferredIndexManager;
import org.rama.starter.mongo.listener.GlobalPostInsertSyncToMongoListener;
import org.rama.starter.mongo.listener.GlobalPostUpdateSyncToMongoListener;
import org.rama.starter.mongo.service.MongoSyncService;
import org.rama.starter.repository.BaseRepositoryImpl;
import org.rama.starter.repository.RevisionRepository;
import org.rama.starter.repository.api.ApiHeaderSetRepository;
import org.rama.starter.repository.api.ApiRepository;
import org.rama.starter.repository.asset.AssetFileRepository;
import org.rama.starter.repository.master.MasterIdRepository;
import org.rama.starter.repository.master.MasterItemRepository;
import org.rama.starter.repository.system.ClientConfigRepository;
import org.rama.starter.repository.system.SystemLogRepository;
import org.rama.starter.repository.system.SystemParameterRepository;
import org.rama.starter.service.GenericApiFormUrlService;
import org.rama.starter.service.GenericApiService;
import org.rama.starter.service.GenericEntityService;
import org.rama.starter.service.GenericMongoService;
import org.rama.starter.service.RevisionService;
import org.rama.starter.service.StorageService;
import org.rama.starter.service.VaultService;
import org.rama.starter.service.environment.EnvironmentService;
import org.rama.starter.service.environment.StaticValueResolver;
import org.rama.starter.service.environment.StaticValueService;
import org.rama.starter.service.master.MasterIdService;
import org.rama.starter.service.master.MasterItemService;
import org.rama.starter.service.system.ClientConfigService;
import org.rama.starter.service.system.SystemLogService;
import org.rama.starter.service.system.SystemParameterService;
import org.rama.starter.service.template.BarcodeService;
import org.rama.starter.service.template.DocxTemplateProcessor;
import org.rama.starter.service.template.ImageService;
import org.rama.starter.service.template.PdfService;
import org.rama.starter.service.template.ReplacementHooks;
import org.rama.starter.service.template.ReplacementObjectHook;
import org.rama.starter.service.template.ReplacementProcessor;
import org.rama.starter.service.template.ReplacementStringHook;
import org.rama.starter.service.template.TemplatePreprocessor;
import org.rama.starter.service.template.hooks.CheckBoxHooks;
import org.rama.starter.service.template.hooks.DatetimeHooks;
import org.rama.starter.service.template.hooks.GeneralHooks;
import org.rama.starter.service.template.hooks.JoinHooks;
import org.rama.starter.service.template.hooks.MasterHooks;
import org.rama.starter.service.template.hooks.StringArrayHooks;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.vault.core.VaultTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

@AutoConfiguration
@EntityScan(basePackageClasses = {Api.class, Revision.class})
@EnableJpaRepositories(basePackageClasses = ApiRepository.class, repositoryBaseClass = BaseRepositoryImpl.class)
@EnableConfigurationProperties({RamaStarterProperties.class, RamaStarterLiquibaseProperties.class})
public class RamaStarterAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ObjectMapper ramaStarterObjectMapper() {
        return new ObjectMapper()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    @ConditionalOnMissingBean
    TextEncryptor ramaStarterTextEncryptor() {
        return new NoOpTextEncryptor();
    }

    @Bean
    @ConditionalOnMissingBean(name = "ramaStarterJsonEncryptConverterInitializer")
    Object ramaStarterJsonEncryptConverterInitializer(TextEncryptor textEncryptor) {
        JsonEncryptConverter.setTextEncryptor(textEncryptor);
        Encrypt.setTextEncryptor(textEncryptor);
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
    @ConditionalOnProperty(prefix = "rama.starter.storage", name = "minio-endpoint")
    MinioClient ramaStarterMinioClient(RamaStarterProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getStorage().getMinioEndpoint())
                .credentials(properties.getStorage().getMinioAccessKey(), properties.getStorage().getMinioSecretKey())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    StorageService storageService(RamaStarterProperties properties, AssetFileRepository assetFileRepository, ObjectProvider<MinioClient> minioClientProvider) {
        return new StorageService(properties.getStorage().getFileStoragePath(), properties.getStorage().getFileStorageLocation(), assetFileRepository, minioClientProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    GenericEntityService genericEntityService(ObjectMapper objectMapper) {
        return new GenericEntityService(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    EnvironmentService environmentService(org.springframework.core.env.Environment environment, ObjectProvider<StaticValueResolver> staticValueResolver) {
        return new EnvironmentService(environment, staticValueResolver.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(StaticValueResolver.class)
    @ConditionalOnProperty(prefix = "rama.starter.static-values", name = "enabled", havingValue = "true", matchIfMissing = true)
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
    GenericApiService genericApiService(ApiRepository apiRepository, ApiHeaderSetRepository apiHeaderSetRepository, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        return new GenericApiService(apiRepository, apiHeaderSetRepository, webClientBuilder, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    GenericApiFormUrlService genericApiFormUrlService(ApiRepository apiRepository, ApiHeaderSetRepository apiHeaderSetRepository, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        return new GenericApiFormUrlService(apiRepository, apiHeaderSetRepository, webClientBuilder, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    MasterIdService masterIdService(MasterIdRepository masterIdRepository) {
        return new MasterIdService(masterIdRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    MasterItemService masterItemService(MasterItemRepository masterItemRepository) {
        return new MasterItemService(masterItemRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.starter.revision", name = "enabled", havingValue = "true", matchIfMissing = true)
    RevisionService revisionService(RevisionRepository revisionRepository) {
        return new RevisionService(revisionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    SystemParameterService systemParameterService(SystemParameterRepository systemParameterRepository) {
        return new SystemParameterService(systemParameterRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    SystemLogService systemLogService(SystemLogRepository systemLogRepository, ObjectMapper objectMapper) {
        return new SystemLogService(systemLogRepository, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    ClientConfigService clientConfigService(ClientConfigRepository clientConfigRepository, SystemLogRepository systemLogRepository) {
        return new ClientConfigService(clientConfigRepository, systemLogRepository);
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
    PdfService pdfService(WebClient.Builder webClientBuilder, RamaStarterProperties properties) {
        return new PdfService(webClientBuilder, properties.getDocument().getGotenbergServer());
    }

    @Bean
    @ConditionalOnMissingBean
    ReplacementHooks replacementHooks(Map<String, ReplacementObjectHook> objectHooks, Map<String, ReplacementStringHook> stringHooks) {
        return new ReplacementHooks(objectHooks, stringHooks);
    }

    @Bean
    @ConditionalOnMissingBean
    ReplacementProcessor replacementProcessor(ReplacementHooks replacementHooks, StorageService storageService) {
        return new ReplacementProcessor(replacementHooks, storageService);
    }

    @Bean
    @ConditionalOnMissingBean
    DocxTemplateProcessor docxTemplateProcessor(RamaStarterProperties properties, PdfService pdfService, ReplacementProcessor replacementProcessor) {
        return new DocxTemplateProcessor(properties.getDocument().getPlaceholderPattern(), pdfService, replacementProcessor);
    }

    @Bean
    @ConditionalOnMissingBean
    TemplatePreprocessor templatePreprocessor(StorageService storageService) {
        return new TemplatePreprocessor(storageService);
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
    @ConditionalOnProperty(prefix = "rama.starter.revision", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostInsertRevisionListener globalPostInsertRevisionListener(RevisionService revisionService) {
        return new GlobalPostInsertRevisionListener(revisionService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.starter.revision", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostUpdateRevisionListener globalPostUpdateRevisionListener(RevisionService revisionService) {
        return new GlobalPostUpdateRevisionListener(revisionService);
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
    @ConditionalOnProperty(prefix = "rama.starter.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    DeferredIndexManager deferredIndexManager(MongoTemplate mongoTemplate) {
        return new DeferredIndexManager(mongoTemplate);
    }

    @Bean
    @ConditionalOnBean({MongoTemplate.class, DeferredIndexManager.class})
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.starter.mongo", name = "deferred-indexes-enabled", havingValue = "true", matchIfMissing = true)
    IndexAwareMongoTemplate ramaStarterIndexAwareMongoTemplate(MongoTemplate mongoTemplate, DeferredIndexManager deferredIndexManager) {
        return new IndexAwareMongoTemplate(mongoTemplate.getMongoDatabaseFactory(), mongoTemplate.getConverter(), deferredIndexManager);
    }

    @Bean
    @ConditionalOnBean(MongoTemplate.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.starter.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    MongoSyncService mongoSyncService(ApplicationContext applicationContext, MongoTemplate mongoTemplate) {
        return new MongoSyncService(applicationContext, mongoTemplate);
    }

    @Bean
    @ConditionalOnBean(IndexAwareMongoTemplate.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.starter.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    GenericMongoService genericMongoService(IndexAwareMongoTemplate mongoTemplate) {
        return new GenericMongoService(mongoTemplate);
    }

    @Bean
    @ConditionalOnBean(MongoSyncService.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.starter.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostInsertSyncToMongoListener globalPostInsertSyncToMongoListener(MongoSyncService mongoSyncService) {
        return new GlobalPostInsertSyncToMongoListener(mongoSyncService);
    }

    @Bean
    @ConditionalOnBean(MongoSyncService.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.starter.mongo", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostUpdateSyncToMongoListener globalPostUpdateSyncToMongoListener(MongoSyncService mongoSyncService) {
        return new GlobalPostUpdateSyncToMongoListener(mongoSyncService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.starter.meilisearch", name = "host-url")
    Client ramaStarterMeilisearchClient(RamaStarterProperties properties) {
        return new Client(new Config(properties.getMeilisearch().getHostUrl(), properties.getMeilisearch().getApiKey()));
    }

    @Bean
    @ConditionalOnMissingBean
    MeilisearchErrorHandler meilisearchErrorHandler() {
        return new LoggingMeilisearchErrorHandler();
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
    @ConditionalOnProperty(prefix = "rama.starter.meilisearch", name = "enabled", havingValue = "true", matchIfMissing = true)
    MeilisearchService meilisearchService(ApplicationContext applicationContext, Client client, ObjectMapper objectMapper, MeilisearchErrorHandler errorHandler) {
        return new MeilisearchService(applicationContext, client, objectMapper, errorHandler);
    }

    @Bean
    @ConditionalOnBean(MeilisearchService.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.starter.meilisearch", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostInsertMeilisearchListener globalPostInsertMeilisearchListener(MeilisearchService meilisearchService) {
        return new GlobalPostInsertMeilisearchListener(meilisearchService);
    }

    @Bean
    @ConditionalOnBean(MeilisearchService.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.starter.meilisearch", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalPostUpdateMeilisearchListener globalPostUpdateMeilisearchListener(MeilisearchService meilisearchService) {
        return new GlobalPostUpdateMeilisearchListener(meilisearchService);
    }

    @Bean
    @ConditionalOnBean(MeilisearchService.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rama.starter.meilisearch", name = "initialize-indexes", havingValue = "true", matchIfMissing = true)
    MeilisearchIndexInitializer meilisearchIndexInitializer(Client client, MeilisearchService meilisearchService, BeanFactory beanFactory) {
        List<String> basePackages = AutoConfigurationPackages.has(beanFactory)
                ? AutoConfigurationPackages.get(beanFactory)
                : Collections.emptyList();
        return new MeilisearchIndexInitializer(client, meilisearchService, basePackages);
    }

    @Bean
    @ConditionalOnClass(RuntimeWiringConfigurer.class)
    @ConditionalOnMissingBean(name = "ramaStarterRuntimeWiringConfigurer")
    @ConditionalOnProperty(prefix = "rama.starter.graphql", name = "enabled", havingValue = "true", matchIfMissing = true)
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
    @ConditionalOnProperty(prefix = "rama.starter.liquibase", name = "enabled", havingValue = "true", matchIfMissing = true)
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
