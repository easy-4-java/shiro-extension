# shiro-extension

[English](./README.md) | [简体中文](./README.zh-CN.md)

[![Java](https://img.shields.io/badge/Java-17-orange)](https://github.com/easy-4-java/shiro-extension) [![License](https://img.shields.io/badge/license-Apache%202.0-green)](./LICENSE)

面向 Apache Shiro 的业务化扩展。`shiro-extension` 通过 `shiro-extension-core` 与 `shiro-extension-spring` 分离无 Spring 依赖的基础能力和 Spring 集成能力。

## 目录

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

**是什么**

`shiro-extension` 是 Apache Shiro 的通用扩展层。core 模块提供无 Spring 依赖的认证、授权、缓存、会话、Servlet 与工具能力；spring 模块提供处理器、过滤器、消息源、调度和 Shiro-Spring 集成。

**不是什么**

- Spring Boot 应用可通过 Spring 模块使用此 Shiro 扩展库。
- 它不会将 Shiro 打成 shaded jar 随包发布；`org.apache.shiro:shiro-spring` 是普通依赖。

**典型场景**

| 场景 | 说明 |
| :--- | :--- |
| 表单 / REST 登录流程 | 使用 `TrustableFormAuthenticatingFilter` / `TrustableRestAuthenticatingFilter` 配合 `DefaultAuthenticationToken`（验证码、记住我、主机）。 |
| 登录失败处理 | `DefaultAuthenticationFailureHandler` + `AuthcResponse` 将异常映射为统一响应模型；重试限制由 `CredentialsRetryLimitCredentialsMatcher` 提供。 |
| 授权模型 | `ShiroPrincipalRepository` / `ShiroPrincipal` 配合 `BitPermission` / 通配符权限与 `@RolesAllowed` AOP。 |
| 缓存 | 按需切换 Shiro `CacheManager` 实现：`CaffeineCacheManager`、`GuavaCacheManager`、`SpringCacheManager`、`SessionCacheManager`。 |
| Web 加固 | `HttpServletShiroFilter`、请求头/Referrer/方法/限流过滤器、`HttpServletSessionDequeFilter`（在线用户控制）。 |

## 2. Features & Status

| 能力 | 状态 | 说明 |
| :--- | :--- | :--- |
| 认证 Token（`authc.token`） | 可用 | `DefaultAuthenticationToken`（验证码、强度）、`CaptchaAuthenticationToken`、`LoginTypeAuthenticationToken`、`PwdStrengthAuthenticationToken`、`UsernameWithoutPwdToken`、`LoginProtocolAuthenticationToken`、`LoginType`。 |
| 认证处理器（`authc`） | 可用 | `AuthenticationSuccessHandler` / `AuthenticationFailureHandler` 及默认实现、`AuthcResponse` / `AuthcResponseCode`、`AuthenticationListenerAdapter`。 |
| 凭证匹配器（`authc.credential`） | 可用 | `DefaultCredentialsMatcher`、`CredentialsRetryLimitCredentialsMatcher`。 |
| 模块化 Realm 认证（`authc.pam`） | 可用 | `DefaultModularRealmAuthenticator`、`AtLeastTwoAuthenticatorStrategy`、`OnlyOneAuthenticatorStrategy`。 |
| 业务异常（`authc.exception`） | 可用 | 验证码/票据/令牌/密钥/会话/终端相关异常。 |
| 授权注解（`authz.annotation` / `authz.aop`） | 可用 | `@RolesAllowed` + `RolesAllowedAnnotationHandler` + `RoleAllowsAnnotationMethodInterceptor`。 |
| 权限模型（`authz.permission`、`authz.principal`） | 可用 | `BitPermission`、`BitAndWildPermissionResolver`、`DefaultRolePermissionResolver`、`ShiroPrincipal`、`ShiroRole`、`ShiroPermission`、`ShiroPrincipalRepository`（+`Impl`）。 |
| 缓存管理器（`cache`） | 可用 | 基于 Caffeine、Guava、Spring、HTTP Session 的管理器与包装类。 |
| Realm 基类（`realm`） | 可用 | `AbstractAuthorizingRealm`，内置 `ShiroPrincipalRepository` 与 Realm 监听器。 |
| 会话支持（`session`） | 可用 | `SimpleOnlineSession`（+工厂）、`SequenceSessionIdGenerator`、`SpringSessionValidationScheduler`、`DefaultSessionListener`。 |
| Web 过滤器（`web.filter`） | 可用 | 请求头/Referrer/方法/限流/转义/会话队列/会话状态过滤器、`HttpServletShiroFilter`。 |
| Spring 集成（`spring`） | 可用 | `ShiroFilterProxyFactoryBean`、注解拦截器/顾问。 |
| i18n 消息 | 可用 | `messages.properties`（+`en_US`、`zh_CN`）、`ShiroBizMessageSource`。 |

> 状态以 `feature/2.0.x` 分支上的 `2.0.x.20260630-SNAPSHOT` 为准。

## 3. Requirements & Compatibility

| 项目 | 版本 |
| :--- | :--- |
| JDK | 17+ |
| Maven | 3.0+（内置 Maven Wrapper 3.5.0） |
| Apache Shiro | 2.0.1（`shiro-web`、`shiro-spring`） |
| Spring Framework | 5.3.36（`spring-webmvc`） |
| easy4j 依赖 | `io.github.easy4j:jwt-issuer-api` |
| JSON / 序列化 | fastjson2 2.0.52、jackson-databind 2.17.2、flexjson、xstream |
| 其他 | caffeine 2.9.3、guava 33.2.1-jre、commons-lang3 / commons-text / commons-io、javax.servlet-api 4.0.1 |

**版本线**

| 分支 | JDK 基线 | 版本模式 |
| :--- | :--- | :--- |
| `feature/1.0.x` | JDK 8 | `1.0.x.*` |
| `feature/2.0.x` | JDK 17 | `2.0.x.*` |
| `feature/3.0.x` | JDK 21 | `3.0.x.*` |

## 4. Architecture & Modules

```text
 HTTP 请求
      |
      v
 HttpServletShiroFilter / 过滤器链（web.filter）
      |-- 请求头 / Referrer / 方法 / 限流 / 转义过滤器
      |-- authc: TrustableForm/RestAuthenticatingFilter（验证码、重试）
      |-- authz: Roles / AnyRoles / Permissions 授权过滤器
      v
 DefaultModularRealmAuthenticator（pam 策略）
      |
      v
 AbstractAuthorizingRealm <-- ShiroPrincipalRepository
      |                          （ShiroPrincipal / Role / Permission）
      +-- cache: Caffeine / Guava / Spring / Session 管理器
      +-- session: SimpleOnlineSession + Session DAO / 校验
      +-- authz: @RolesAllowed AOP + BitPermission 解析器
      v
 Subject（认证结果）--> 处理器 --> AuthcResponse（JSON）
```

Maven 父工程构建以下模块。

| 模块 | 职责 |
| :--- | :--- |
| `shiro-extension-core` | 不依赖 Spring 的 Shiro 认证、授权、缓存、会话、Servlet 与工具扩展 |
| `shiro-extension-spring` | Spring 处理器、过滤器、消息源、调度和 Shiro-Spring 集成；依赖 core |
| `shiro-extension-hazelcast3` | 可选的 Hazelcast 3.12 IMap 重试计数适配 |

| 包 | 职责 |
| :--- | :--- |
| `authc` / `authc.token` / `authc.credential` / `authc.pam` / `authc.exception` | 认证 Token、处理器、匹配器、策略与异常 |
| `authz` / `authz.annotation` / `authz.aop` / `authz.permission` / `authz.principal` | 授权处理器、`@RolesAllowed`、权限解析器、主体模型 |
| `cache`（`caffeine`、`guava`、`http`、`spring`） | 可插拔 `CacheManager` 实现 |
| `realm` | `AbstractAuthorizingRealm` 与 Realm 监听器 |
| `session` / `session.mgt` | 在线会话模型、ID 生成器、校验调度器 |
| `spring` / `spring.security.interceptor` | Spring 集成：过滤器代理工厂 Bean、注解拦截器 |
| `web`（`filter`、`filter.authc`、`filter.authz`、`servlet`、`mgt`、`env`） | Servlet 过滤器、Servlet、Subject 工厂、INI Web 环境 |
| `utils` | `HmacSHA256Utils`、`PasswordEncryptUtils`、`IDWorker`、`SubjectUtils`、`SerializeUtils`、`WebUtils2`、`WebThreadContext`、UID `Sequence` |

## 5. Installation

制品发布到项目配置的阿里云制品仓库。无 Spring 场景可单独使用 core；Spring 场景使用 spring 模块，它会传递依赖 core。

**Maven**

```xml
<dependency>
    <groupId>io.github.easy4j</groupId>
    <artifactId>shiro-extension-spring</artifactId>
    <version>2.0.x.20260630-SNAPSHOT</version>
</dependency>
```

**Gradle**

```groovy
implementation 'io.github.easy4j:shiro-extension-spring:2.0.x.20260630-SNAPSHOT'
```

## 6. Quick Start

基于 `ShiroPrincipalRepository` 的最小 Realm：

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
        // 根据 token 主体从业务系统加载用户，
        // 返回如 SimpleAuthenticationInfo(principal, hash, salt, realmName)
        return /* 你的 AuthenticationInfo */ null;
    }

    @Override
    public Set<String> getRoles(Object principal) {
        return /* 该主体的角色 */ Collections.emptySet();
    }

    @Override
    public Set<String> getPermissions(Object principal) {
        return /* 该主体的权限 */ Collections.emptySet();
    }
};

