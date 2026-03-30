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

## Maven Central Preparation

This repository is now prepared for Maven Central in these areas:

- source JAR generation
- javadoc JAR generation
- GPG signing plugin
- Central publishing plugin
- SCM / developer / organization / license metadata
- GitLab CI tag-driven release job

### Important Central Checklist

Before the first real Central release, confirm these points:

1. the groupId namespace is publishable by your team
2. the repository URL is publicly reachable
3. the selected license is the one your team wants to release under
4. Sonatype Central credentials are created
5. a GPG key is available for signing

### Current CI Tag Rules

The GitLab pipeline is configured so that:

- normal branches run `verify`
- `vX.Y.Z` style tags run release verification and expose the Central publish job

This starter uses only `v...` tags for release flow.

### GitLab CI Variables Required For Central Publish

Set these protected CI variables:

- `CENTRAL_TOKEN_USERNAME`
- `CENTRAL_TOKEN_PASSWORD`
- `MAVEN_GPG_PRIVATE_KEY`
- `MAVEN_GPG_PASSPHRASE`
- `GIT_USER_EMAIL`
- `GIT_USER_NAME`

`MAVEN_GPG_PRIVATE_KEY` should be stored as base64-encoded key material because the CI job imports it with `base64 -d`.

### Central Publish Flow

1. push a release tag like `v1.0.0`
2. run the `tag_verify` job
3. trigger the manual `central_publish` job
4. the job sets the Maven version from the tag, signs artifacts, and runs `mvn deploy`

### Namespace Note

If `org.rama.starter` is not a namespace your team can verify for Central, the code is technically ready but the publish will still block. In that case, move to a domain-backed groupId before the first public release.
