# ShrinkGuard

R8 fitness harness and consumer rules linter for Kotlin and Android libraries.

## Language

**ShrinkGuard**:
A Gradle plugin and R8 fitness harness that validates library consumer rules under R8 full mode.
_Avoid_: proguard helper, app shrinker

**Baseline report**:
The committed `shrink-report.txt` recording surviving public API members, kept reflection targets, inlined members, and applied consumer rules.
_Avoid_: proguard dump, mapping log

**shrinkReport**:
The Gradle task that generates or updates the committed baseline report.
_Avoid_: dumpRules, writeProguard

**shrinkCheck**:
The Gradle verification task that validates consumer rules against toxic flags and asserts that shrinking behavior matches the committed baseline report.
_Avoid_: lintProguard, testR8

**Synthetic Consumer**:
A synthesized program entry point referencing all public API members, simulating how real applications consume the library.
_Avoid_: dummy app, fake caller

**Toxic Rules**:
Global ProGuard directives (such as `-dontobfuscate`, `-dontoptimize`, `-dontshrink`, `-repackageclasses`) in library `consumer-rules.pro` that force global behavior on consuming applications.
_Avoid_: bad rules, invalid flags