AbstractAuthorizingRealm realm = new AbstractAuthorizingRealm() {};
realm.setRepository(repository);
realm.setCredentialsMatcher(new DefaultCredentialsMatcher());
// realm.setCacheManager(new CaffeineCacheManager()); // 可选缓存
```

**预期结果：** `realm` 通过 `ShiroPrincipalRepository.getAuthenticationInfo(token)` 完成认证，通过 `getRoles` / `getPermissions` 完成授权；注册到 `SecurityManager` 后即可支持 `subject.login(token)` 与权限校验。

## 7. Configuration

通过构造器和 Bean 配置扩展行为。

| 扩展点 | 配置方式 |
| :--- | :--- |
| `AbstractAuthorizingRealm` | `setRepository(ShiroPrincipalRepository)`、`setRealmsListeners(List)`、`setCredentialsMatcher(...)` |
| `CredentialsRetryLimitCredentialsMatcher` | 重试限制与缓存设置（依赖 `CacheManager`） |
| Realm 缓存 | `setCacheManager(CaffeineCacheManager / GuavaCacheManager / SpringCacheManager / SessionCacheManager)` |
| 过滤器（`HttpServletShiroFilter` 等） | Bean 属性与 Shiro 过滤器链定义 |
| i18n 消息 | 随包 `org/apache/shiro/biz/messages*.properties`、`ShiroBizMessageSource` |

### Spring Boot 共享重试计数

如果匹配器的重试缓存名为 `login`，声明名为 `shiro.credential-retry.login` 的 `CredentialRetryStore` Bean。Spring Boot 会将它绑定到 `DefaultCredentialsMatcher` 和 `CredentialsRetryLimitCredentialsMatcher`，包括 Spring 管理的 Realm 内部创建的实例。手工指定的存储优先。缺少同名 Bean 时，匹配器沿用原 Shiro 缓存配置。共享重试窗口的应用实例应使用相同的缓存提供者、重试命名空间和主体键映射。

对于现有 Ehcache 2 重试缓存，先停止访问该区域的全部旧版客户端，再启用共享重试存储，并沿用原区域及主体键。首次预留在键锁内转换已保存的旧计数，计入本次尝试，并保留原 TTL 截止时间和配置的空闲过期策略。集群部署使用强一致性区域。

对于原生 Ehcache 3，暂停所有重试客户端并排空在途写入，每个区域只允许一个迁移写入方。使用原缓存对已知主体调用 `migrateLegacyWindow`。区域须使用仅在创建时设置的过期策略或永不过期策略。`Serializable` 等可接受旧计数和新窗口的区域直接保存新窗口。`AtomicInteger` 类型区域保存含窗口 ID 的可序列化子类型，要求序列化器保留该类型。活跃条目在原位置替换，保留原截止时间。会在写入时重置截止时间的策略需要独立切换方案。

回滚时保持客户端暂停并排空在途写入，再对已知主体调用 `restoreLegacyWindow`。缓存区域必须接受 `AtomicInteger`。活跃窗口恢复为普通旧版计数，保留当前次数和原截止时间。缺失、已过期或已经恢复的条目会跳过。

Hazelcast 5.2+ 与 3.12 的独立重试 IMap 沿用同样的切换顺序。所有集群成员须部署重试存储模块，并沿用原 IMap 和主体键。首次预留会原子转换旧计数，保留剩余 TTL，并按原策略刷新空闲时间。使用 3.12 适配器时需传入 IMap 配置的最大空闲时长。

对于现有 JCache 重试区域，停止旧客户端后沿用原区域和主体键。条目处理器会在同一次更新中转换计数并预留本次尝试。区域必须按值存储，并在访问和更新时保留原截止时间。值类型允许两种格式的区域（例如 `Object`）直接存储重试窗口。值类型为 `AtomicInteger` 的区域使用可序列化的计数器子类保存窗口标识。切换前验证提供方及其序列化配置能保留子类，并在所有执行节点部署此模块。该类型区域的迁移已在双节点 Hazelcast JCache 集群验证。回切时暂停所有重试客户端，等待在途写入完成，然后对原区域内每个已转换的主体调用 `restoreLegacyWindow`。该方法原地恢复普通 `AtomicInteger`，保留当前计数和原截止时间，跳过缺失、过期或已恢复条目。检查区域中是否仍有已转换条目，再恢复旧客户端。

对于 Spring Data Redis，切换期间暂停所有旧版和新版重试客户端，等在途缓存写入全部到达 Redis 后再导入。用原缓存和新存储使用的独立目标命名空间，对每个已知的字符串主体调用适配器的 `migrateLegacyWindow`。导入沿用源缓存的键和值序列化配置，复制已保存的计数与 Redis 绝对截止时间。源数据保留，跳过缺失、已过期和目标已存在的窗口。恢复客户端之前先核对本次提交的全部主体，并在同一切换窗口完成导入。启用空闲续期时，从下一次预留起应用原策略。

J2Cache 的 Jedis 和 Lettuce 适配器也可在独占切换期间对已知字符串主体调用 `migrateLegacyWindow`。导入按原渠道的序列化方式读取 Redis 二级缓存计数，保留普通键或 Hash 区域的 Redis 截止时间，仅在目标窗口不存在时写入。沿用原缓存提供者配置，并指定独立的目标命名空间。迁移按 JVM 默认字符集写入的旧键时，沿用原字符集。Jedis 按原二进制键查询截止时间。对于非 UTF-8 的 Lettuce 旧键，除 UTF-8 目标连接外，还需传入二进制脚本连接。

J2Cache Memcached 适配器也可在同一独占切换阶段导入已知主体。按原键路由选择保存旧值的源节点。对允许 Meta Text 协议的可信源节点，可向 `migrateLegacyWindow` 传入地址。需要认证时，先认证专用 Meta Text 连接，设置有限的读取超时，再传给 `migrateLegacyWindowFromMetaConnection`。调用方负责认证、传输安全和关闭连接。连接上应没有在途命令，并由单个调用方顺序读取。适配器同时读取旧值和剩余有效期，按服务端秒级精度确定绝对截止时间，保留原条目，仅在目标不存在时写入。仅支持二进制协议的节点（包括要求二进制 SASL 的节点）仍需要独立迁移方式，因为其 GET 操作不返回原有效期。 如果能确认所有旧窗口的有效期上限，先暂停旧、新客户端并排空在途写入，等待所有源节点上的旧窗口过期，再启用新存储。存在永久窗口或无法确认有效期上限时，应继续使用旧存储，直到取得可信的到期时间快照。二进制 GET 返回计数和 CAS，无法区分限时窗口和永久窗口。

如果仅支持二进制协议的源节点已在可保留的内存文件上启用 [Memcached 热重启](https://docs.memcached.org/features/restart/)，可在独占切换期间通过现有 Meta Text 导入器读取恢复后的窗口。保持客户端暂停并排空在途写入。向源进程发送 `SIGUSR1` 以正常停机，等待主机时钟超过记录的停机秒数，再使用同一个内存文件和兼容的 slab、CAS 设置重启，并开放隔离的 Meta Text 监听。监听应启用认证或限制为可信本机访问。确认源条目确实恢复后再导入。Memcached 1.6.45 回环测试保留了限时窗口的原始截止时间和永久窗口的无截止状态，停机期间已过期的窗口也未恢复。此方式要求在写入旧窗口前已启用热重启。

对于 Redisson 3.12.5 的 `RMapCache` 重试区域，在同一独占切换阶段使用原缓存和主体调用 `migrateLegacyWindow`。导入复制原计数、条目固定截止时间、空闲策略和当前空闲截止时间，读取时不延长源窗口。若整个 Map 更早过期，使用更早的截止时间。同一 Redis 部署内使用独立的目标命名空间。

## 8. Core Usage / API

精选公开 API（类均位于 `org.apache.shiro.biz` 包下）：

| 类 | 职责 |
| :--- | :--- |
| `authc.token.DefaultAuthenticationToken` | `UsernamePasswordToken` 子类，支持验证码与密码强度。 |
| `authc.pam.DefaultModularRealmAuthenticator` | 模块化 Realm 认证器，内置业务策略（`AtLeastTwoAuthenticatorStrategy`、`OnlyOneAuthenticatorStrategy`）。 |
| `authc.credential.CredentialsRetryLimitCredentialsMatcher` | 超过重试限制即锁定的凭证匹配器。 |
| `authz.principal.ShiroPrincipal` | 可序列化主体模型（userid、userkey、usercode、username、password、salt、secret 等）。 |
| `authz.principal.ShiroPrincipalRepository`（+`Impl`） | Realm 使用的主体查询契约。 |
| `authz.permission.BitPermission` / `BitAndWildPermissionResolver` | 位 + 通配符权限模型。 |
| `cache.caffeine.CaffeineCacheManager` / `cache.guava.GuavaCacheManager` / `cache.spring.SpringCacheManager` | 面向 Shiro 的 `CacheManager` 实现。 |
| `session.mgt.eis.SequenceSessionIdGenerator` | 会话 ID 生成器（基于序列）。 |
| `spring.ShiorFilterProxyFactoryBean`\* | Shiro 过滤器代理的 Spring 工厂 Bean。 |
| `web.filter.HttpServletSessionDequeFilter` | 在线用户会话控制（支持踢出）。 |
| `utils.HmacSHA256Utils` / `utils.PasswordEncryptUtils` | 哈希 / 加密辅助工具。 |

\* 源码中声明的类名为 `ShiroFilterProxyFactoryBean`（继承 `ShiroFilterFactoryBean`）。

**工具示例（原地哈希 `ShiroPrincipal` 的密码）：**

```java
import org.apache.shiro.biz.authz.principal.ShiroPrincipal;
import org.apache.shiro.biz.utils.PasswordEncryptUtils;

