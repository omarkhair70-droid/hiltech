# SPIKE-01 — KMP Android + Windows Shared Feature

Status: RUNNING

## Hypothesis

HILTECH can use Kotlin Multiplatform + Compose Multiplatform with:
- a shared UI/domain module,
- a separate Android application entrypoint,
- a separate Windows/JVM desktop entrypoint,
- current stable Kotlin/Compose versions and stable Android SDK,
- AGP 9 KMP-compatible project structure.

## Versions

- Kotlin 2.4.20
- Compose Multiplatform 1.12.0
- Android Gradle Plugin 9.3.1
- Gradle 9.5.0 in CI
- JDK 17
- Android compile/target SDK 36
- min SDK 21
- Activity Compose 1.13.0

AGP 9.4 is intentionally not used because Kotlin 2.4.20's official compatibility table lists AGP support through 9.3.1.

## What this spike proves

1. shared common UI compiles.
2. Android application consumes shared UI.
3. Desktop JVM application consumes same shared UI.
4. common tests execute.
5. Windows desktop compile works on Windows CI.
6. Windows EXE/MSI packaging is attempted in the Windows job.

## Pass Criteria

ACCEPT if:
- Android debug APK compiles.
- shared tests pass.
- desktop JVM compiles on Linux.
- desktop compiles on Windows.
- at least one Windows native package task succeeds.
- no unsupported/deprecated architecture is required.

MODIFY if:
- architecture is sound but tooling requires supported version/configuration adjustment.

REJECT if:
- current supported KMP structure cannot reliably produce Android + Windows apps or creates unacceptable platform divergence.

## Production status

This directory is disposable spike evidence.
It is NOT production bootstrap.


## Android SDK note

Android 17 / API 37 is currently a preview SDK line. The spike intentionally uses stable Android 16 / API 36 so the architecture decision is not coupled to preview SDK availability on CI runners.
