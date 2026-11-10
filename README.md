# PartyFiller

Headless Hypixel SkyBlock dungeon party filler. A bot account waits in the Dungeon Hub, accepts
invites from your accounts, and at 5/5 runs `/party warp` → `/party promote <you>` → `/party leave`.

Runs as a real Minecraft client with no window via [HeadlessMC](https://github.com/headlesshq/headlessmc).

## Setup

1. Java 25 on `PATH`, or its full `java.exe` path in `java-path.txt` next to `start.bat`.
2. Minecraft 26.1.2 + Fabric in `.minecraft`, with these in `.minecraft/mods`:
   Fabric API, Fabric Language Kotlin, PackDisabler, and `partyfiller` (build below).
   NoammAddons is optional (used for auto-kick).
3. Run `start.bat` once to download HeadlessMC, close it, then in `HeadlessMC/` run
   `java -jar headlessmc-launcher-wrapper-*.jar`, type `login`, sign in as the bot, then `quit`.
4. Copy `partyfiller/partyfiller.properties.example` to `.minecraft/config/partyfiller.properties`
   and set `owners`.
5. Run `start.bat`.

## Build

```
cd partyfiller
gradle build
```

## Commands

Whisper the bot or use party chat: `!pf help`.