ShiroPrincipal user = new ShiroPrincipal("admin", "plain-password");
PasswordEncryptUtils.encryptPassword(user); // 默认 md5，2 次哈希迭代
```

## 9. Testing & Build

```bash
# 完整构建（含测试与 JaCoCo 覆盖率报告/检查）
./mvnw clean verify

# 仅运行测试
./mvnw test

# 安装到本地仓库
./mvnw install
```

测试与门禁事实（以 pom 配置为准）：

- `src/test/java` 下存在 JUnit 4 测试（`org.apache.shiro.biz.LoginLogoutTest`、`org.apache.shiro.biz.CodecAndCryptoTest`），覆盖登录/登出与编解码/加密主题；随包提供 `web-fragment.xml` 测试资源。
- JaCoCo 绑定 `prepare-agent` / `report` / `check`；`check` 规则要求**行覆盖率不低于 90%**（配置了 `haltOnFailure=false`，即仅报告而不强制失败）。

## 10. Versioning & Branches

| 分支 | JDK 基线 | 版本模式 | 状态 |
| :--- | :--- | :--- | :--- |
| `feature/1.0.x` | JDK 8 | `1.0.x.*` | 维护中 |
| `feature/2.0.x` | JDK 17 | `2.0.x.*` | 活跃；当前快照 `2.0.x.20260630-SNAPSHOT` |
| `feature/3.0.x` | JDK 21 | `3.0.x.*` | 维护中 |

维护策略：1.0.x 版本线保持 JDK 8 兼容，服务于存量部署；2.0.x 与 3.0.x 版本线为现代 JDK 基线。发布制品发布到项目配置的制品仓库（阿里云制品仓库）与 GitHub Releases；项目尚未发布到 Maven Central。

## 11. Contributing & License

欢迎参与贡献——请在 [GitHub 仓库](https://github.com/easy-4-java/shiro-extension) 提交 Issue 或 Pull Request。代码风格遵循仓库既有约定（4 空格缩进、Maven 插件/依赖块带注释）。

本项目基于 **Apache License 2.0** 开源。详见 [LICENSE](LICENSE)。
