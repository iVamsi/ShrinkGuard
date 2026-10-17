# ShrinkGuard

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1+-purple.svg)](https://kotlinlang.org)
[![AGP](https://img.shields.io/badge/AGP-8.0+-green.svg)](https://developer.android.com/build)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](https://opensource.org/licenses/Apache-2.0)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-0.1.1-red.svg)](https://central.sonatype.com/artifact/io.github.ivamsi/shrinkguard-core/0.1.1)

Review R8 shrinking changes and lint consumer keep rules before publishing your Kotlin or Android library.

ShrinkGuard runs a synthetic consumer against your library, records what R8 retains, renames, and removes, and compares that report with a committed baseline. It also flags consumer rules that can affect every application using your library.

## Quick start

Apply the plugin in your library module's `build.gradle.kts`:

```kotlin
plugins {
    id("io.github.ivamsi.shrinkguard") version "0.1.1"
}
```

Generate the first report, review it, and commit it:

```bash
./gradlew shrinkReport
git diff -- shrink-report.txt
git add shrink-report.txt
git commit -m "Add reviewed shrinking baseline"
```

A new, untracked report will not appear in `git diff`; open the file to review it before committing. For multi-module builds, use the library module's task path, such as `:your-library:shrinkReport`, and commit that module's report.

Validate subsequent changes locally and in your existing CI build:

```bash
./gradlew check
```

`shrinkCheck` is wired into `check`. Do not regenerate the baseline automatically in CI: doing so would replace the evidence you want to compare.

## Who is it for?

| Situation | How ShrinkGuard helps |
| --- | --- |
| Your library ships consumer keep rules | Lints global directives and broad keep patterns. |
| You want shrinking changes visible in code review | Produces a committed text report with reviewable diffs. |
| Your library uses reflection, serialization, or JNI | Helps spot changes to retained members that may need investigation. |
| You need proof that a real application works after shrinking | Keep release-build integration and runtime tests; a synthetic report is not proof of runtime correctness. |

Think of the workflow as `apiDump` / `apiCheck` for shrinking behavior, not as a replacement for binary compatibility checks or release-app tests.

## Configuration

```kotlin
shrinkGuard {
    baselineFile.set(file("shrink-report.txt"))
    ruleLint {
        failOnToxicFlags.set(true)
        failOnOverbroadRules.set(false)
    }
}
```

In this configuration, toxic directives fail validation; over-broad rules produce warnings. Set `failOnOverbroadRules` to true if you want them to fail validation as well.

| Task | Purpose |
| --- | --- |
| `shrinkReport` | Runs the shrinking analysis and writes or updates the baseline. |
| `shrinkCheck` | Lints consumer rules and checks the analysis against the committed baseline. |

## How it works and what it cannot prove

ShrinkGuard compiles a synthetic consumer that calls your public API, runs R8 full mode over the consumer and library, and reads R8 output to report retained, renamed, and removed members.

The fitness run disables R8 optimization. The report measures shrinking and obfuscation, not the inlining or optimization decisions of a real consuming app.

A baseline records observed behavior; it does not certify that the behavior is correct. Review the first report carefully. Reflection targets, serialization paths, JNI entry points, and application-specific behavior still require appropriate rules and runtime coverage.

See [Android's library keep-rule guidance](https://developer.android.com/build/shrink-code#configuration-files) for the platform context.

## Reading a report change

- `Public API renamed`: renaming can be expected for statically linked callers. Investigate names that external runtime mechanisms depend on.
- `Public API removed`: review why the member disappeared and whether consumers need it.
- `Internal Members Retained`: investigate disappearing members used through reflection, serialization, or JNI.
- Rule violations: inspect the reported directive and the configured warning/failure policy.

When a change is intentional, regenerate the report, inspect the diff, and commit the code, rules, and baseline together. Do not accept a new baseline just to make a failing build green.

## Example investigation

Suppose an internal serialization adapter is present in your reviewed baseline. A later edit removes its keep rule, and the adapter disappears from the new report.

1. Run `shrinkCheck` to compare against the committed baseline.
2. Investigate the report change rather than immediately overwriting the baseline.
3. Restore or narrow the required rule if the adapter must remain available.
4. Run the check again and exercise the serialization path in a release consumer.

This illustrates the review workflow, not a guarantee that every serialization regression is detected.

## Sample report

The following illustrative excerpt shows the report structure; your member names and counts will differ.

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
```

## Consumer rule guidance

Consumer rules are merged into the consuming application's configuration. ShrinkGuard flags these global directives as toxic:

- `-dontobfuscate`
- `-dontoptimize`
- `-dontshrink`
- `-repackageclasses`
- `-flattenpackagehierarchy`
- `-optimizations`

Avoid keeping an entire package when only a specific runtime entry point needs protection:

```pro
# Broad: retains the whole package and its members.
-keep class com.example.lib.** { *; }
```

A targeted member rule can be more appropriate when the containing class is already retained through another path:

```pro
-keepclassmembers class com.example.lib.internal.ModelAdapter {
    public <init>();
    public java.lang.String serialize(java.lang.Object);
}
```

Do not copy this rule unchanged without checking your runtime access pattern. Member retention alone is not a universal solution for a class discovered only by name.

## Compatibility

The repository currently advertises Kotlin 2.1+ and AGP 8.0+. This documentation update does not establish a tested version matrix or expand platform support. Validate the plugin with your library's build configuration before adding it to a release gate.

## Report a problem

[Open an issue](https://github.com/iVamsi/ShrinkGuard/issues) with the plugin, Gradle, Kotlin, AGP, and Java versions; a minimal library reproducer; the relevant consumer rules; and expected versus actual report or lint output. Remove private code, credentials, and sensitive paths before sharing logs.

## License

```text
Copyright 2026 Vamsi Vaddavalli

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```
