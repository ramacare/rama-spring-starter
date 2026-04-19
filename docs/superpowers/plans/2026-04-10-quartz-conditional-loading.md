# Quartz Conditional Loading & JDBC Migration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make SchedulerController conditionally loaded only when Quartz is configured, add Liquibase migration for QRTZ_* tables, and set sensible default Quartz properties so consumers get JDBC JobStore out of the box.

**Architecture:** Move SchedulerController from component-scanned `@Controller` to a bean registered in `RamaStarterAutoConfiguration` with `@ConditionalOnBean(QuartzService.class)`. Add Quartz JDBC schema as a starter Liquibase migration. Set default `spring.quartz.*` properties via auto-configuration so consumers only need a datasource.

**Tech Stack:** Spring Boot 4.0.3, Quartz, Liquibase, Maven

---

### Task 1: Make SchedulerController conditional on QuartzService

**Files:**
- Modify: `rama-spring-core/src/main/java/org/rama/controller/system/SchedulerController.java`
- Modify: `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java`

The root cause: `SchedulerController` is annotated with `@Controller`, which means it gets picked up by the consumer app's component scan (consumer apps use `@SpringBootApplication` in `org.rama` package). It injects `QuartzService` and `List<Scheduler>` unconditionally — crashing when Quartz isn't configured.

**Fix:** Remove `@Controller` from `SchedulerController` and register it as a `@Bean` in auto-configuration, conditional on `QuartzService`.

- [ ] **Step 1: Remove @Controller from SchedulerController**

In `rama-spring-core/src/main/java/org/rama/controller/system/SchedulerController.java`, remove the `@Controller` annotation and keep `@RequiredArgsConstructor`. The class becomes a plain POJO that auto-configuration will register when appropriate.

```java
// BEFORE:
@Controller
@RequiredArgsConstructor
public class SchedulerController {

// AFTER:
@RequiredArgsConstructor
public class SchedulerController {
```

Also remove the unused `@Controller` import: `import org.springframework.stereotype.Controller;`

- [ ] **Step 2: Register SchedulerController as a conditional bean**

In `RamaStarterAutoConfiguration.java`, add a bean definition after the `quartzService` bean (around line 244):

```java
@Bean
@ConditionalOnMissingBean
@ConditionalOnBean(QuartzService.class)
SchedulerController schedulerController(QuartzService quartzService, org.springframework.core.env.Environment environment, List<Scheduler> schedulers) {
    return new SchedulerController(quartzService, environment, schedulers);
}
```

Add the import at the top:
```java
import org.rama.controller.system.SchedulerController;
```

- [ ] **Step 3: Verify the build compiles**

Run: `mvn compile -pl rama-spring-core,rama-spring-autoconfigure`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add rama-spring-core/src/main/java/org/rama/controller/system/SchedulerController.java rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java
git commit -m "fix: make SchedulerController conditional on QuartzService bean

SchedulerController was component-scanned unconditionally via @Controller,
causing startup failures when Quartz is not configured. Now registered as
a bean in auto-configuration with @ConditionalOnBean(QuartzService.class).

Closes #3"
```

---

### Task 2: Add Quartz JDBC schema Liquibase migration (conditional)

**Files:**
- Create: `rama-spring-autoconfigure/src/main/resources/db/changelog/rama-spring-quartz.changelog.xml`
- Modify: `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java`

Consumers currently need to bring their own QRTZ_* table migration (like ramaservice does in `_quartz.init.xml`). Since the starter ships `spring-boot-starter-quartz` and provides `QuartzService`, it should also provide the schema.

**Important:** Do NOT add this to `rama-spring-starter-master.yaml`. Instead, register a separate `SpringLiquibase` bean conditional on `spring.quartz.enabled` so the migration only runs when Quartz is active.

- [ ] **Step 1: Create the Quartz Liquibase migration**

Create `rama-spring-autoconfigure/src/main/resources/db/changelog/rama-spring-quartz.changelog.xml` with the standard Quartz JDBC schema. Copy the exact content from ramaservice's `_quartz.init.xml` at `/Users/tantee/IdeaProjects/ramaservice/src/main/resources/db/changelog/_quartz.init.xml`. This includes 11 tables (QRTZ_LOCKS, QRTZ_FIRED_TRIGGERS, QRTZ_CALENDARS, QRTZ_PAUSED_TRIGGER_GRPS, QRTZ_SCHEDULER_STATE, QRTZ_JOB_DETAILS, QRTZ_TRIGGERS, QRTZ_BLOB_TRIGGERS, QRTZ_SIMPROP_TRIGGERS, QRTZ_CRON_TRIGGERS, QRTZ_SIMPLE_TRIGGERS), plus foreign keys and indexes. The `preConditions onFail="MARK_RAN"` guard makes it safe for existing databases.

- [ ] **Step 2: Register conditional Liquibase bean in auto-configuration**

In `RamaStarterAutoConfiguration.java`, add a second `SpringLiquibase` bean after the existing `ramaStarterLiquibase` bean (around line 597):

```java
@Bean
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "spring.quartz", name = "enabled", havingValue = "true", matchIfMissing = true)
SpringLiquibase ramaStarterQuartzLiquibase(DataSource dataSource) {
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(dataSource);
    liquibase.setChangeLog("classpath:db/changelog/rama-spring-quartz.changelog.xml");
    return liquibase;
}
```

This means:
- `spring.quartz.enabled=true` (default) → migration runs, QRTZ_* tables created
- `spring.quartz.enabled=false` → migration skipped entirely

- [ ] **Step 3: Verify the build compiles**

Run: `mvn compile -pl rama-spring-autoconfigure`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add rama-spring-autoconfigure/src/main/resources/db/changelog/rama-spring-quartz.changelog.xml rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java
git commit -m "feat: add conditional Quartz JDBC schema Liquibase migration

Adds standard QRTZ_* table definitions as a separate SpringLiquibase bean
conditional on spring.quartz.enabled (default true). Migration is skipped
when Quartz is disabled. Uses preConditions onFail=MARK_RAN for safety
with existing databases."
```

