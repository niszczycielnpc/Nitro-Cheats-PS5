package dev.ps5nitro.app

import android.content.Context
import android.content.SharedPreferences

/** Small settings store (SharedPreferences). */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("ps5nitro", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "192.168.101.15") ?: "192.168.101.15"
        set(v) { sp.edit().putString("host", v).apply() }

    /** ps5debug command port. */
    var port: Int
        get() = sp.getInt("port", 744)
        set(v) { sp.edit().putInt("port", v).apply() }

    /**
     * Address of etaHEN's FTP server when it differs from [host] (e.g. the PC relay address is typed
     * as the console address, but the FTP server is on the console itself). Blank = same as [host].
     */
    var ftpHost: String
        get() = sp.getString("ftpHost", "") ?: ""
        set(v) { sp.edit().putString("ftpHost", v).apply() }

    /** etaHEN's FTP server (used to push cheat files). */
    var ftpPort: Int
        get() = sp.getInt("ftpPort", 1337)
        set(v) { sp.edit().putInt("ftpPort", v).apply() }

    /**
     * Port the phone listens on for debugger interrupts. The console always dials 755, which Android
     * apps cannot bind, so the default is 7755 together with tools/relay.py on a PC (or 755 with root).
     */
    var interruptPort: Int
        get() = sp.getInt("interruptPort", 7755)
        set(v) { sp.edit().putInt("interruptPort", v).apply() }

    var author: String
        get() = sp.getString("author", "PS5 Nitro Cheats") ?: "PS5 Nitro Cheats"
        set(v) { sp.edit().putString("author", v).apply() }

    /** Pause between two rounds of freeze writes. */
    var freezeMs: Int
        get() = sp.getInt("freezeMs", 50)
        set(v) { sp.edit().putInt("freezeMs", v).apply() }

    var cheatsJson: String
        get() = sp.getString("cheats", "[]") ?: "[]"
        set(v) { sp.edit().putString("cheats", v).apply() }
}
