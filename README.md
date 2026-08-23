# ShrinkGuard

R8 fitness harness and consumer rules linter for Kotlin and Android libraries.

R8 full mode has been the default since AGP 8.0. Google's guidance for library authors makes two demands: your library must not break under full mode, and your `consumer-rules.pro` must remain narrow because broad rules bloat every app that depends on you.

ShrinkGuard brings the `binary-compatibility-validator` (`apiDump` / `apiCheck`) workflow to R8 shrinking:

```bash
./gradlew shrinkReport   # regenerates and updates shrink-report.txt
./gradlew shrinkCheck    # diffs against the committed report, fails on regression
```

## Why ShrinkGuard?

1. **Eliminates release-only crashes:** When a reflection target, serialization adapter, or JNI signature is missing keep rules, the library crashes in consumer release builds ("works in debug, crashes in release"). ShrinkGuard exercises R8 full mode directly in your library build to catch regressions in CI.
2. **Reviewable PR diffs:** Every public API member that survives, gets inlined, or is kept via consumer rules is recorded in a committed `shrink-report.txt`. Reviewers see the shrinking impact of any code or rule change directly in pull request diffs.
3. **Rejects toxic consumer rules:** Consumer rules merge into the host application. Global directives like `-dontobfuscate` or `-dontoptimize` disable optimizations across the entire consuming app. ShrinkGuard lints rules before release and fails builds containing toxic or over-broad directives.

## Installation

Apply the plugin in your library's `build.gradle.kts`:

```kotlin
plugins {
    id("io.github.ivamsi.shrinkguard") version "0.1.0"
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
Public API surviving unchanged: 2 (14.3%)
Public API renamed/inlined: 12
Internal members kept by consumer rules: 4
Dead code members stripped: 4
Rule violations: 0

## Kept Public API Surface
class com.example.currency.CurrencyService
class com.example.currency.Money

## Renamed / Inlined Public Members
com.example.currency.CurrencyService#convert(...) -> <inlined/stripped>
com.example.currency.Money#formatted() -> <inlined/stripped>

## Internal Members Kept by Consumer Rules
class com.example.currency.internal.RateSerializer

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
