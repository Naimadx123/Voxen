# Voxen

Chat plugin for Paper 1.21.8+ servers. Channels, private messages, parties, ignoring, moderation, @name mentions, nicknames, item sharing in chat and optional cross-server chat over Redis, NATS or RabbitMQ. Works on Folia too.

## Contents

1. [Requirements](#requirements)
2. [Installation](#installation)
3. [Building from source](#building-from-source)
4. [Configuration files](#configuration-files)
5. [config.yml](#configyml)
6. [Channels](#channels)
7. [Format placeholders](#format-placeholders)
8. [Modules](#modules)
9. [Messages and languages](#messages-and-languages)
10. [Commands](#commands)
11. [Permissions](#permissions)
12. [Cross-server chat](#cross-server-chat)
13. [Storage](#storage)
14. [PlaceholderAPI](#placeholderapi)
15. [Developer API](#developer-api)

## Requirements

* Paper (or a fork) 1.21.8 or newer
* Java 21

Libraries (Kotlin, HikariCP, database drivers, broker clients) download themselves on first start. Optional plugins Voxen hooks into: PlaceholderAPI, MiniPlaceholders, Vault, LuckPerms, DiscordSRV, EssentialsDiscord, Towny, Factions, mcMMO. Missing any of them breaks nothing.

## Installation

1. Drop `Voxen-vX.X.jar` into the `plugins` folder.
2. Start the server. The plugin creates a `plugins/Voxen` folder with all the configuration.
3. Adjust the files, then run `/voxen reload`.

When you update the plugin, new settings and messages are added to your existing config files automatically on startup; your changes and comments stay untouched. Only the `channels/` folder is left alone, so deleted channels stay deleted.

Changing command names in `config.yml` requires a server restart; everything else applies with `/voxen reload`.

## Building from source

```
./gradlew build
```

The finished jar lands in `build/libs/`. For a quick test there is also `./gradlew runServer`, which spins up a local server with the plugin.

## Configuration files

| File | What it controls |
|------|-----------------|
| `config.yml` | server name, language, quick chat, item sharing, nicknames, command names |
| `storage.yml` | player data storage: SQLite or MySQL/MariaDB |
| `integrations.yml` | hooks into other plugins and the cross-server transport |
| `channels/*.yml` | one file per chat channel |
| `modules/mentions.yml` | @name mentions |
| `modules/moderation.yml` | cooldowns, anti-repeat, word filter, chat clear |
| `modules/private-messages.yml` | private message formats and social spy |
| `modules/party.yml` | party size and invite expiry |
| `modules/minimessage-tags.yml` | which formatting tags players may use |
| `messages/*.yml` | translations, one file per language |

## config.yml

| Key | Default | Meaning |
|-----|---------|---------|
| `server-name` | `server` | value of the `<server>` placeholder, in formats and in chat |
| `default-language` | `en_US` | console language and fallback for players |
| `quick-chat` | `true` | allows sending to a channel with its quick prefix, e.g. `!hello` |
| `chat-delivery` | `system` | how chat reaches players, see below |
| `item-share.enabled` | `true` | the `<item>`, `<helmet>`, `<chestplate>`, `<leggings>`, `<boots>` chat tags |
| `item-share.cooldown` | `10s` | delay between item shares per player, empty disables it |
| `nicknames.enabled` | `true` | the `/nick` command and nickname display |
| `nicknames.min-length` | `3` | minimum visible nickname length, formatting tags excluded |
| `nicknames.max-length` | `24` | maximum visible nickname length |
| `nicknames.filter` | `true` | rejects nicknames containing words blocked by the moderation word filter |
| `commands.*` | see below | command names and aliases |

Every player command can be renamed under `commands:`. The first entry is the main name, the rest are aliases:

```yaml
commands:
  message: [priv, szept]   # /msg becomes /priv with the alias /szept
  reply: [reply, r]
```

Only `/voxen` always keeps its name. Renaming requires a server restart.

### Chat delivery

`chat-delivery` picks how messages reach players:

* `system` (default): Voxen cancels the chat event and sends its own messages. Per-viewer features work fully and vanilla chat reporting is disabled, since messages are not signed.
* `player`: messages stay signed player chat, rendered through Paper's chat renderer. Vanilla chat reporting and the client's secure chat features keep working. The signature covers the original typed text, so word filter censoring and quick prefixes only change what is displayed, and a chat report contains what the player actually typed.

## Channels

Each file in `channels/` defines one channel; the file name (without `.yml`) is the channel id. The defaults are `global`, `local`, `world`, `staff`, `party` and `server`. To add a channel, copy an existing file and adjust it.

A channel with a `quick-prefix` can be reached without switching to it: the prefix plus the message sends straight there. The default channels use `!` for global (`!hello`), `@` for staff and `#` for party chat. Quick prefixes can be disabled globally with `quick-chat: false` in config.yml.

| Field | Default | Meaning |
|-------|---------|---------|
| `display-name` | channel id | MiniMessage name shown by `<channel>` |
| `type` | `custom` | behavior: `global`, `local`, `world`, `staff`, `party`, `server`, `custom` |
| `enabled` | `true` | disabled channels are ignored entirely |
| `default` | `false` | new players join this channel automatically |
| `default-active` | `false` | new players talk in this channel by default |
| `read-only` | `false` | only channel managers can write |
| `radius` | `-1` | hearing range in blocks for `local` channels, `-1` is unlimited |
| `worlds` | empty | limits the channel to listed worlds, empty means all |
| `format` | generic | MiniMessage chat format (see [Format placeholders](#format-placeholders)) |
| `group-formats` | empty | per group formats, matched by permission group or `voxen.chat.format.<key>` |
| `world-formats` | empty | per world formats, override everything else |
| `console-format` | none | separate format for the console log |
| `external-format` | none | format used when the message arrives from another server |
| `aliases` | empty | extra commands that switch to this channel, e.g. `/g` |
| `quick-prefix` | none | message prefix that sends straight to this channel, e.g. `!` |
| `cooldown` | none | per channel message cooldown, e.g. `3s` |
| `permissions.read/write/join/manage` | `none` | permission nodes gating access, `none` means no requirement |
| `empty-warning` | `false` | tells the sender when nobody heard them |
| `item-tags` | `true` | whether item share tags work here |
| `scope` | none | limits recipients to a team: `towny`, `factions` or `mcmmo` |
| `discord` | `false` | forwards messages to DiscordSRV or EssentialsDiscord |
| `discord-format` | none | how the forwarded message looks on Discord, e.g. `"[<world>] <player> » <message>"`; rendered to plain text. Empty keeps the default: the bare message for DiscordSRV (its own config formats it), `name: message` for EssentialsDiscord |
| `cross-server` | `false` (`true` for type `server`) | sends messages to other servers over the configured transport |
| `sound.*` | disabled | sound played to recipients (`key`, `source`, `volume`, `pitch`) |

Channel types in short: `global` reaches everyone, `local` reaches players within `radius` blocks, `world` reaches the sender's world, `staff` is a regular channel usually locked behind a permission, `party` reaches the sender's party, `server` is meant for cross-server chat, `custom` applies no extra logic.

## Format placeholders

Available in `format`, `group-formats`, `world-formats`, `console-format` and `external-format`:

| Placeholder | Value |
|-------------|-------|
| `<message>` | the message body |
| `<player>` | the sender's nickname if set, otherwise their real name; hover shows the real name |
| `<username>` | always the real name |
| `<display_name>` | the Bukkit display name |
| `<prefix>` / `<suffix>` | from LuckPerms or Vault |
| `<group>` | the sender's primary permission group |
| `<world>` | the sender's world |
| `<channel>` / `<channel_id>` | channel display name / id |
| `<server>` | `server-name` from config.yml |

If PlaceholderAPI is installed, `%papi%` style placeholders also work in every format. If MiniPlaceholders is installed, its `<placeholder>` tags work in formats too. Plugins can register their own `<placeholder>` tags through the [Developer API](#developer-api).

Players can additionally use `<server>` and, with the right permissions, MiniMessage formatting tags inside their messages. Item share tags (`<item>`, `<helmet>`, `<chestplate>`, `<leggings>`, `<boots>`) show the sender's equipment with a hover preview.

Placeholders inside player messages are permission gated, down to single placeholders:

* `voxen.chat.papi` parses every `%papi%` placeholder; `voxen.chat.papi.<placeholder>` parses one, e.g. `voxen.chat.papi.player_health` only parses `%player_health%`. Colors in placeholder output additionally need the matching `voxen.chat.legacy.*` permissions.
* `voxen.chat.miniplaceholders` parses every MiniPlaceholders tag; `voxen.chat.miniplaceholders.<tag>` parses one, e.g. `voxen.chat.miniplaceholders.luckperms_prefix` only parses `<luckperms_prefix>`.

## Modules

### Mentions (`modules/mentions.yml`)

Typing `@name` highlights the message for that player and plays a sound. `highlight` is the MiniMessage rendering of the mention, `cooldown` limits how often one player can mention others, and the `sound` section configures the notification. Players toggle their own mentions with `/voxen mentions`. Requires `voxen.chat.mention`.

### Moderation (`modules/moderation.yml`)

| Key | Meaning |
|-----|---------|
| `cooldown` | minimum delay between any two messages per player, empty disables it |
| `anti-repeat.enabled` | blocks sending the same message twice within `window-seconds` |
| `filter.enabled` | the word filter |
| `filter.mode` | `block` rejects the message, `censor` masks matches with `censor-char` |
| `filter.words` | list of blocked words, matched case-insensitively |
| `filter.words-file` | optional extra word list file, one word per line, `#` starts a comment; path relative to the plugin folder |
| `filter.patterns` | list of blocked regex patterns |
| `chat-clear-lines` | how many blank lines `/voxen chatclear` sends |

In `censor` mode, players with `voxen.filter.toggle` can run `/filter` to see the original, uncensored messages. `block` mode rejects messages before they exist, so there is nothing to reveal.

### Private messages (`modules/private-messages.yml`)

`sender-format`, `receiver-format` and `spy-format` are MiniMessage strings with `<player>` (sender), `<target>` and `<message>`. Social spy (`/voxen spy`, permission `voxen.socialspy`) shows other players' conversations; `notify-monitored: true` warns both sides that someone watched. The `sound` section plays on message receipt. Players can refuse private messages with `/voxen pm`.

### Parties (`modules/party.yml`)

`enabled` turns the whole system off (the `/party` command disappears after a restart), `max-members` caps the size, `invite-expiry` is how long an invitation stays valid. Party chat goes through the `party` channel or the `#` quick prefix.

### MiniMessage tags (`modules/minimessage-tags.yml`)

Controls which formatting players may use in messages. `unauthorized-mode` decides what happens to tags a player is not allowed to use: `escape` shows them as plain text, `strip` removes them.

When a plugin that re-parses chat text is installed (Nexo, Oraxen), `escape` is not safe: the escaped tag survives as plain text and that plugin renders it anyway. Voxen detects these plugins and switches to `strip` automatically, logging a notice on startup. Messages sent to other servers are always rendered with `strip` semantics for unauthorized tags, regardless of `unauthorized-mode`, because the sending server cannot know what re-parses chat on the receiving side.

`legacy.enabled` translates `&c`, `&#ff0000` and `&l` style codes for players holding the matching `voxen.chat.legacy.*` permission.

Under `tags:` every built-in tag can be disabled, given a custom permission, aliases and `blocked-params` (regex list checked against tag arguments). The `click` tag additionally takes per action permissions under `actions:`.

Under `custom-tags:` you can gate tags rendered by other plugins, for example Nexo glyphs:

```yaml
custom-tags:
  glyph:
    enabled: true
    permission: voxen.chat.tag.glyph
```

The base permission allows every argument; `voxen.chat.tag.glyph.smile` allows only `<glyph:smile>`. Voxen does not render these tags itself, it only decides whether they stay in the message. `strip` mode is safer here, because an escaped tag may still be picked up by the plugin that renders it.

## Messages and languages

Every player-visible text lives in `messages/<language>.yml`; `en_US` and `pl_PL` ship by default. To add a language, copy one of them under a new name, e.g. `de_DE.yml`. Players get the language matching their client settings when the file exists, the server fallback otherwise, and can pick one manually with `/lang <language>` or go back to automatic with `/lang auto`.

## Commands

Names below are defaults; see [config.yml](#configyml) for renaming.

### Players

| Command | Description | Permission |
|---------|-------------|------------|
| `/msg <player> <text>` (`/tell`, `/whisper`, `/w`) | private message | `voxen.pm.send` |
| `/r <text>` (`/reply`) | reply to the last conversation | `voxen.pm.send` |
| `/channel join\|leave\|set\|list` (`/ch`) | manage channels | per channel |
| `/ignore <player>` | toggle ignoring a player | `voxen.ignore` |
| `/ignorelist` | list ignored players | `voxen.ignore` |
| `/party create\|invite\|accept\|deny\|leave\|kick\|transfer\|disband\|chat\|list` | party system | `voxen.party` |
| `/chattoggle` | hide or show the chat | everyone |
| `/lang <language\|auto>` | choose the message language | everyone |
| `/nick <nickname\|reset>` | manage your nickname | `voxen.nick` |
| `/nick <nickname> <player>` | manage someone's nickname | `voxen.nick.others` |
| `/filter` | see chat without the word filter | `voxen.filter.toggle` |

### Staff (`/voxen ...`)

| Subcommand | Description | Permission |
|------------|-------------|------------|
| `reload` | reload the configuration | `voxen.admin` |
| `status` | show storage, network and hook status | `voxen.admin` |
| `mute <player\|uuid> [time] [channel\|all] [reason]` | mute a player | `voxen.mod.mute` |
| `unmute <player\|uuid> [channel\|all]` | lift a mute | `voxen.mod.mute` |
| `mutes` | list active mutes | `voxen.mod.mute` |
| `muteinfo <player\|uuid>` | check one player's mutes | `voxen.mod.mute` |
| `mutechat` | mute the whole chat | `voxen.mod.mutechat` |
| `mutechannel <channel>` | mute one channel | `voxen.mod.mutechannel` |
| `chatclear [player]` | clear the chat | `voxen.mod.chatclear` |
| `spy` | toggle social spy | `voxen.socialspy` |
| `mentions` / `pm` | personal toggles | everyone |

Mute durations look like `10m`, `2h`, `7d` or `permanent`. Moderation commands accept a raw UUID instead of a name, which works for players who never joined this server.

## Permissions

### Player features (default: everyone)

| Permission | What it allows |
|-----------|----------------|
| `voxen.party` | using the party system |
| `voxen.ignore` | ignoring other players |
| `voxen.pm.send` | sending private messages |
| `voxen.chat.mention` | mentioning players with `@name` |
| `voxen.chat.tag.item` | sharing held and worn items in chat |

### Staff and personal toggles (default: OP)

| Permission | What it allows |
|-----------|----------------|
| `voxen.admin` | `/voxen reload` and `/voxen status` |
| `voxen.mod.mute` | muting and unmuting players |
| `voxen.mod.mutechat` | muting the whole chat |
| `voxen.mod.mutechannel` | muting single channels |
| `voxen.mod.chatclear` | clearing the chat |
| `voxen.mod.chatclear.exempt` | keeps chat visible during a global clear |
| `voxen.mute.exempt` | cannot be muted |
| `voxen.socialspy` | `/voxen spy`, viewing private messages |
| `voxen.filter.toggle` | `/filter`, seeing chat without the word filter |
| `voxen.nick` | setting your own nickname |
| `voxen.nick.others` | setting other players' nicknames |
| `voxen.chat.papi` | PlaceholderAPI placeholders parsed in own chat messages (`voxen.chat.papi.<placeholder>` for single ones) |
| `voxen.chat.miniplaceholders` | MiniPlaceholders tags parsed in own chat messages (`voxen.chat.miniplaceholders.<tag>` for single ones) |

### Bypasses (default: OP)

| Permission | What it skips |
|-----------|---------------|
| `voxen.bypass.cooldown` | chat cooldowns |
| `voxen.bypass.spam` | the repeated message check |
| `voxen.bypass.filter` | the blocked word filter |
| `voxen.bypass.mutechat` | the global chat mute |
| `voxen.bypass.mutechannel` | channel mutes |
| `voxen.bypass.ignore` | messages reach players who ignore the sender |
| `voxen.bypass.pmtoggle` | private messages reach players who disabled them |
| `voxen.bypass.item-cooldown` | the item share cooldown |
| `voxen.bypass.mention-cooldown` | the mention cooldown |

### Colors and formatting (default: OP)

Regular players cannot use any formatting until it is granted. Unauthorized tags are shown as plain text or removed, depending on `unauthorized-mode` in `modules/minimessage-tags.yml`.

| Permission | What it unlocks |
|-----------|-----------------|
| `voxen.chat.tag.color` | named colors: `<red>`, `<gold>`, `<color:red>` |
| `voxen.chat.tag.hex` | hex colors: `<#ff0000>`, `<color:#ff0000>` |
| `voxen.chat.tag.gradient` | `<gradient:...>` |
| `voxen.chat.tag.rainbow` | `<rainbow>` |
| `voxen.chat.tag.transition` | `<transition:...>` color transitions |
| `voxen.chat.tag.pride` | `<pride>` flag gradients |
| `voxen.chat.tag.keybind` | `<key:...>` client keybind names |
| `voxen.chat.tag.font` | `<font:...>` (disabled by default, needs a resource pack) |
| `voxen.chat.tag.newline` | `<newline>` line breaks (disabled by default, fake message risk) |
| `voxen.chat.tag.bold` | `<bold>` |
| `voxen.chat.tag.italic` | `<italic>` |
| `voxen.chat.tag.underlined` | `<underlined>` |
| `voxen.chat.tag.strikethrough` | `<strikethrough>` |
| `voxen.chat.tag.obfuscated` | `<obfuscated>` |
| `voxen.chat.tag.hover` | `<hover:...>` |
| `voxen.chat.tag.click` | `<click:...>`, each action also has its own node, e.g. `voxen.chat.tag.click.run-command` |
| `voxen.chat.tag.insertion` | `<insertion:...>` |
| `voxen.chat.tag.shadow` | `<shadow:#RRGGBB>` text shadows |
| `voxen.chat.tag.sprite` | `<sprite:...>` item/mob icons (needs MiniMessage 5+ on the server) |
| `voxen.chat.tag.head` | `<head:...>` player heads (needs MiniMessage 5+ on the server) |
| `voxen.chat.tag.translatable` | `<lang:...>` translatable keys (disabled by default) |
| `voxen.chat.tag.fallback` | `<lang_or:...>` translatable with fallback (disabled by default) |
| `voxen.chat.tag.selector` | `<selector:...>` entity selectors (disabled by default) |
| `voxen.chat.tag.score` | `<score:...>` scoreboard values (disabled by default) |
| `voxen.chat.tag.nbt` | `<nbt:...>` NBT data (disabled by default) |
| `voxen.chat.tag.reset` | `<reset>` |
| `voxen.chat.tag.*` | everything above at once |
| `voxen.chat.tag.<custom>` | a tag defined under `custom-tags`, all arguments |
| `voxen.chat.tag.<custom>.<argument>` | a single argument of a custom tag |
| `voxen.chat.legacy.color` | `&c` style color codes |
| `voxen.chat.legacy.hex` | `&#ff0000` style hex codes |
| `voxen.chat.legacy.format` | `&l`, `&o` style format codes |
| `voxen.chat.legacy.*` | all legacy codes at once |

The `<selector>`, `<score>`, `<nbt>`, `<lang>` and `<lang_or>` tags are disabled by default: they read server-side data or let players imitate other messages. Enable them in `modules/minimessage-tags.yml` only for ranks you trust.

Tag arguments can be permission-gated as well: the tag's base node allows every argument, and the base node plus the arguments (with `:` replaced by `.`) allows one combination. For example `voxen.chat.tag.gradient.red.blue` lets a player use exactly `<gradient:red:blue>` and nothing else. If a message contains both a permitted and an unpermitted use of the same tag, the tag stays unparsed in that whole message.

Argument nodes also work as denials: setting one explicitly to `false` (e.g. `voxen.chat.tag.color.red: false` in LuckPerms) blocks that use even for players holding the base node or the wildcard. For colors this covers both forms, `<color:red>` and `<red>`.

To grant formatting selectively, use the individual nodes rather than the wildcard. The wildcard is checked directly in code, so negating a single node in a permission plugin has no effect on players who hold `voxen.chat.tag.*`. A tag can also be given a different permission or disabled for everyone in `modules/minimessage-tags.yml`.

### Channels and formats

Channel access nodes (`read`, `write`, `join`, `manage`) are defined per channel in its `channels/*.yml` file; the default staff channel uses `voxen.channel.staff`. Formats under `group-formats` are matched by permission group name or by `voxen.chat.format.<key>`.

## Cross-server chat

1. In `integrations.yml` set `network.transport` to `redis`, `nats` or `rabbitmq` and fill in the connection details for that transport.
2. Give every server a unique `server-id`.
3. Set `cross-server: true` in each channel that should be shared. The default `server` channel already has it.

`transport: none` keeps the plugin in single-server mode. `reconnect-seconds` and `timeout-millis` control connection recovery. Messages arriving from other servers render with the channel's `external-format` when set.

Mentions work across servers: `@name` highlights and notifies the mentioned player on whichever server they are on, respecting their mention toggle. The sender's mention permission and cooldown are checked on the sending server.

All formatting permissions are applied on the sending server: the message is rendered there and travels as final MiniMessage text, so what a player may use is decided by their permissions on the server they wrote on. Servers on the network can run different Paper builds.

Private messages work across servers too: `/msg <player>` reaches the player on whichever server they are on, `/r` replies back across servers, and the target's PM toggle and ignore list are respected on their server. Tab completion only suggests local players, but any name can be typed. If nobody on the network has the player, the sender gets the not-found message after `timeout-millis`.

The `/r` target is stored in player data, so with a shared MySQL database it follows players between servers and survives relogs. On per-server SQLite each server keeps its own reply target.

Social spy is network wide: every private message is broadcast to the other servers as a spy event, and each server shows it to its local spies using its own `spy-format`. This also means PM contents travel over the broker for every message, so keep the broker private to your network.

Mutes are network wide as well: `/voxen mute` and `unmute` take effect on every server immediately. Controlled by `network.sync-mutes` in `integrations.yml` (enabled by default). With shared MySQL, mutes also survive restarts on all servers; with per-server SQLite each server keeps its own copy from the moment it received the broadcast.

## Storage

`storage.yml` selects where player data (channels, toggles, nicknames, ignores, mutes, parties) is kept:

* `type: sqlite` needs no configuration; the database file lives in the plugin folder
* `type: mysql` (or MariaDB) uses `host`, `port`, `database`, `username`, `password`, `table-prefix` and `pool-size`

On a multi-server network use MySQL so player settings follow players between servers. Schema updates run automatically on startup.

## PlaceholderAPI

Both directions work when PlaceholderAPI is installed:

* any `%papi%` placeholder can be used inside chat formats
* other plugins can read Voxen state through these placeholders:

| Placeholder | Value |
|-------------|-------|
| `%voxen_channel%` | active channel id |
| `%voxen_channel_display%` | active channel display name |
| `%voxen_muted%` | `true`/`false`, global mute state |
| `%voxen_party%` | party name or empty |
| `%voxen_party_leader%` | party leader's name or empty |
| `%voxen_language%` | chosen language or `auto` |
| `%voxen_mentions%` / `%voxen_pm%` / `%voxen_chat%` | personal toggle states |

## Developer API

Add a dependency on the `:api` module (or the plugin jar) with `compileOnly`, and `depend`/`softdepend` on `Voxen` in your plugin.yml.

Entry points:

```java
// static facade
Collection<ChannelInfo> channels = VoxenApi.channels();

// or through Bukkit's ServicesManager
VoxenService voxen = getServer().getServicesManager().load(VoxenService.class);
```

What the API offers:

* reading channels (`channels`, `channel`, `activeChannel`) as immutable `ChannelInfo` snapshots
* sending chat as a player (`sendChannelMessage`, full pipeline: permissions, mutes, filter) or broadcasting a raw component (`broadcastToChannel`)
* mute and ignore checks (`isMuted`, `isIgnoring`)
* sending private messages (`sendPrivateMessage`, full pipeline: PM toggle, ignores, tag permissions)
* reading and setting nicknames (`nickname`, `setNickname`; config length limits apply, permission checks do not)
* reading party membership (`party`) as an immutable `PartyInfo` snapshot
* registering custom chat format placeholders (`registerPlaceholder("level", ...)` makes `<level>` usable in formats)
* registering runtime channels owned by your plugin (`registerChannel`), optionally with a custom recipient list (`RecipientProvider`)
* overriding recipients of any channel (`registerRecipients`)

Events:

* `ChatMessageSendEvent`: before delivery; cancellable, `content` is mutable
* `ChatMessageDeliveredEvent`: after delivery, with the final component and recipient list

Both fire on the thread the message came from: asynchronously for regular chat, synchronously when a command or the API triggered it. Check `isAsynchronous()` in your listener before touching the world.

Full behavior notes are in the KDoc of `VoxenApi`.

## Author

Naimad (Discord: 4g0)
