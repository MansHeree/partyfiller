package com.filler;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headless-friendly Hypixel dungeon party filler + control layer for NoammAddons' auto-kick.
 *
 * PartyFiller handles navigation to the Dungeon Hub, filling, and the 5/5 hand-off.
 * NoammAddons ("Party Finder" feature) does the per-requirement auto-kick using its
 * rate-limit-free profile API. Owners set the kick requirements live, in-game, and
 * PartyFiller pushes them into NoammAddons via reflection (no NoammAddons rebuild needed).
 *
 * Commands (whisper the bot, or party chat), all under the "!pf" prefix:
 *   !pf / !pf help        list commands
 *   !pf start / stop      enable / pause the filler
 *   !pf status            report state + current kick requirements
 *   !pf fire              force the hand-off now
 *   !pf come              re-navigate to the Dungeon Hub
 *   !pf f1-f7 / m1-m7     kick for this floor (m = Master Mode) and turn auto-kick on
 *   !pf time 2:30         max S+ time (mm:ss or seconds)
 *   !pf secrets 30        min secrets, in thousands
 *   !pf off               turn auto-kick off (keeps the filler running)
 */
public class PartyFiller implements ClientModInitializer {

    // ---- config (config/partyfiller.properties) ----
    private List<String> owners = Arrays.asList();   // set in config/partyfiller.properties
    private int trigger = 5;
    private boolean warpBeforeHandoff = true;
    private boolean enabled = true;

    private static final long TICKS_PER_SECOND = 20L;

