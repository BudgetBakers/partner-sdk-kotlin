# Changelog

All notable changes to this SDK are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
SDK adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- First release of the Kotlin/JVM server SDK, callable from Java: the whole
  Partner API v2 plus the five connection lifecycle actions, client-scoped
  calls, lazy cursor pagination, the hosted connect session helper with
  `waitForTerminal`, typed errors, retries with backoff on 429/5xx, automatic
  `Idempotency-Key` on creates, webhook verification and typed event parsing.
- Money as `BigDecimal` read from the exact wire token, with the `Money`
  helpers for exact cent arithmetic.
- Distributed as a source zip from the documentation site; Maven Central
  coordinates `com.budgetbakers:partner-sdk`.
