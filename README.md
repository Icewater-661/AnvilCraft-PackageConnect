# AnvilCraft: PackageConnect

[简体中文](README.zh-CN.md)

A small compatibility addon that connects AnvilCraft's storage blocks to Create's packaging and logistics system.

Packagers can directly access **Crates, Large Crates, Shulker Containers, and Hyperdimension Storage Stations**. Their contents can be queried through Create's stock links and stock tickers, and supplied to automated item requests.

## Features

- Direct packaging and unpacking without additional storage ports.
- Connections to the main and non-main parts of supported multiblock storage.
- Automatic packager orientation when placed beside supported storage.
- Shared inventory caches and item/component indexes for order extraction.
- Shared-storage identification to prevent duplicate stock reporting and requests from the same inventory.
- Preservation of AnvilCraft's capacities, item restrictions, and overflow disposal rules.

## Requirements

| Component  | Version                         |
| ---------- | ------------------------------- |
| Minecraft  | 1.21.1                          |
| Loader     | NeoForge; built with 21.1.251   |
| Create     | Tested with 6.0.10              |
| AnvilCraft | Tested with 1.6.0+snapshot.2465 |
| Java       | 21                              |

The current dependency ranges are defined in [neoforge.mods.toml](src/main/resources/META-INF/neoforge.mods.toml). New Create or AnvilCraft versions may require integration changes.

## Installation and use

Place the addon JAR in the instance's `mods` directory alongside Create, AnvilCraft, and their dependencies. Restart the game or server.

Place a packager with its inventory connection face against supported storage. Attach a stock link and use the normal Create logistics setup for stock queries and item requests.

The core inventory and packaging logic runs on the server. Client installation also provides consistent placement prediction. Server installation without the addon on clients is expected to work because this addon registers no new content or custom network payloads, but that specific multiplayer arrangement has not yet been tested.

For single-player, install the addon in the local instance, which also runs the integrated server.

## Inventory caching

The cache is an index of the real inventory, not a second inventory. Items remain in AnvilCraft storage.

Caches are shared by storage UUID. Initial access builds the index; normal mutations update only the affected slot. Count-only changes reuse the existing item/component key and slot set. Orders locate matching items through the index and snapshot only the slots needed for the current extraction; a single sufficient slot needs no snapshot. Summaries are rebuilt on demand after changes and reused between packagers. Their item stacks are separate from the private index keys.

Up to 256 storage caches are retained. Evicted caches are rebuilt on the next access, and all caches are cleared when the server stops. Internal aggregate counts use `long`; per-item counts exposed through Create are capped at its native `INF` value of 1,000,000,000.

Complete stock summaries, arrival notifications, catalogue transmission, and native AnvilCraft insertion still have costs that grow with inventory size and component variants. 

Administrators can run:

```text
/packageconnect stats
```

This reports an immutable snapshot of cache builds, scanned slots, incremental updates, indexed extractions, native extraction slots, and summary cache hits. These counters are not timing measurements. The index uses fastutil primitive collections already supplied by Minecraft; the addon bundles no additional runtime library.

## Building from source

Install JDK 21, set `JAVA_HOME` to that JDK, and clone this repository. The Gradle wrapper downloads Gradle and the pinned mod dependencies automatically. The first build requires network access; no existing Minecraft instance, local `libs` directory, or dependency-preparation script is needed.

Linux / macOS:

```sh
./gradlew build
./gradlew runClient
```

Windows PowerShell:

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

`build` writes the addon to `build/libs/`. `runClient` launches a development client in `run-client/` with Create, AnvilCraft, and this addon. An IDE can import the root directory as a Gradle project using JDK 21.

Versions are pinned in [gradle.properties](gradle.properties):

| Dependency | Pinned version | Publication |
| --- | --- | --- |
| Create | 6.0.10 for Minecraft 1.21.1 | Modrinth release `UjX6dr61` |
| AnvilCraft | 1.6.0+snapshot.2465 for Minecraft 1.21.1 | Modrinth release `85SOrOGM` |
| Ponder | 1.0.82+mc1.21.1 | Create Maven, compile only |
| AnvilLib | 2.0.0+snapshot.546 | AnvilLib Maven, compile only |

The two Modrinth dependencies are complete release JARs; NeoForge discovers their embedded runtime libraries. Matching Ponder and AnvilLib publications expose nested classes to the Java compiler without introducing separate runtime copies. Dependency JARs are not bundled into the addon. When updating a mod release, also check the versions of its embedded libraries.

## Code style and development checks

Java follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html). Project-owned explanatory code comments and Javadoc are bilingual Chinese/English, with AI-assisted translation. Contributor and AI rules are in [AGENTS.md](AGENTS.md); responsibilities, upstream contracts, and validation boundaries are in [docs/architecture.md](docs/architecture.md).

```sh
./gradlew spotlessApply
./gradlew styleCheck
./gradlew build
```

Spotless 8.1.0 uses `google-java-format` 1.28.0. Checkstyle 12.3.0 uses its complete bundled Google rules, with violations treated as errors and zero warnings allowed. `spotlessApply` fixes formatting; braces, names, and missing documentation may require manual fixes. XML and HTML Checkstyle reports are written to `build/reports/checkstyle/`.

`styleCheck` checks all three Java source sets without compiling the mod or resolving game dependencies. Both `check` and `build` run style checks, JUnit regression tests, and compilation of world integration checks; they never auto-format source. GitHub Actions runs a separate style check and builds on Linux, Windows, and macOS.

`./gradlew test` runs JUnit tests against real AnvilCraft handlers without starting a server or accessing a world. Coverage includes component variants, simulation invariance, long counts, physical holes and duplicate entries, cache invalidation and eviction, live order queues, native space weights, overflow disposal, and eternal items. A fixed-seed differential test compares capacity plans against actual native insertion across 360 scenarios. Reports are in `build/reports/tests/test/`.

`./gradlew runServer` uses the dedicated `run-server/` world, places and removes blocks, changes inventory, runs integration assertions, and shuts down. This checks real Mixin callbacks, connections, multiblock parts, stock links, packaging, unpacking, global storage identity, storage replacement, unloaded chunks, container fallback, and placement orientation. Never point it at a gameplay world. The pinned NeoForge development launcher uses upstream development-mode EULA handling; this project does not write an EULA acceptance file. Where a launcher requires acceptance, read and accept the Minecraft EULA yourself. On Windows, use `.\gradlew.bat`.

World checks and their startup listener live in `src/integrationTest/` and are loaded only by `runServer`; neither the release JAR nor `runClient` contains them. Before launching, Gradle deletes the previous result. The task requires a fresh `PASS <assertion count>` in `build/reports/integrationTest/result.txt`; missing results and `FAIL` make the task fail. Logs still contain `PACKAGECONNECT SELFTEST PASS` / `FAIL`. To verify the failure path in the dedicated world, run `./gradlew runServer -PselfTestFailure=true` (expected to fail), then run normally again.

Successful compilation or run preparation does not replace these world checks. Automated checks also do not establish client rendering, mixed client/server installation compatibility, or performance improvements in milliseconds.

## AI-assisted code

**Parts of this project's source code and documentation were generated with AI assistance using OpenAI Codex.** Feature requirements and design direction were provided by the project maintainer.

## License

This project is licensed under the **MIT License**. See [LICENSE](LICENSE).

AnvilCraft, Create, NeoForge, and other dependencies retain their own licenses. This is an independent compatibility addon.
