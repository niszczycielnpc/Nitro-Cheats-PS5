# PS5 Nitro Cheats — a Cheat Engine-style memory tool for your phone, built on ps5debug-NG

A native Android app (8.0+, Kotlin + Jetpack Compose) that talks over Wi-Fi to the **ps5debug-NG** payload
running on a PS5 (written against protocol v1.3.2) and lets you:

| Tab | What it does |
|---|---|
| 🔌 **Connect** | console address/port, console discovery on the LAN (UDP 1010), settings, credits |
| 🎮 **Game** | process list + foreground game, **memory map** (module + offset), hex editor (read / write / NOP) |
| 🔍 **Scanner** | first / next scan: every number type, AOB with `??`, unknown initial value, increased / decreased / changed; *turbo* engine when the console has it |
| 📋 **Watch** | watched addresses with live values, editing, **freezing** (background write loop with a notification) |
| 🧭 **Trace** | "what writes to this address?" — hardware watchpoint + console-side instruction decoding → **NOP as a cheat**; static search for references in code without a debugger |
| 🧩 **Cheats** | cheat list with switches, export to **etaHEN SHN / JSON**, upload to the console over FTP or save the file on the phone |

## What is in the package

```
Ps5NitroCheats/
├─ app/      the Android app (Compose): screens + Session.kt (all state; long jobs keep running when you switch tabs)
├─ core/     plain Kotlin/JDK: ps5debug-NG protocol, scanners, tracer, cheat files, FTP, console discovery
│            + 73 JVM tests against a simulated server (./gradlew :core:test)
└─ tools/    relay.py - PC relay for the Trace tab (see below), its tests, a C reference of the auth keystream
```

## Building in Android Studio

1. Android Studio (Ladybug 2024.2 or newer, JDK 17+ - the bundled JBR is enough), **File ▸ Open** → the
   `Ps5NitroCheats` folder (use *Open*, not *New Project*).
2. Wait for the *Gradle sync* (it downloads Android Gradle Plugin 8.7.3, Gradle 8.9, Kotlin 2.0.21 and
   Compose BOM 2024.10.01). If Studio offers to upgrade AGP/Gradle, accepting is fine.
3. Connect your phone (Developer options ▸ USB or wireless debugging) and press **Run ▶**.
   Or without a phone: **Build ▸ Build APK(s)** → `app/build/outputs/apk/debug/app-debug.apk`, copy it to the phone and install it.
4. Core tests from the command line: `./gradlew :core:test` (Windows: `gradlew.bat :core:test`). The
   `communityFilesParse` test checks the SHN reader against community cheat files and is skipped when they are not on disk.

> **Honest note on verification.** `:core` (the whole protocol, scanners, tracer, export, FTP) was compiled and tested
> here - all 73 tests pass - but against a *simulated* server built from ps5debug-NG's PROTOCOL.md and source,
> not against a real console. The `:app` module was **not built with a real Android SDK** (the build
> environment had no access to Google/Maven): it was type-checked with the Kotlin compiler against stand-ins for
> the Android and Compose APIs together with `:core` (types, names and call signatures - no errors), but the
> first real build happens on your machine. If anything fails to build or misbehaves, send the *Build* / *Logcat* output.

## Quick start

1. Load the **ps5debug-NG** payload on the PS5 and start a game.
2. Put the phone on the same Wi-Fi. In the **Connect** tab enter the console IP (default `192.168.101.15`) → **Connect**.
   The app picks the foreground game by itself (**Game** tab ▸ *Use this game* if you need to change it).
3. **Scanner**: type a value (a lives counter, say) → *First scan* → change it in the game → type the new value →
   *Next scan*, until a few addresses are left. Tap a result to send it to the **Watch** list.
4. **Watch**: *Change* writes a new value, the *Freeze* switch keeps it (the phone re-writes it about every 50 ms; the interval is set in Connect).

### Freezing vs. a code patch

ps5debug has no "freeze", so the phone simply writes the same bytes in a loop (that is also how Cheat Engine does it).
It works for values the game does not overwrite too aggressively. **Better and permanent** is disabling the
instruction that changes the value: **Trace** tab → *Trace (debugger)* → *NOP…* → you get a cheat that restores the
original bytes when switched off.

Freezing runs in a foreground service (the notification has a stop action). If Samsung puts the app to sleep, turn off
battery optimisation for it (Settings ▸ Apps ▸ PS5 Nitro Cheats ▸ Battery ▸ Unrestricted).

