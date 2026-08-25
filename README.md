# ShrinkGuard

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1+-purple.svg)](https://kotlinlang.org)
[![AGP](https://img.shields.io/badge/AGP-8.0+-green.svg)](https://developer.android.com/build)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](https://opensource.org/licenses/Apache-2.0)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-0.1.1-red.svg)](https://central.sonatype.com/artifact/io.github.ivamsi/shrinkguard-core/0.1.1)

R8 fitness harness and consumer rules linter for Kotlin and Android libraries.

R8 full mode has been the default since AGP 8.0. [Google's guidance for library authors](https://developer.android.com/build/shrink-code#configuration-files) makes two demands: your library must not break under full mode, and your `consumer-rules.pro` must remain narrow because broad rules bloat every app that depends on you.

ShrinkGuard brings the `binary-compatibility-validator` (`apiDump` / `apiCheck`) workflow to R8 shrinking:

```bash
./gradlew shrinkReport   # regenerates and updates shrink-report.txt
./gradlew shrinkCheck    # diffs against the committed report, fails on regression
```

## Why ShrinkGuard?

1. **Catches release-only breakage:** When a reflection target, serialization adapter, or JNI signature is missing keep rules, the library breaks in consumer release builds ("works in debug, crashes in release"). ShrinkGuard compiles a synthetic consumer that calls your public API, runs R8 full mode over both, then reads R8's own output to record what survived, what was renamed, and what disappeared. Remove a keep rule that a reflection target depends on and the report changes, so the check fails.
2. **Reviewable PR diffs:** Every public API member that survives, gets inlined, or is kept via consumer rules is recorded in a committed `shrink-report.txt`. Reviewers see the shrinking impact of any code or rule change directly in pull request diffs.
3. **Rejects toxic consumer rules:** Consumer rules merge into the host application. Global directives like `-dontobfuscate` or `-dontoptimize` disable optimizations across the entire consuming app. ShrinkGuard lints rules before release and fails builds containing toxic or over-broad directives.

## Installation

Apply the plugin in your library's `build.gradle.kts`:

```kotlin
plugins {
    id("io.github.ivamsi.shrinkguard") version "0.1.1"
}

shrinkGuard {
    baselineFile.set(file("shrink-report.txt")) // optional: defaults to shrink-report.txt
    ruleLint {
        failOnToxicFlags.set(true)              // fails on -dontobfuscate, -dontoptimize, etc.
        failOnOverbroadRules.set(false)          // warns on -keep class com.example.** { *; }
    }
}
```

## Tasks

| Task | Purpose |
| :--- | :--- |
| `shrinkReport` | Executes R8 full mode against the library and writes the baseline `shrink-report.txt`. |
| `shrinkCheck` | Lints consumer rules, runs R8 full mode, and verifies output against the committed baseline (wired into `check`). |

## Reading the report

`Public API renamed` is ordinary. R8 renames anything a keep rule does not pin, and applications
are shrunk the same way, so a renamed member still works for callers compiled against it.

`Public API removed` and members disappearing from **Internal Members Retained** are the signals
that matter. Anything reached only by reflection, serialization, or JNI has to be named by a rule
you ship, or R8 deletes it and consumers crash at runtime.

The fitness run disables R8 optimization, so the report measures shrinking and obfuscation, which
your keep rules control. Inlining decisions depend on how many call sites an application has and
are not something a library can influence.

## Workflow

1. **Generate baseline:** Run `./gradlew shrinkReport` to produce the initial `shrink-report.txt`, then commit it to git.
2. **CI validation:** Run `./gradlew check`. `shrinkCheck` runs automatically and ensures no unexpected shrinking changes occurred.
3. **Updating baseline:** When you intentionally modify public APIs or update keep rules, re-run `./gradlew shrinkReport` and commit the resulting diff.

## Sample Report (`shrink-report.txt`)

```text
# ShrinkGuard R8 Report
# Library: currency-library

## Summary
Public API members: 14
Public API surviving unchanged: 0 (0.0%)
Public API renamed: 14
Public API removed: 0
Internal members retained: 7
Dead code members stripped: 1
Rule violations: 0

## Kept Public API Surface

## Renamed or Removed Public Members
com.example.currency.Money -> a.b
com.example.currency.Money#formatted()Ljava/lang/String; -> c
com.example.currency.Money#getCurrencyCode()Ljava/lang/String; -> e

## Internal Members Retained
class com.example.currency.internal.RateSerializer {
    constructor <init> ()V
    method serialize (Lcom/example/currency/Money;)Ljava/lang/String;
}

## Applied Consumer Rules
-keepclassmembers class com.example.currency.internal.RateSerializer {
    public <init>();
    public java.lang.String serialize(com.example.currency.Money);
}
```

## Consumer Rule Guidance

### Toxic Directives (Forbidden)
Library `consumer-rules.pro` files are merged globally into the host application. The following directives are rejected by ShrinkGuard:
- `-dontobfuscate` (disables obfuscation globally)
- `-dontoptimize` (disables optimizations globally)
- `-dontshrink` (disables tree shaking globally)
- `-repackageclasses` (forces global class repackaging)
- `-flattenpackagehierarchy` (forces global package flattening)
- `-optimizations` (overrides global optimization passes)

### Prefer Targeted Rules
Avoid broad package-level wildcards that bloat consumer APKs:

```pro
# BAD: Forces consumer apps to keep all classes and members in the package
-keep class com.example.lib.** { *; }

# GOOD: Targets only members needed for reflection or serialization
-keepclassmembers class com.example.lib.internal.ModelAdapter {
    public <init>();
    public java.lang.String serialize(java.lang.Object);
}
```

## License

```text
Copyright 2026 Vamsi Vaddavalli

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```