---

### Task 3: Set default Quartz properties via auto-configuration

**Files:**
- Create: `rama-spring-autoconfigure/src/main/resources/rama-quartz-defaults.properties`

Spring Boot starters can provide default properties that consumers can override. We want sensible defaults matching what ramaservice uses so consumers only need a datasource.

- [ ] **Step 1: Create default properties file**

Create `rama-spring-autoconfigure/src/main/resources/rama-quartz-defaults.properties`:

```properties
# Rama starter Quartz defaults — consumers can override any of these
spring.quartz.job-store-type=jdbc
spring.quartz.jdbc.initialize-schema=never
spring.quartz.properties.org.quartz.jobStore.tablePrefix=QRTZ_
spring.quartz.properties.org.quartz.threadPool.threadCount=5
spring.quartz.properties.org.quartz.scheduler.instanceId=AUTO
spring.quartz.properties.org.quartz.jobStore.isClustered=true
spring.quartz.properties.org.quartz.jobStore.lockHandler.class=org.quartz.impl.jdbcjobstore.UpdateLockRowSemaphore
```

Note: We do NOT set `spring.quartz.auto-startup` or `spring.quartz.properties.org.quartz.scheduler.instanceName` — those are app-specific. `auto-startup` defaults to `true` in Spring Boot which is fine.

- [ ] **Step 2: Register defaults in auto-configuration**

In `RamaStarterAutoConfiguration.java`, add `@PropertySource` to load defaults with lowest priority so consumers can override:

```java
@AutoConfiguration(afterName = {
        "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration"
})
@EnableConfigurationProperties({...})
@PropertySource(value = "classpath:rama-quartz-defaults.properties", ignoreResourceNotFound = true)
@org.springframework.scheduling.annotation.EnableAsync
public class RamaStarterAutoConfiguration {
```

Add the import:
```java
import org.springframework.context.annotation.PropertySource;
```

Note: `@PropertySource` has lower precedence than `application.properties`, so consumer values always win.

- [ ] **Step 3: Verify the build compiles**

Run: `mvn compile -pl rama-spring-autoconfigure`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add rama-spring-autoconfigure/src/main/resources/rama-quartz-defaults.properties rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java
git commit -m "feat: add sensible default Quartz properties

Sets jdbc job-store, clustered mode, and QRTZ_ table prefix as defaults.
Consumers can override any property in their application.properties.
Consumers can disable Quartz entirely with spring.quartz.enabled=false."
```

---

### Task 4: Update CLAUDE.md documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update feature flags section in CLAUDE.md**

Add documentation about Quartz configuration:
- Note that `spring.quartz.enabled` (Spring Boot property, default `true`) controls whether Quartz auto-configures
- Note that the starter provides default JDBC JobStore config and Liquibase migration
- Note that consumers can disable with `spring.quartz.enabled=false`

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document Quartz configuration and disable option"
```

---

### Task 5: Full build verification

- [ ] **Step 1: Run full build**

Run: `mvn clean install`
Expected: BUILD SUCCESS

- [ ] **Step 2: Verify SchedulerController is not component-scanned**

Grep to confirm no `@Controller` on SchedulerController:
```bash
grep -n '@Controller' rama-spring-core/src/main/java/org/rama/controller/system/SchedulerController.java
```
Expected: No output (no `@Controller` annotation)

Confirm the bean is registered in auto-configuration:
```bash
grep -n 'SchedulerController' rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java
```
Expected: Shows the `@Bean` method and import
