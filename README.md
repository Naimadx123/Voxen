# Voxen

Chat plugin for Paper 1.21.8+ (and Folia). Channels with their own formats, ranges and
permissions, private messages, parties, ignoring, @name mentions, nicknames, item sharing in
chat, a moderation stack (cooldowns, anti-repeat, anti-flood, word and link filters, mutes,
chat log) and optional cross-server chat over Redis, NATS or RabbitMQ.

## Links

- Documentation: https://voxen.vao.zone (setup, channels, formats, moderation, network, API)
- Issues and source: https://github.com/Naimadx123/Voxen
- API releases: https://repo.vao.zone/#/releases/zone/vao/voxen-api

## Quick start

1. Drop the jar into `plugins/` and start the server.
2. Edit the files in `plugins/Voxen/` (`config.yml`, `storage.yml`, `integrations.yml`, one
   file per channel in `channels/`, one per feature in `modules/`), then run `/voxen reload`.
3. Players talk in their active channel and switch with `/channel <name>`, or use a quick
   prefix such as `!hello` for global.

New settings are merged into your existing files on update, with your changes and comments
left alone. Changing command names in `config.yml` needs a restart; everything else applies
with `/voxen reload`.

## Compatibility

- Paper 1.21.8+ and compatible forks. Folia is supported (same jar).
- Java 21.
- Kotlin, HikariCP, database drivers and broker clients download themselves on first start.
- Optional soft dependencies: PlaceholderAPI, MiniPlaceholders, Vault, LuckPerms, DiscordSRV,
  EssentialsX Discord, Towny, Factions and mcMMO. Missing any of them breaks nothing, every
  hook has its own switch in `integrations.yml`.
- Storage is SQLite by default, MySQL/MariaDB when you point `storage.yml` at one. A shared
  database is what lets several servers see the same mutes, parties and ignores.

## Building

```bash
./gradlew build
```

The build produces two jars in `build/libs/`. The one to install is the shaded plugin jar
with the `v` prefix, `Voxen-v<version>.jar`. The other jar is not shaded and should not be
used on a server.

`./gradlew runServer` starts a local Paper server with the plugin for a quick test.

## For developers

The public API is published separately to <https://repo.vao.zone/>, so an addon can compile
against it without shipping the whole plugin.

Gradle:

```kotlin
repositories {
    maven("https://repo.vao.zone/releases") // use /snapshots for SNAPSHOT versions
}
dependencies {
    compileOnly("zone.vao:voxen-api:<version>")
}
```

Maven:

```xml
<repositories>
    <repository>
        <id>vao-releases</id>
        <url>https://repo.vao.zone/releases</url>
        <!-- or https://repo.vao.zone/snapshots for -SNAPSHOT versions -->
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>zone.vao</groupId>
        <artifactId>voxen-api</artifactId>
        <version>VERSION</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

`VoxenApi.isAvailable()` tells you when the service is up. Custom recipient providers, format
placeholders, the two chat events and the full `VoxenService` surface are documented under the
API section of the docs site.

## License

See [LICENSE](LICENSE).
