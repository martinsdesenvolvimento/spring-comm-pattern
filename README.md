# spring-comm-pattern

A reusable Spring Boot library that centralizes HTTP client abstractions to standardize inter-service API communication using Feign.

## Overview

Part of the **MDS** ecosystem (`com.mds`). This library provides a session-based Feign interceptor framework with built-in support for SSO authentication, parameter encryption, and configurable request/response interception.

---

## Features

- **Abstract Feign Client** — `AbstractFeignClientBase` with session-aware request interception and response customization
- **Session Management** — `Session` and `FeignProperties` for configuring encrypted/unencrypted Feign sessions
- **SSO Integration** — Automatic `Authorization` and `X-EncryptedObject` header injection via `AuthenticatorSSOConfig`
- **Parameter Encryption** — Path and query parameter encryption via `CryptoHandler`
- **OkHttp Transport** — Pre-configured `FeignOkHttpConfig` using OkHttp as the Feign HTTP backend
- **Encryption Enums** — `EncryptionTypeRequestEnum` and `EncryptionTypeParameterEnum` for fine-grained control
- **Auto-configuration** — Spring Boot auto-config via `HttpClientAutoConfiguration`

---

## Stack

| Component | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.0.6 |
| Spring Cloud | 2025.1.1 (Oakwood) |
| Jackson | 3.x |
| Feign OkHttp | 13.4 |
| Guava | 33.1.0-jre |

---

## Installation

```xml
<dependency>
    <groupId>com.mds</groupId>
    <artifactId>spring-comm-pattern</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

---

## MDS Cross-dependencies

- `spring-token-pattern` — SSO authentication (`AuthenticatorSSOConfig`)
- `spring-crypto-pattern` — Cryptographic operations (`CryptoHandler`)
- `spring-error-pattern` — Exception hierarchy (`GeneralException`)

---

## Project Structure

```text
com.mds.comm
├── annotation/        — @FeignSession
├── base/              — AbstractFeignClientBase
├── config/            — DefaultFeignConfig, FeignOkHttpConfig
├── enumerator/        — EncryptionTypeParameterEnum, EncryptionTypeRequestEnum
│   └── pattern/       — EnumerationPattern
├── exception/         — PatternRequestException
├── interfaces/        — FeignClientApi, FeignConfigApi, ExecutableVoidGeneral
├── keys/              — HttpClientKeys
├── model/             — FeignProperties, Session
├── util/              — HttpClientUtil
└── wrapper/           — FeignRequestWrapper
```

---

## Migration from arcom-http-client-lib

| arcom (original) | MDS (migrated) |
|---|---|
| `com.santander.arcom.http.client` | `com.mds.comm` |
| `com.santander.arcom.authentication.rhsso.config.AuthenticatorRHSSOConfig` | `com.mds.token.sso.config.AuthenticatorSSOConfig` |
| `com.santander.arcom.dlb.v1.handler.CryptoHandler` | `com.mds.crypto.v1.handler.CryptoHandler` |
| `com.santander.error.handler.exception.GeneralException` | `com.mds.error.handler.exception.GeneralException` |
| `com.fasterxml.jackson.databind.*` | `tools.jackson.databind.*` (Jackson 3) |
| Java 17 / Spring Boot 3.5.3 | Java 21 / Spring Boot 4.0.6 |
| `parallelStream()` in `HttpClientUtil` | `stream()` (fix race condition) |

---

## Author

Martins Desenvolvimento de Sistemas (MDS)

## Migrating to 0.1.6: injected SSO session

`AbstractFeignClientBase` no longer reaches the
`AuthenticatorSSOConfig` singleton. Its protected constructor now takes a
`SsoSessionProvider` (from `spring-token-pattern`), which is provided by
`AuthenticationSSOHandler` as a regular bean:

```java
protected ExampleInterceptor(CryptoHandler cryptoHandler,
    @Qualifier("my-feign-config") FeignConfigApi feignConfigApi,
    SsoSessionProvider ssoSessionProvider) {
  super(cryptoHandler, feignConfigApi, ssoSessionProvider);
}
```

Subclasses must add the third constructor parameter. The behaviour is
unchanged: authorization and encrypted-object reads still create and
refresh the session lazily on first access. `PatternRequestException` now
extends `BaseException`, so interception failures flow through the MDS
error contract.