## The Trace tab and port 755 (important)

After `DEBUG_ATTACH` it is the **console** that connects to the client, on TCP **755** (to the address the command came
from). Android does not let an ordinary app listen on ports below 1024, so there are two ways:

**A. No root - a relay on a PC** (Python 3.8+, no dependencies):

```
python tools\relay.py --console 192.168.101.15 --phone <phone IP>
```

```
phone --744--> PC --744--> PS5        commands
phone <-7755-- PC <--755-- PS5        debugger interrupts
```

In the app (Connect tab): *Console IP address* = the **PC's IP**, *etaHEN FTP address* = the **PS5's IP**, leave the
interrupt port at `7755`. Windows: allow Python through the firewall the first time (private network); ports 744/755
need `sudo` on Linux/macOS. The relay accepts the connection on 755 only from the PS5's address (`--allow-any` turns that off).

**B. With root:** set the *interrupt port* to `755`.

Without either, the **Search in code** button still works (static RIP-relative references to the address - it finds
globals/static variables, not heap objects) and so does the rest of the app.

How to use it: enter an address (for example Watch ▸ ⋮ ▸ "What writes to this address?"), press *Trace (debugger)*, do whatever
changes the value in the game (lose a life, fire a shot), stop. You get the instructions with a hit counter; "address matches"
means the operand's effective address equals the watched one.

## Cheats and export to etaHEN

- Addresses in cheats are **module + offset** (e.g. `eboot.bin+0x1A2B3C`), so they survive a game restart. Heap/stack addresses
  (the typical scanner result) **cannot go into a file** - the app refuses and explains why. Turn scanner findings into cheats
  through the Trace tab (an instruction in the code) or by writing a value to a static data address.
- Offsets depend on the **game version** - a file belongs to one version (Title ID + version).
- One file patches one module; tick cheats from a single module when exporting.
- Formats: **SHN** (etaHEN XML) or **JSON**. *Cheats* tab ▸ *Preview* shows the content before sending.
- *Send to console (FTP)* writes `/data/etaHEN/cheats/shn/<TITLEID>_<VERSION>_nitro.shn` (or `.../json/...`) through
  etaHEN's FTP server (port **1337**, passive mode) and appends an entry to etaHEN's cache list. The `_nitro` suffix is on
  purpose: etaHEN merges files of the same game and version, so community packs are not overwritten.
  After sending, restart the game or reload the cheats in the etaHEN menu.
- *Save file…* stores the same file on the phone (to copy by hand).

## What is not there (yet)

- The **MC4** format - SHN and JSON only; the SHN `Section` field is always `0`.
- Several patches inside one hand-made cheat (you can add several cheats instead).
- A real console may differ from the simulation in details PROTOCOL.md does not describe (scan speed on big games,
  limits on the number of results). If results look odd, narrow the scan (another type, fewer regions).
- *Turbo* mode: the server returns the value from the last scan with each result, not a live one - so the *Refresh* button
  reads current values separately and the "was" column stays empty in that mode.
- Online games: changing memory in games with online modes can get you banned - that is your responsibility.

## Credits

- **Holendersky** - assisting Claude
- **Claude (Anthropic)** - AI developer: protocol client, scanners, tracer, cheat export, app and tests
- **Pharaoh2k** - [ps5debug-NG](https://github.com/Pharaoh2k/ps5debug-NG), the payload this app talks to (GPL-3.0)
- **Ctn & SiSTRo** (ps5debug) and **jogolden** (ps4debug) - the wire protocol ps5debug-NG stays compatible with
- **etaHEN** - the cheat loader the exported files are made for (SHN/JSON formats, FTP server)
- The PS5 cheat community ([etaHEN/PS5_Cheats](https://github.com/etaHEN/PS5_Cheats)) - 842 SHN files used to test the cheat file reader

Use at your own risk and only on your own console. Not affiliated with Sony, etaHEN or the ps5debug-NG authors.
`tools/keystream_ref.c` is copied from ps5debug-NG (GPL-3.0) and is only used to generate a unit-test fixture.

## Sources

- ps5debug-NG: `PROTOCOL.md` and the server source (commands, structures, scan authentication, watchpoints)
- etaHEN: the cheat loading code (SHN/JSON, file names, cache lists, FTP)
- the PS5 community cheat repository - 842 SHN files used for compatibility tests of the SHN reader
