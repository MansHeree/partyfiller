# PartyFiller

Headless Hypixel SkyBlock dungeon party filler. A bot account waits in the Dungeon Hub, accepts
invites from your accounts, and at 5/5 runs `/party warp` → `/party promote <you>` → `/party leave`.

Runs as a real Minecraft client with no window via [HeadlessMC](https://github.com/headlesshq/headlessmc).

## Use

Needs a JDK 25 (e.g. [Temurin](https://adoptium.net/)) and git. Then double-click `start.bat`.

The first run sets everything up inside this folder, never touching your normal `.minecraft`:
HeadlessMC, Minecraft 26.1.2 + Fabric, Fabric API, Fabric Language Kotlin, PackDisabler,
and builds PartyFiller (`partyfiller/`) and NoammAddons (from the `26.1.2` branch of
MansHeree/NoammAddons). Later runs only update what changed.

If no account is saved yet it opens HeadlessMC: type `login`, sign in as the **bot** account, then `quit`.

Set your usernames in `instance/config/partyfiller.properties` (`owners=`).

## Commands

Whisper the bot or use party chat: `!pf help`.
