# Publishing Guide

## Repository

Published to GitHub Pages as a public Maven repository at:

```
https://ramacare.github.io/rama-spring-starter
```

Source code is managed in GitLab and mirrored to GitHub via GitLab's built-in push mirror.

## Release Flow

```
GitLab (source) --> GitHub (mirror) --> GitHub Actions (publish) --> GitHub Pages (Maven repo)
```

1. Develop and test on GitLab
2. Tag a release: `git tag v4.0.1 && git push origin v4.0.1`
3. GitLab mirrors the tag to GitHub
4. GitHub Actions workflow triggers on `v*` tags
5. Workflow builds, sets version from tag, deploys to GitHub Pages
6. Artifacts available at `https://ramacare.github.io/rama-spring-starter/org/rama/...`

## Manual Trigger

Go to GitHub repo > Actions > "Publish to GitHub Pages Maven Repository" > Run workflow > enter version (e.g. `4.0.1`).

## Local Development

Install to local Maven cache:

```bash
mvn -DskipTests clean install
```

## GitLab CI

The GitLab CI pipeline runs:
- **build** stage: `mvn verify` (on all branches except tags)
- **test** stage: `mvn test` with JUnit report artifacts

Publishing is handled entirely by GitHub Actions, not GitLab CI.

## Consumer Setup

No authentication needed (public GitHub Pages).

```xml
<repositories>
    <repository>
        <id>github-pages</id>
        <url>https://ramacare.github.io/rama-spring-starter</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>org.rama</groupId>
        <artifactId>rama-spring-boot-starter</artifactId>
        <version>4.0.1</version>
    </dependency>
</dependencies>
```

## Versioning

- Use semantic versioning: `MAJOR.MINOR.PATCH`
- Tags must use `v` prefix: `v4.0.0`, `v4.0.1`
- The workflow strips the `v` prefix for the Maven version
- Keep consumer services aligned on a known tested version