    // ---- patterns ----
    private static final Pattern INVITE = Pattern.compile("(?:\\[[^\\]]*] )?(\\w{1,16}) has invited you to join their party!");
    private static final Pattern LIST_LINE = Pattern.compile("^Party (?:Leader|Moderators|Members): (.+)$");
    private static final Pattern MEMBER_TOKEN = Pattern.compile("(?:\\[[^\\]]*]\\s*)?(\\w{2,16})\\s*$");
    private static final Pattern MEMBERSHIP = Pattern.compile("(joined the party\\.|has left the party\\.|has been removed from the party\\.|joined the dungeon group!|because they disconnected\\.|The party was transferred)");
    private static final Pattern WHISPER = Pattern.compile("^From (?:\\[[^\\]]*] )?(\\w{1,16}): (.+)$");
    private static final Pattern PARTY_CHAT = Pattern.compile("^Party > (?:\\[[^\\]]*] )?(\\w{1,16}): (.+)$");
    // Hypixel's reliable "party is 5/5" signal - no need to poll /party list.
    private static final Pattern PARTY_FULL = Pattern.compile("dungeon group is full", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_JOIN = Pattern.compile("^(?:\\[[^\\]]*] )?(\\w{1,16}) joined the party\\.");
    private static final Pattern PARTY_LEFT = Pattern.compile("You left the party|You are not in a party|party was disbanded", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOOR_CMD = Pattern.compile("^([fm])([1-7])$");
    private static final Pattern COLOR = Pattern.compile("§[0-9A-Fa-fK-Rk-r]");
    // Genuine server death where the bot can't recover the party in place (so it hands off).
    // Transient displacements (limbo, AFK-transfer, kick-to-lobby) are NOT here - navigation
    // just walks the bot back to the Dungeon Hub instead.
    private static final Pattern EVACUATE = Pattern.compile("server is (?:closing|restarting|being restarted)", Pattern.CASE_INSENSITIVE);
    private static final String BULLET = "●";

    // NoammAddons feature/setting names
    private static final String NA_FEATURE = "com.github.noamm9.features.impl.dungeon.PartyFinder";
    private static final String NA_PARTYHELPER = "com.github.noamm9.features.impl.general.PartyHelper";
    private static final String S_AUTOKICK = "Auto Kick";
    private static final String S_FLOOR = "Floor";
    private static final String S_MASTER = "Master Mode";
    private static final String S_SECONDS = "Maximum Seconds";
    private static final String S_SECRETS = "Minimum Secrets";

    // ---- state ----
    private long tick = 0;
    private long nextLocrawAt = 0;
    private long nextTravelAt = 0;
    private boolean firing = false;
    private String activeOwner = null;   // the owner currently in the party = hand-off target
    private long lastEvacTick = -1000;

    private String server = null;   // from /locraw ("limbo" when stuck in limbo)
    private String gametype = null;
    private String map = null;

    private final TreeMap<Long, List<Runnable>> schedule = new TreeMap<>();

    // anti-AFK: each action runs on its own randomized schedule so nothing looks mechanical.
    private final java.util.Random rng = new java.util.Random();
    private boolean afkInit = false;
    private long nextJumpTick = 0;
    private long jumpReleaseTick = -1;
    private long nextLookTick = 0;
    private int lookDur = 0;
    private int lookElapsed = 0;
    private float lookStartYaw = 0f, lookDeltaYaw = 0f;
    private float lookStartPitch = 0f, lookDeltaPitch = 0f;
    private float lookSkew = 1f;
    private long nextSwingTick = 0;

    // All outgoing commands go through this queue, released 1/second, so nothing ever trips
    // Hypixel's "sending commands too fast".
    private final ArrayDeque<String> cmdQueue = new ArrayDeque<>();
    private long lastSendTick = -100;
    private static final long CMD_GAP_TICKS = 20; // 1 second

    @Override
    public void onInitializeClient() {
        loadConfig();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChat(message.getString()));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    // ---------------- config file ----------------
    private void loadConfig() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir();
            Path file = dir.resolve("partyfiller.properties");
            Properties p = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) { p.load(in); }
                String o = p.getProperty("owners");
                if (o != null && !o.isBlank()) {
                    List<String> parsed = new ArrayList<>();
                    for (String s : o.split(",")) if (!s.trim().isEmpty()) parsed.add(s.trim());
                    if (!parsed.isEmpty()) owners = parsed;
                }
                trigger = parseInt(p.getProperty("trigger"), trigger);
                warpBeforeHandoff = Boolean.parseBoolean(p.getProperty("warp", String.valueOf(warpBeforeHandoff)));
                enabled = Boolean.parseBoolean(p.getProperty("enabled", String.valueOf(enabled)));
            } else {
                p.setProperty("owners", String.join(", ", owners));
                p.setProperty("trigger", String.valueOf(trigger));
                p.setProperty("warp", String.valueOf(warpBeforeHandoff));
                p.setProperty("enabled", String.valueOf(enabled));
                Files.createDirectories(dir);
                try (OutputStream out = Files.newOutputStream(file)) {
                    p.store(out, "PartyFiller config - owners = accounts whose party invites are auto-accepted (auto-join whitelist) and the hand-off target; commands are open to anyone; trigger = party size to hand off at");
                }
            }
        } catch (IOException ignored) {}
    }

    private int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private boolean isOwner(String name) {
        for (String o : owners) if (o.equalsIgnoreCase(name)) return true;
        return false;
    }

    // ---------------- NoammAddons reflection bridge ----------------
    private Object naFeature() { return naFeatureOf(NA_FEATURE); }

    private Object naFeatureOf(String className) {
        try { return Class.forName(className).getField("INSTANCE").get(null); }
        catch (Throwable t) { return null; }
    }

    /** Sets every entry of a MultiCheckbox setting's map to true (mutates in place). Returns count. */
    @SuppressWarnings("unchecked")
    private int naEnableAllInMap(Object f, String settingName) {
        try {
            Object setting = naSetting(f, settingName);
            if (setting == null) return 0;
            Object val = setting.getClass().getMethod("getValue").invoke(setting);
            if (val instanceof java.util.Map) {
                java.util.Map<Object, Object> m = (java.util.Map<Object, Object>) val;
                int count = 0;
                for (Object k : new ArrayList<>(m.keySet())) { m.put(k, Boolean.TRUE); count++; }
                return count;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private boolean naEnableFeature(Object f) {
        try {
            // Feature.enabled is a Kotlin @JvmField (raw public field, no getEnabled()).
            java.lang.reflect.Field ef = f.getClass().getField("enabled");
            if (!ef.getBoolean(f)) f.getClass().getMethod("toggle").invoke(f); // toggle() registers its listeners
            return true;
        } catch (Throwable t) { return false; }
    }

    private Object naSetting(Object f, String name) {
        try { return f.getClass().getMethod("getSettingByName", String.class).invoke(f, name); }
        catch (Throwable t) { return null; }
    }

    private boolean naSet(Object f, String name, Object value) {
        try {
            Object setting = naSetting(f, name);
            if (setting == null) return false;
            for (Method m : setting.getClass().getMethods()) {
                if (m.getName().equals("setValue") && m.getParameterCount() == 1) {
                    m.invoke(setting, value);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    private Object naGet(Object f, String name) {
        try {
            Object setting = naSetting(f, name);
            if (setting == null) return null;
            return setting.getClass().getMethod("getValue").invoke(setting);
        } catch (Throwable t) { return null; }
    }

    /** Persist NoammAddons config so !pf changes survive a restart (it normally only
     *  saves when its clickgui closes, which never happens headless). */
    private void naSave() {
        try {
            Object cfg = Class.forName("com.github.noamm9.config.Config").getField("INSTANCE").get(null);
            cfg.getClass().getMethod("save").invoke(cfg);
        } catch (Throwable ignored) {}
    }

    private int asInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        return def;
    }

    // ---------------- scheduling / sending ----------------
    private long secs(double s) { return Math.round(s * TICKS_PER_SECOND); }

    private void schedule(long delayTicks, Runnable r) {
        schedule.computeIfAbsent(tick + delayTicks, k -> new ArrayList<>()).add(r);
    }

    /** Queues a command; the queue is drained one per second in onTick. */
    private void cmd(String command) {
        cmdQueue.add(command.replaceFirst("^/", ""));
    }

    private long randTicks(double minSec, double maxSec) {
        return secs(minSec) + rng.nextInt((int) Math.max(1, secs(maxSec - minSec)));
    }

    /**
     * Human-ish anti-AFK: jump, look, and swing each run on their own randomized interval
     * (so they don't fire together), and the looking is eased smoothly across many ticks
     * instead of snapping. Jump hold duration and all intervals are randomized.
     */
    private void antiAfk(Minecraft mc) {
        if (!afkInit) {
            afkInit = true;
            nextJumpTick = tick + randTicks(2, 8);
            nextLookTick = tick + randTicks(0, 5);
            nextSwingTick = tick + randTicks(3, 15);
        }

        // JUMP - random interval, random hold length (real key press)
        if (tick >= nextJumpTick) {
            mc.options.keyJump.setDown(true);
            jumpReleaseTick = tick + 2 + rng.nextInt(6);   // hold 2-7 ticks
            nextJumpTick = tick + randTicks(9, 24);         // next jump in 9-24s
        }
        if (tick == jumpReleaseTick) mc.options.keyJump.setDown(false);

        // LOOK - eased (non-linear) rotation toward a random target. Uses smootherstep so it
        // accelerates and decelerates like a real hand on a mousepad, with a random per-move
        // skew (asymmetric accel) and per-tick jitter on top.
        if (lookElapsed < lookDur) {
            lookElapsed++;
            float t = (float) lookElapsed / lookDur;
            float e = ease(t, lookSkew);
            float jitter = (rng.nextFloat() - 0.5f) * 0.4f;
            mc.player.setYRot(lookStartYaw + lookDeltaYaw * e + jitter);
            mc.player.setXRot(clampPitch(lookStartPitch + lookDeltaPitch * e + jitter * 0.5f));
        } else if (tick >= nextLookTick) {
            lookStartYaw = mc.player.getYRot();
            lookStartPitch = mc.player.getXRot();
            lookDeltaYaw = rng.nextFloat() * 140f - 70f;    // target -70..70 deg
            lookDeltaPitch = rng.nextFloat() * 40f - 20f;   // target -20..20 deg
            lookDur = (int) randTicks(0.7, 2.2);            // 14-44 ticks
            lookElapsed = 0;
            lookSkew = 0.7f + rng.nextFloat() * 0.9f;       // 0.7-1.6 curve asymmetry
            nextLookTick = tick + randTicks(4, 14) + lookDur;
        }

        // SWING (click) - its own random interval
        if (tick >= nextSwingTick) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            nextSwingTick = tick + randTicks(8, 35);        // click every 8-43s
        }
    }

    private float clampPitch(float p) {
        return Math.max(-90f, Math.min(90f, p));
    }

    /** Smootherstep (6t^5-15t^4+10t^3): eases in and out with near-zero speed at both ends,
     *  like friction on a mousepad. `skew` (~0.7-1.6) biases the accel to be front/back-loaded. */
    private float ease(float t, float skew) {
        float s = t * t * t * (t * (t * 6f - 15f) + 10f);
        return (float) Math.pow(s, skew);
    }

    private void drainCommands(Minecraft mc) {
        if (cmdQueue.isEmpty() || tick - lastSendTick < CMD_GAP_TICKS) return;
        String c = cmdQueue.poll();
        if (mc.player != null && c != null) {
            mc.player.connection.sendCommand(c);
            lastSendTick = tick;
        }
    }

    private void reply(String to, String msg) { cmd("msg " + to + " " + msg); }

    // ---------------- main loop ----------------
    private void onTick(Minecraft mc) {
        tick++;
        while (!schedule.isEmpty() && schedule.firstKey() <= tick) {
            for (Runnable r : schedule.pollFirstEntry().getValue()) {
                try { r.run(); } catch (Exception ignored) {}
            }
        }
        if (mc.player == null) return;

        drainCommands(mc); // release at most one queued command per second

        antiAfk(mc);

        if (firing || !enabled) return;

        if (tick >= nextLocrawAt) {
            nextLocrawAt = tick + secs(10);
            cmd("locraw");
        }
        if (tick >= nextTravelAt) {
            if ("limbo".equalsIgnoreCase(server)) {
                // Stuck in Limbo (no gametype in locraw): /lobby escapes it, then /play sb next cycle.
                nextTravelAt = tick + secs(8);
                cmd("lobby");
            } else if ("SKYBLOCK".equals(gametype)) {
                if (map != null && !map.equalsIgnoreCase("Dungeon Hub")) {
                    nextTravelAt = tick + secs(10);
                    cmd("warp dungeon_hub");
                }
            } else if (gametype != null) {
                nextTravelAt = tick + secs(10);
                cmd("play sb");
            }
        }
    }

    // ---------------- chat ----------------
    private void onChat(String raw) {
        if (raw == null || raw.isEmpty()) return;
        // Hypixel chat carries legacy color codes; strip them or every regex below fails.
        String msg = COLOR.matcher(raw).replaceAll("");
        String trimmed = msg.trim();

        if (trimmed.startsWith("{") && trimmed.contains("\"server\"")) {
            server = extract(trimmed, "server");
            gametype = extract(trimmed, "gametype");
            map = extract(trimmed, "map");
            return;
        }

        // Party is FULL (5/5) -> hand it off automatically. This is Hypixel's own reliable
        // signal, so we don't poll /party list at all.
        if (PARTY_FULL.matcher(msg).find() && !firing && enabled) {
            log("party is full -> auto hand-off");
            fireHandoff(handoffTarget());
            return;
        }

        // Server evacuation (AFK transfer / kick-to-lobby / restart): warp + promote owner,
        // stay in party. Debounced so a burst won't re-fire.
        if (EVACUATE.matcher(msg).find() && tick - lastEvacTick > secs(30)) {
            lastEvacTick = tick;
            schedule(secs(4), () -> handleEvac(handoffTarget()));
            return;
        }

        // Owner commands (whisper or party chat). Seeing an owner in party chat also tells us
        // who's in the party to hand off to.
        Matcher cw = WHISPER.matcher(msg);
        Matcher cp = PARTY_CHAT.matcher(msg);
        if (cw.find()) {
            if (handleCommand(cw.group(1), cw.group(2), true)) return;
        } else if (cp.find()) {
            if (isOwner(cp.group(1))) activeOwner = cp.group(1);
            if (handleCommand(cp.group(1), cp.group(2), false)) return;
        }

        // Auto-accept invites from owners.
        Matcher mi = INVITE.matcher(msg);
        if (mi.find()) {
            String from = mi.group(1);
            if (enabled && (owners.isEmpty() || isOwner(from))) {
                activeOwner = from;
                firing = false;
                schedule(secs(0.5), () -> cmd("party accept " + from));
            }
            return;
        }

        // Track an owner joining the party -> that's our hand-off target.
        Matcher pj = PARTY_JOIN.matcher(msg);
        if (pj.find()) {
            if (isOwner(pj.group(1))) activeOwner = pj.group(1);
            return;
        }

        // Reset tracking when we leave / the party disbands.
        if (PARTY_LEFT.matcher(msg).find()) {
            activeOwner = null;
            firing = false;
        }
    }

    /** Who to promote on a hand-off: the owner currently in the party, else the first configured. */
    private String handoffTarget() {
        if (activeOwner != null) return activeOwner;
        return owners.isEmpty() ? null : owners.get(0);
    }

    /**
     * All PartyFiller commands live under the "!pf" prefix so they never collide with
     * NoammAddons' own party bot (which owns bare !f/!m/!time/!warp/!kick/!help).
     * Returns true if the text was an owner command we consumed.
     */
    private boolean handleCommand(String from, String text, boolean whisper) {
        // Commands are open to anyone (per user: only auto-join is whitelisted).
        String t = text.trim();
        if (t.length() < 3 || !t.substring(0, 3).equalsIgnoreCase("!pf")) return false;
        String rest = t.substring(3).trim();
        String low = rest.toLowerCase();

        if (rest.isEmpty() || low.equals("help")) {
            return respond(from, whisper, "PartyFiller: !pf start|stop|status|fire|come|partycmds | !pf f1-f7 / m1-m7 | !pf time m:ss | !pf secrets N | !pf off");
        }

        // floor: !pf f7 / !pf m7  -> sets NoammAddons auto-kick floor + master, turns kick on
        Matcher fm = FLOOR_CMD.matcher(low);
        if (fm.matches()) {
            boolean master = fm.group(1).equals("m");
            int floor = Integer.parseInt(fm.group(2));
            Object f = naFeature();
            if (f == null) return respond(from, whisper, "NoammAddons not loaded - can't set kick floor.");
            naEnableFeature(f);
            naSet(f, S_MASTER, master);
            naSet(f, S_FLOOR, floor - 1);
            naSet(f, S_AUTOKICK, Boolean.TRUE);
            naSave();
            return respond(from, whisper, "Auto-kick ON for " + (master ? "M" : "F") + floor + ".");
        }

        if (low.startsWith("time")) {
            Integer sec = parseTime(rest.substring(4).trim());
            if (sec == null) return respond(from, whisper, "Use !pf time m:ss  (e.g. !pf time 2:30).");
            int clamped = Math.max(60, Math.min(480, sec));
            Object f = naFeature();
            if (f == null) return respond(from, whisper, "NoammAddons not loaded.");
            naEnableFeature(f);
            naSet(f, S_SECONDS, clamped);
            naSet(f, S_AUTOKICK, Boolean.TRUE);
            naSave();
            return respond(from, whisper, "Max S+ time set to " + fmtTime(clamped) + (clamped != sec ? " (clamped 1:00-8:00)" : "") + ".");
        }

        if (low.startsWith("secrets")) {
            Integer n = parseIntOrNull(rest.substring(7).trim());
            if (n == null) return respond(from, whisper, "Use !pf secrets N  (thousands, e.g. !pf secrets 30).");
            int clamped = Math.max(0, Math.min(200, n));
            Object f = naFeature();
            if (f == null) return respond(from, whisper, "NoammAddons not loaded.");
            naEnableFeature(f);
            naSet(f, S_SECRETS, clamped);
            naSet(f, S_AUTOKICK, Boolean.TRUE);
            naSave();
            return respond(from, whisper, "Min secrets set to " + clamped + "k.");
        }

        switch (low) {
            case "start":
                enabled = true;
                return respond(from, whisper, "PartyFiller started.");
            case "stop":
                enabled = false;
                return respond(from, whisper, "PartyFiller stopped.");
            case "off": {
                Object f = naFeature();
                if (f != null) { naSet(f, S_AUTOKICK, Boolean.FALSE); naSave(); }
                return respond(from, whisper, "Auto-kick off.");
            }
            case "status":
                return respond(from, whisper, status());
            case "fire":
                fireHandoff(from);
                return respond(from, whisper, "Handing the party over to " + from + "...");
            case "come":
                map = null; gametype = null; nextTravelAt = 0;
                return respond(from, whisper, "Re-navigating to Dungeon Hub.");
            case "partycmds":
            case "allcmds": {
                Object ph = naFeatureOf(NA_PARTYHELPER);
                if (ph == null) return respond(from, whisper, "NoammAddons PartyHelper not found.");
                naEnableFeature(ph);
                naSet(ph, "Party Commands", Boolean.TRUE);
                int n = naEnableAllInMap(ph, "Enabled Commands");
                naSave();
                return respond(from, whisper, "Enabled all " + n + " NoammAddons party commands.");
            }
            default:
                return respond(from, whisper, "Unknown: !pf " + rest + "  (try !pf help)");
        }
    }

    private boolean respond(String from, boolean whisper, String msg) {
        if (whisper) reply(from, msg); else cmd("pc " + msg);
        return true;
    }

    private String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("filler=").append(enabled ? "on" : "off");
        sb.append(" loc=").append(map == null ? String.valueOf(gametype) : map);
        sb.append(" firing=").append(firing);
        Object f = naFeature();
        if (f == null) sb.append(" | autokick=NoammAddons-not-loaded");
        else {
            Object ak = naGet(f, S_AUTOKICK);
            boolean on = ak instanceof Boolean && (Boolean) ak;
            sb.append(" | autokick=").append(on ? "on" : "off");
            if (on) {
                boolean master = Boolean.TRUE.equals(naGet(f, S_MASTER));
                int floor = asInt(naGet(f, S_FLOOR), 6) + 1;
                int sec = asInt(naGet(f, S_SECONDS), 400);
                int sec_k = asInt(naGet(f, S_SECRETS), 0);
                sb.append(" ").append(master ? "M" : "F").append(floor)
                  .append(" S+<").append(fmtTime(sec))
                  .append(" secrets>=").append(sec_k).append("k");
            }
        }
        return sb.toString();
    }

    private Integer parseTime(String s) {
        try {
            if (s.contains(":")) {
                String[] p = s.split(":");
                return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
            }
            return Integer.parseInt(s.trim());
        } catch (Exception e) { return null; }
    }

    private Integer parseIntOrNull(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    private String fmtTime(int totalSeconds) {
        int m = totalSeconds / 60, s = totalSeconds % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    private String extract(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private void log(String s) {
        System.out.println("[PartyFiller] " + s);
    }

    /** On server evacuation: warp the party and hand leadership to an owner, but do NOT leave. */
    private void handleEvac(String target) {
        if (firing) return;
        log("evacuation -> warp + promote " + target + " (staying in party)");
        cmd("party warp");                 // warp while we're still leader
        if (target != null) cmd("party transfer " + target);  // transfer leadership; no "party leave"
    }

    /** Enqueue the hand-off; the command queue spaces these 1s apart on its own. */
    private void fireHandoff(String target) {
        firing = true;
        log("hand-off: warp -> transfer " + target + " -> leave");
        if (warpBeforeHandoff) cmd("party warp");
        if (target != null) cmd("party transfer " + target);
        cmd("party leave");
        schedule(secs(8), () -> { firing = false; activeOwner = null; });
    }
}
