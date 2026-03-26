# Publishing Guide

## Local Development

To publish only to your local Maven cache:

```bash
cd /Users/tantee/IdeaProjects/rama-spring-starter
mvn -DskipTests clean install
```

This is enough for local consumer services on the same machine.

## Internal Repository Publishing

Use an internal Maven repository such as:

- Nexus
- Artifactory
- GitHub Packages

## 1. Add `distributionManagement`

Add this to the parent `pom.xml`:

```xml
<distributionManagement>
    <repository>
        <id>internal-releases</id>
        <url>https://your-repo/repository/maven-releases/</url>
    </repository>
    <snapshotRepository>
        <id>internal-snapshots</id>
        <url>https://your-repo/repository/maven-snapshots/</url>
    </snapshotRepository>
</distributionManagement>
```

## 2. Configure Maven Credentials

In `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>internal-releases</id>
        <username>your-user</username>
        <password>your-password</password>
    </server>
    <server>
        <id>internal-snapshots</id>
        <username>your-user</username>
        <password>your-password</password>
    </server>
</servers>
```

The `id` values must match the `distributionManagement` entries.

## 3. Verify Before Deploy

```bash
cd /Users/tantee/IdeaProjects/rama-spring-starter
mvn -DskipTests verify
```

## 4. Deploy

```bash
cd /Users/tantee/IdeaProjects/rama-spring-starter
mvn -DskipTests deploy
```

## Versioning Guidance

- use `-SNAPSHOT` while iterating internally
- publish a fixed version for stable shared releases
- keep consumer services aligned on a known tested version

## Recommended Internal Process

1. update starter code
2. run `mvn -DskipTests verify`
3. publish a version
4. update consumer services to that version
5. verify consumer startup and Liquibase execution
