# shiro-extension

[English](./README.md) | [简体中文](./README.zh-CN.md)

[![Java](https://img.shields.io/badge/Java-8-orange)](https://github.com/easy-4-java/shiro-extension) [![License](https://img.shields.io/badge/license-Apache%202.0-green)](./LICENSE)

Business-oriented extensions for Apache Shiro. `shiro-extension` separates the Spring-independent foundation from Spring integration through `shiro-extension-core` and `shiro-extension-spring`.

## Table of Contents

- [1. Project Overview](#1-project-overview)
- [2. Features & Status](#2-features--status)
- [3. Requirements & Compatibility](#3-requirements--compatibility)
- [4. Architecture & Modules](#4-architecture--modules)
- [5. Installation](#5-installation)
- [6. Quick Start](#6-quick-start)
- [7. Configuration](#7-configuration)
- [8. Core Usage / API](#8-core-usage--api)
- [9. Testing & Build](#9-testing--build)
- [10. Versioning & Branches](#10-versioning--branches)
- [11. Contributing & License](#11-contributing--license)

## 1. Project Overview

**What it is**

`shiro-extension` is the common extension layer for Apache Shiro. The core module provides Spring-independent authentication, authorization, caching, session, servlet and utility support; the Spring module provides handlers, filters, message sources, scheduling and Shiro-Spring integration.

**What it is not**

- Spring Boot applications can use the extension library through the Spring module.
- It does not bundle Shiro itself as a shaded jar; `org.apache.shiro:shiro-spring` is a regular dependency.

**Typical scenarios**

| Scenario | Description |
| :--- | :--- |
| Form / REST login flows | Use `TrustableFormAuthenticatingFilter` / `TrustableRestAuthenticatingFilter` with `DefaultAuthenticationToken` (captcha, remember-me, host). |
| Login failure handling | `DefaultAuthenticationFailureHandler` + `AuthcResponse` map exceptions to a uniform response model; retry limits via `CredentialsRetryLimitCredentialsMatcher`. |
| Authorization model | `ShiroPrincipalRepository` / `ShiroPrincipal` with `BitPermission` / wildcard permissions and `@RolesAllowed` AOP. |
| Caching | Swap Shiro `CacheManager` implementations: `CaffeineCacheManager`, `GuavaCacheManager`, `SpringCacheManager`, `SessionCacheManager`. |
| Web hardening | `HttpServletShiroFilter`, header/referrer/method/limit filters, `HttpServletSessionDequeFilter` (online user control). |

## 2. Features & Status

| Capability | Status | Notes |
| :--- | :--- | :--- |
| Authentication tokens (`authc.token`) | Available | `DefaultAuthenticationToken` (captcha, strength), `CaptchaAuthenticationToken`, `LoginTypeAuthenticationToken`, `PwdStrengthAuthenticationToken`, `UsernameWithoutPwdToken`, `LoginProtocolAuthenticationToken`, `LoginType`. |
| Authentication handlers (`authc`) | Available | `AuthenticationSuccessHandler` / `AuthenticationFailureHandler` + defaults, `AuthcResponse` / `AuthcResponseCode`, `AuthenticationListenerAdapter`. |
| Credential matchers (`authc.credential`) | Available | `DefaultCredentialsMatcher`, `CredentialsRetryLimitCredentialsMatcher`. |
| Modular realm auth (`authc.pam`) | Available | `DefaultModularRealmAuthenticator`, `AtLeastTwoAuthenticatorStrategy`, `OnlyOneAuthenticatorStrategy`. |
| Business exceptions (`authc.exception`) | Available | Captcha/ticket/token/secret/session/terminal related exceptions. |
| Authorization annotations (`authz.annotation` / `authz.aop`) | Available | `@RolesAllowed` + `RolesAllowedAnnotationHandler` + `RoleAllowsAnnotationMethodInterceptor`. |
| Permission model (`authz.permission`, `authz.principal`) | Available | `BitPermission`, `BitAndWildPermissionResolver`, `DefaultRolePermissionResolver`, `ShiroPrincipal`, `ShiroRole`, `ShiroPermission`, `ShiroPrincipalRepository` (+`Impl`). |
| Cache managers (`cache`) | Available | Caffeine, Guava, Spring, HTTP-session based managers and wrappers. |
| Realm base (`realm`) | Available | `AbstractAuthorizingRealm` with `ShiroPrincipalRepository` and realm listeners. |
| Session support (`session`) | Available | `SimpleOnlineSession` (+ factory), `SequenceSessionIdGenerator`, `SpringSessionValidationScheduler`, `DefaultSessionListener`. |
| Web filters (`web.filter`) | Available | Header/referrer/method/limit/escape/session-deque/session-status filters, `HttpServletShiroFilter`. |
| Spring integration (`spring`) | Available | `ShiroFilterProxyFactoryBean`, annotation interceptors/advisor. |
| i18n messages | Available | `messages.properties` (+ `en_US`, `zh_CN`), `ShiroBizMessageSource`. |

> Status is reported as of `1.0.x.20260630-SNAPSHOT` on the `feature/1.0.x` branch.

## 3. Requirements & Compatibility

| Item | Version |
| :--- | :--- |
| JDK | 8+ |
| Maven | 3.0+ (Maven Wrapper 3.5.0 bundled) |
| Apache Shiro | 1.13.0 (`shiro-web`, `shiro-spring`) |
| Spring Framework | 5.3.36 (`spring-webmvc`) |
| easy4j dependency | `io.github.easy4j:jwt-issuer-api` |
| JSON / serialization | fastjson2 2.0.52, jackson-databind 2.17.2, flexjson, xstream |
| Other | caffeine 2.9.3, guava 33.2.1-jre, commons-lang3 / commons-text / commons-io, javax.servlet-api 4.0.1 |

**Version lines**

| Branch | JDK baseline | Version pattern |
| :--- | :--- | :--- |
| `feature/1.0.x` | JDK 8 | `1.0.x.*` |
| `feature/2.0.x` | JDK 17 | `2.0.x.*` |
| `feature/3.0.x` | JDK 21 | `3.0.x.*` |

## 4. Architecture & Modules

```text
 HTTP request
      |
      v
 HttpServletShiroFilter / filter chain (web.filter)
      |-- header / referrer / method / limit / escape filters
      |-- authc: TrustableForm/RestAuthenticatingFilter (captcha, retry)
      |-- authz: Roles / AnyRoles / Permissions authorization filters
      v
 DefaultModularRealmAuthenticator (pam strategies)
      |
      v
 AbstractAuthorizingRealm <-- ShiroPrincipalRepository
      |                          (ShiroPrincipal / Role / Permission)
      +-- cache: Caffeine / Guava / Spring / Session managers
      +-- session: SimpleOnlineSession + session DAO / validation
      +-- authz: @RolesAllowed AOP + BitPermission resolver
      v
 Subject (authentication result) --> handlers --> AuthcResponse (JSON)
```

The Maven parent builds the following modules.

| Module | Role |
| :--- | :--- |
| `shiro-extension-core` | Spring-independent Shiro authentication, authorization, cache, session, servlet and utility extensions |
| `shiro-extension-spring` | Spring handlers, filters, message source, scheduling and Shiro-Spring integration; depends on core |
| `shiro-extension-hazelcast3` | Optional Hazelcast 3.12 IMap credential retry adapter |

| Package | Role |
| :--- | :--- |
| `authc` / `authc.token` / `authc.credential` / `authc.pam` / `authc.exception` | Authentication tokens, handlers, matchers, strategies and exceptions |
| `authz` / `authz.annotation` / `authz.aop` / `authz.permission` / `authz.principal` | Authorization handlers, `@RolesAllowed`, permission resolvers, principal model |
| `cache` (`caffeine`, `guava`, `http`, `spring`) | Pluggable `CacheManager` implementations |
| `realm` | `AbstractAuthorizingRealm` and realm listeners |
| `session` / `session.mgt` | Online session model, id generator, validation scheduler |
| `spring` / `spring.security.interceptor` | Spring integration: filter proxy factory bean, annotation interceptors |
| `web` (`filter`, `filter.authc`, `filter.authz`, `servlet`, `mgt`, `env`) | Servlet filters, servlets, subject factory, INI web environment |
| `utils` | `HmacSHA256Utils`, `PasswordEncryptUtils`, `IDWorker`, `SubjectUtils`, `SerializeUtils`, `WebUtils2`, `WebThreadContext`, UID `Sequence` |

## 5. Installation

The artifacts are published to the configured Aliyun Packages repository. Use core alone for Spring-independent integration, or use the Spring module, which transitively includes core.

**Maven**

```xml
<dependency>
    <groupId>io.github.easy4j</groupId>
    <artifactId>shiro-extension-spring</artifactId>
    <version>1.0.x.20260630-SNAPSHOT</version>
</dependency>
```

**Gradle**

```groovy
implementation 'io.github.easy4j:shiro-extension-spring:1.0.x.20260630-SNAPSHOT'
```

## 6. Quick Start

A minimal realm backed by `ShiroPrincipalRepository`:

```java
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authz.principal.ShiroPrincipalRepository;
import org.apache.shiro.authz.principal.ShiroPrincipalRepositoryImpl;
import org.apache.shiro.biz.authc.credential.DefaultCredentialsMatcher;
import org.apache.shiro.biz.realm.AbstractAuthorizingRealm;

ShiroPrincipalRepository repository = new ShiroPrincipalRepositoryImpl() {
    @Override
    public AuthenticationInfo getAuthenticationInfo(AuthenticationToken token)
            throws AuthenticationException {
        // load the user from your business system by token principal
        // and return e.g. a SimpleAuthenticationInfo(principal, hash, salt, realmName)
        return /* your AuthenticationInfo */ null;
    }

    @Override
    public Set<String> getRoles(Object principal) {
        return /* roles of the principal */ Collections.emptySet();
    }

    @Override
    public Set<String> getPermissions(Object principal) {
        return /* permissions of the principal */ Collections.emptySet();
    }
};

AbstractAuthorizingRealm realm = new AbstractAuthorizingRealm() {};
realm.setRepository(repository);
realm.setCredentialsMatcher(new DefaultCredentialsMatcher());
// realm.setCacheManager(new CaffeineCacheManager()); // optional caching
```

**Expected result:** `realm` performs authentication through `ShiroPrincipalRepository.getAuthenticationInfo(token)` and authorization through `getRoles` / `getPermissions`; registered with a `SecurityManager`, it supports `subject.login(token)` and permission checks.

## 7. Configuration

Configure behavior through constructors and beans.

| Extension point | Configurable via |
| :--- | :--- |
| `AbstractAuthorizingRealm` | `setRepository(ShiroPrincipalRepository)`, `setRealmsListeners(List)`, `setCredentialsMatcher(...)` |
| `CredentialsRetryLimitCredentialsMatcher` | retry limit and caching settings (backed by a `CacheManager`) |
| Realm cache | `setCacheManager(CaffeineCacheManager / GuavaCacheManager / SpringCacheManager / SessionCacheManager)` |
| Filters (`HttpServletShiroFilter` and friends) | bean properties and the Shiro filter-chain definition |
| i18n messages | bundled `org/apache/shiro/biz/messages*.properties`, `ShiroBizMessageSource` |

### Shared credential retry state in Spring Boot

For a Spring-managed retry matcher with cache name `login`, declare a provider-backed `CredentialRetryStore` bean named `shiro.credential-retry.login`. Spring Boot binds it to `DefaultCredentialsMatcher` and `CredentialsRetryLimitCredentialsMatcher`, including instances held by Spring-managed realms. An explicitly assigned store takes precedence. Matchers without a matching bean keep their Shiro cache configuration. Use the same provider, retry namespace and principal key mapping across the instances that share the window.

For an existing Ehcache 2 retry cache, stop all legacy clients of that region before enabling the shared store. Reuse the same region and principal keys. The first reservation upgrades the stored legacy counter under the key lock, counts the current attempt and keeps the original TTL deadline and the configured time-to-idle policy. Use a strongly consistent region when clustered.

For Hazelcast 5.2+ and 3.12, use the same cutover steps for the dedicated retry IMap. Deploy the retry-store module to every member and reuse the original map and principal keys. The first reservation converts the legacy counter atomically, retaining its remaining TTL and refreshing idle time under the existing policy. For the 3.12 adapter, supply the map's configured max idle explicitly.

For a JCache retry region that already accepts both value types (for example, with value type `Object`), stop the legacy clients and reuse the region and principal keys. The JCache entry processor converts the saved counter and reserves the current attempt in one update. The region must use store-by-value and retain its creation deadline on access and update. Regions configured to accept only `AtomicInteger` require a separate cutover because they cannot store the new window format.

For Spring Data Redis, pause all old and new retry clients during cutover. Call the adapter’s `migrateLegacyWindow` for each known string principal with the original cache and the distinct destination namespace used by the new store. The import copies the saved count and absolute Redis expiry using the source cache’s key and value serializers. It leaves source entries intact and skips missing, expired, and occupied destinations. Reconcile the submitted principals before resuming clients and finish all imports during the same cutover. If the new policy uses time-to-idle, it applies on the next reservation.

## 8. Core Usage / API

Selected public API (all classes under `org.apache.shiro.biz`):

| Class | Role |
| :--- | :--- |
| `authc.token.DefaultAuthenticationToken` | `UsernamePasswordToken` subclass with captcha and password-strength support. |
| `authc.pam.DefaultModularRealmAuthenticator` | Modular realm authenticator with business strategies (`AtLeastTwoAuthenticatorStrategy`, `OnlyOneAuthenticatorStrategy`). |
| `authc.credential.CredentialsRetryLimitCredentialsMatcher` | Credentials matcher that locks after exceeding a retry limit. |
| `authz.principal.ShiroPrincipal` | Serializable principal model (userid, userkey, usercode, username, password, salt, secret, ...). |
| `authz.principal.ShiroPrincipalRepository` (+`Impl`) | Principal lookup contract used by realms. |
| `authz.permission.BitPermission` / `BitAndWildPermissionResolver` | Bit + wildcard permission model. |
| `cache.caffeine.CaffeineCacheManager` / `cache.guava.GuavaCacheManager` / `cache.spring.SpringCacheManager` | `CacheManager` implementations for Shiro. |
| `session.mgt.eis.SequenceSessionIdGenerator` | Session id generator (sequence based). |
| `spring.ShiorFilterProxyFactoryBean`* | Spring factory bean for the Shiro filter proxy. |
| `web.filter.HttpServletSessionDequeFilter` | Online-user session control (kick-out support). |
| `utils.HmacSHA256Utils` / `utils.PasswordEncryptUtils` | Hashing / encryption helpers. |

\* Class name as declared in source: `ShiroFilterProxyFactoryBean` (extends `ShiroFilterFactoryBean`).

**Utility example (hashes a `ShiroPrincipal`'s password in place):**

```java
import org.apache.shiro.biz.authz.principal.ShiroPrincipal;
import org.apache.shiro.biz.utils.PasswordEncryptUtils;

ShiroPrincipal user = new ShiroPrincipal("admin", "plain-password");
PasswordEncryptUtils.encryptPassword(user); // md5, 2 hash iterations by default
```

## 9. Testing & Build

```bash
# Full build with tests and JaCoCo coverage report/check
./mvnw clean verify

# Run tests only
./mvnw test

# Install into the local repository
./mvnw install
```

Test & gate facts (as configured in the pom):

- JUnit 4 tests exist under `src/test/java` (`org.apache.shiro.biz.LoginLogoutTest`, `org.apache.shiro.biz.CodecAndCryptoTest`) covering login/logout and codec/crypto topics; a `web-fragment.xml` test resource is bundled.
- JaCoCo is bound to `prepare-agent` / `report` / `check`; the `check` rule requires a **90% line coverage ratio** (configured with `haltOnFailure=false`, i.e. reported rather than hard-failing).

## 10. Versioning & Branches

| Branch | JDK baseline | Version pattern | Status |
| :--- | :--- | :--- | :--- |
| `feature/1.0.x` | JDK 8 | `1.0.x.*` | Active; current snapshot `1.0.x.20260630-SNAPSHOT` |
| `feature/2.0.x` | JDK 17 | `2.0.x.*` | Maintained |
| `feature/3.0.x` | JDK 21 | `3.0.x.*` | Maintained |

Maintenance strategy: the 1.0.x line keeps JDK 8 compatibility for legacy deployments; the 2.0.x and 3.0.x lines are the modern JDK baselines. Release artifacts are published to the project's configured artifact repository (Aliyun Packages) and GitHub Releases; the project has not yet published to Maven Central.

## 11. Contributing & License

Contributions are welcome — please open an issue or a pull request on the [GitHub repository](https://github.com/easy-4-java/shiro-extension). Code style follows the existing conventions of the repository (4-space indentation, commented Maven plugin/dependency blocks).

This project is licensed under the **Apache License 2.0**. See [LICENSE](LICENSE) for details.
