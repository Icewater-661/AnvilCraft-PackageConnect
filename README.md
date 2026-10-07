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

Caches are shared by storage UUID. Initial access builds the index; normal mutations update only the affected slot. Orders locate matching items and data components through the index instead of scanning the entire storage. Summaries are rebuilt on demand after changes and reused between packagers.

Up to 256 storage caches are retained. Evicted caches are rebuilt on the next access, and all caches are cleared when the server stops. Internal aggregate counts use `long`; per-item counts exposed through Create are capped at its native `INF` value of 1,000,000,000.

Complete stock summaries, arrival notifications, catalogue transmission, and native AnvilCraft insertion still have costs that grow with inventory size and component variants. 

Administrators can run:

```text
/packageconnect stats
```

This reports cache builds, scanned slots, incremental updates, indexed extractions, and summary cache hits. These counters are not timing measurements.

## Building from source

You need Java 21. The Gradle wrapper is included.

1. Obtain the matching Create and AnvilCraft JARs. Dependencies are not redistributed in this repository.

2. Prepare the compile dependencies from a local Minecraft instance:
   
   ```powershell
   .\scripts\prepare-libs.ps1 -ModsDirectory "C:\path\to\instance\mods"
   ```
   
   The script copies the two mod JARs and recursively extracts their embedded dependencies into `libs`. Start with an empty `libs` directory when changing dependency versions.

3. Build:
   
   ```powershell
   .\gradlew.bat build
   ```

The addon JAR is written to `build/libs/`. Dependency JARs are used for compilation and development runs; they are not bundled into the addon.

On other operating systems, prepare the same local `libs` contents and run `./gradlew build`.

## Development checks

`./gradlew runServer` starts an isolated development server in `run-server/`, executes functional checks, and shuts down. Configure the Minecraft EULA and server properties in that directory before the first run.

The current version passed 111 assertions covering storage connections, multiblock parts, stock-link reads, orders, unpacking, capacity simulation, item components, large counts, storage identity, and placement orientation. This was a functional check, not a performance benchmark. See [VALIDATION.md](VALIDATION.md) for the recorded scope and limitations.

## AI-assisted code

**Parts of this project's source code and documentation were generated with AI assistance using OpenAI Codex.** Feature requirements and design direction were provided by the project maintainer.

## License

This project is licensed under the **MIT License**. See [LICENSE](LICENSE).

AnvilCraft, Create, NeoForge, and other dependencies retain their own licenses. This is an independent compatibility addon.
