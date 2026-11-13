package dev.ps5nitro.core

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** One loaded module of the target process: every mapping that carries the same name. */
class ModuleInfo(val name: String, val regions: List<MapEntry>) {
    /** Lowest mapped address. etaHEN adds cheat offsets to the module's first section, which starts here. */
    val base: Long = regions.minOf { it.start }
    val end: Long = regions.maxOf { it.end }

    fun contains(address: Long): Boolean = regions.any { it.contains(address) }
    fun offsetOf(address: Long): Long = address - base
}

class ModuleMap(maps: List<MapEntry>) {
    val modules: List<ModuleInfo> = maps
        .filter { it.name.isNotEmpty() }
        .groupBy { it.name }
        .map { (n, l) -> ModuleInfo(n, l) }
        .sortedBy { it.base }

    fun find(address: Long): ModuleInfo? = modules.firstOrNull { it.contains(address) }
    fun byName(name: String): ModuleInfo? = modules.firstOrNull { it.name == name }

    /** The main executable of the process ("eboot.bin" for almost every title). */
    fun mainModule(): ModuleInfo? =
        byName("eboot.bin")
            ?: modules.firstOrNull { it.name.endsWith(".elf") || it.name.endsWith(".bin") }
            ?: modules.firstOrNull { m -> !m.name.endsWith(".sprx") && m.regions.any { it.executable } }
}

enum class CheatFormat(val ext: String) { SHN("shn"), JSON("json") }

/** One memory patch of a cheat, relative to the start of the module named in [CheatSet.process]. */
class PatchLine(val offset: Long, val on: ByteArray, val off: ByteArray, val section: Int = 0) {
    init {
        require(offset >= 0) { "negative offset" }
        require(on.isNotEmpty()) { "empty patch" }
        require(section >= 0) { "bad section" }
    }
}

class Cheat(val name: String, val description: String = "", val lines: List<PatchLine>) {
    init {
        require(name.isNotBlank()) { "a cheat needs a name" }
    }
}

/** Everything that goes into one cheat file (one process, one game version). */
class CheatSet(
    val gameName: String,
    val author: String,
    val titleId: String,
    val appVersion: String,
    val process: String,
    val cheats: List<Cheat>,
    val genre: String = "Action",
)

/** A patch on a live address, as the app keeps them while the game runs. */
class AbsPatch(val address: Long, val on: ByteArray, val off: ByteArray) {
    init {
        require(on.isNotEmpty() && on.size == off.size) { "ON and OFF bytes must have the same non-zero length" }
    }
}

class AbsCheat(val name: String, val description: String, val patches: List<AbsPatch>)

class CheatExportException(message: String) : IllegalArgumentException(message)

object CheatExporter {
    /** Turns live-address cheats into module-relative cheat-file entries. */
    fun build(
        modules: ModuleMap,
        gameName: String,
        author: String,
        titleId: String,
        appVersion: String,
        cheats: List<AbsCheat>,
        processName: String? = null,
    ): CheatSet {
        if (cheats.isEmpty()) throw CheatExportException("Nothing to export - add at least one cheat")
        var process = processName
        val out = ArrayList<Cheat>()
        for (c in cheats) {
            if (c.patches.isEmpty()) throw CheatExportException("Cheat \"${c.name}\" has no patches")
            val lines = ArrayList<PatchLine>()
            for (p in c.patches) {
                val mod = modules.find(p.address)
                    ?: throw CheatExportException(
                        "Cheat \"${c.name}\": ${hex64(p.address)} is not inside a named module " +
                            "(heap and stack addresses change every run, patch code or static data instead)",
                    )
                if (process == null) process = mod.name
                if (mod.name != process) {
                    throw CheatExportException(
                        "Cheat \"${c.name}\": ${hex64(p.address)} is in ${mod.name}, " +
                            "but one cheat file can only patch one module ($process)",
                    )
                }
                lines.add(PatchLine(mod.offsetOf(p.address), p.on, p.off))
            }
            out.add(Cheat(c.name, c.description, lines))
        }
        return CheatSet(gameName, author, titleId, appVersion, process!!, out)
    }
}

internal fun xmlEscape(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (ch in s) {
        when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch == '\'' -> sb.append("&apos;")
            ch < ' ' -> sb.append(' ')
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

internal fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

private fun hexOffset(v: Long): String = java.lang.Long.toHexString(v).uppercase()

/** Writes the XML trainer format that etaHEN loads from /data/etaHEN/cheats/shn. */
object ShnWriter {
    fun write(set: CheatSet): ByteArray {
        val sb = StringBuilder(2048)
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<Trainer xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" ")
            .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
        sb.append(" Game=\"").append(xmlEscape(set.gameName)).append('"')
        sb.append(" Moder=\"").append(xmlEscape(set.author)).append('"')
        sb.append(" Cusa=\"").append(xmlEscape(set.titleId)).append('"')
        sb.append(" Version=\"").append(xmlEscape(set.appVersion)).append('"')
        sb.append(" Process=\"").append(xmlEscape(set.process)).append("\">\n")
        sb.append("  <Genres Name=\"").append(xmlEscape(set.genre)).append("\" />\n")
        sb.append("  <Items />\n")
        for (c in set.cheats) {
            sb.append("  <Cheat Text=\"").append(xmlEscape(c.name))
                .append("\" Description=\"").append(xmlEscape(c.description)).append("\">\n")
            for (l in c.lines) {
                sb.append("    <Cheatline>\n")
                sb.append("      <Offset>").append(hexOffset(l.offset)).append("</Offset>\n")
                sb.append("      <Section>").append(l.section).append("</Section>\n")
                sb.append("      <ValueOn>").append(l.on.toHex("-")).append("</ValueOn>\n")
                sb.append("      <ValueOff>").append(l.off.toHex("-")).append("</ValueOff>\n")
                sb.append("    </Cheatline>\n")
            }
            sb.append("  </Cheat>\n")
        }
        sb.append("</Trainer>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}

/** Writes the JSON trainer format that etaHEN loads from /data/etaHEN/cheats/json. */
object JsonCheatWriter {
    fun write(set: CheatSet): ByteArray {
        val sb = StringBuilder(2048)
        sb.append("{\n")
        sb.append("  \"name\": ").append(jsonString(set.gameName)).append(",\n")
        sb.append("  \"id\": ").append(jsonString(set.titleId)).append(",\n")
        sb.append("  \"version\": ").append(jsonString(set.appVersion)).append(",\n")
        sb.append("  \"process\": ").append(jsonString(set.process)).append(",\n")
        sb.append("  \"mods\": [\n")
        for ((ci, c) in set.cheats.withIndex()) {
            sb.append("    {\n")
            sb.append("      \"name\": ").append(jsonString(c.name)).append(",\n")
            if (c.description.isNotEmpty()) {
                sb.append("      \"description\": ").append(jsonString(c.description)).append(",\n")
            }
            sb.append("      \"type\": \"checkbox\",\n")
            sb.append("      \"memory\": [\n")
            for ((li, l) in c.lines.withIndex()) {
                sb.append("        {\n")
                sb.append("          \"offset\": \"").append(hexOffset(l.offset)).append("\",\n")
                // etaHEN reads "section" as a string; omit it for the default section 0.
                if (l.section != 0) sb.append("          \"section\": \"").append(l.section).append("\",\n")
                sb.append("          \"on\": \"").append(l.on.toHex()).append("\",\n")
                sb.append("          \"off\": \"").append(l.off.toHex()).append("\"\n")
                sb.append("        }").append(if (li < c.lines.size - 1) ",\n" else "\n")
            }
            sb.append("      ]\n")
            sb.append("    }").append(if (ci < set.cheats.size - 1) ",\n" else "\n")
        }
        sb.append("  ],\n")
        sb.append("  \"credits\": [")
        if (set.author.isNotBlank()) sb.append(jsonString(set.author))
        sb.append("]\n")
        sb.append("}\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}

/** Reads SHN trainer files, e.g. to extend a file that is already on the console. */
object ShnReader {
    fun read(bytes: ByteArray): CheatSet {
        // Files in circulation declare encoding="utf-16" but are UTF-8 on disk; a strict XML parser would
        // follow the declaration and fail. Decode ourselves and hand over characters instead of bytes.
        val text = decode(bytes).replaceFirst(Regex("^\\s*<\\?xml[^>]*\\?>"), "")
        val dbf = DocumentBuilderFactory.newInstance()
        trySetFeature(dbf, "http://apache.org/xml/features/disallow-doctype-decl", true)
        trySetFeature(dbf, "http://xml.org/sax/features/external-general-entities", false)
        trySetFeature(dbf, "http://xml.org/sax/features/external-parameter-entities", false)
        runCatching { dbf.isXIncludeAware = false }
        runCatching { dbf.isExpandEntityReferences = false }
        val doc = try {
            val builder = dbf.newDocumentBuilder()
            // the default handler prints every problem to stderr; we report failures through the exception
            builder.setErrorHandler(object : org.xml.sax.ErrorHandler {
                override fun warning(e: org.xml.sax.SAXParseException) {}
                override fun error(e: org.xml.sax.SAXParseException) {}
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            })
            builder.parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw Ps5ProtocolException("not a readable SHN file: ${e.message}")
        }
        val root = doc.documentElement
        if (root == null || root.tagName != "Trainer") throw Ps5ProtocolException("not a SHN trainer file")

        val cheats = ArrayList<Cheat>()
        val cheatNodes = root.getElementsByTagName("Cheat")
        for (i in 0 until cheatNodes.length) {
            val ce = cheatNodes.item(i) as Element
            val lines = ArrayList<PatchLine>()
            val lineNodes = ce.getElementsByTagName("Cheatline")
            for (j in 0 until lineNodes.length) {
                val le = lineNodes.item(j) as Element
                val off = childText(le, "Offset")
                val on = childText(le, "ValueOn").replace("-", "")
                val orig = childText(le, "ValueOff").replace("-", "")
                // etaHEN ignores a line when any of the three is empty.
                if (off.isEmpty() || on.isEmpty() || orig.isEmpty()) continue
                val offset = off.toLongOrNull(16) ?: throw Ps5ProtocolException("bad offset \"$off\" in cheat file")
                val section = childText(le, "Section").toIntOrNull() ?: 0
                try {
                    lines.add(PatchLine(offset, on.hexToBytes(), orig.hexToBytes(), section))
                } catch (e: IllegalArgumentException) {
                    throw Ps5ProtocolException("bad bytes in cheat \"${ce.getAttribute("Text")}\": ${e.message}")
                }
            }
            cheats.add(Cheat(ce.getAttribute("Text").ifBlank { "(unnamed)" }, ce.getAttribute("Description"), lines))
        }
        val genres = root.getElementsByTagName("Genres")
        val genre = if (genres.length > 0) (genres.item(0) as Element).getAttribute("Name") else ""
        return CheatSet(
            gameName = root.getAttribute("Game"),
            author = root.getAttribute("Moder"),
            titleId = root.getAttribute("Cusa"),
            appVersion = root.getAttribute("Version"),
            process = root.getAttribute("Process"),
            cheats = cheats,
            genre = genre.ifBlank { "Action" },
        )
    }

    private fun childText(parent: Element, tag: String): String {
        val n = parent.getElementsByTagName(tag)
        return if (n.length == 0) "" else (n.item(0).textContent ?: "").trim()
    }

    private fun trySetFeature(f: DocumentBuilderFactory, name: String, value: Boolean) {
        try { f.setFeature(name, value) } catch (_: Exception) { /* parser does not know the feature */ }
    }

    private fun decode(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        if (b.size >= 4 && b[0].toInt() != 0 && b[1].toInt() == 0 && b[2].toInt() != 0 && b[3].toInt() == 0) {
            return String(b, Charsets.UTF_16LE)
        }
        val s = String(b, Charsets.UTF_8)
        return if (s.startsWith("﻿")) s.substring(1) else s
    }
}

/** Where etaHEN looks for cheat files and how it wants them named. */
object CheatFiles {
    const val ROOT = "/data/etaHEN/cheats"
    private val TITLE_ID = Regex("^[A-Z]{4}[0-9]{5}$")
    private val VERSION = Regex("^[0-9][0-9A-Za-z.]{0,15}$")

    fun dir(ext: String): String = "$ROOT/$ext"

    /**
     * `{TITLEID}_{VERSION}[_{tag}].{ext}`. etaHEN merges every file that carries the same title id and
     * version, so the default tag keeps our file next to an existing community file instead of replacing it.
     */
    fun fileName(titleId: String, appVersion: String, ext: String, tag: String? = "nitro"): String {
        require(TITLE_ID.matches(titleId)) { "title id must look like PPSA01342 or CUSA05786, got \"$titleId\"" }
        require(VERSION.matches(appVersion)) { "unexpected app version \"$appVersion\"" }
        val t = tag?.filter { it.isLetterOrDigit() }.orEmpty()
        return titleId + "_" + appVersion + (if (t.isNotEmpty()) "_$t" else "") + "." + ext
    }

    fun remotePath(titleId: String, appVersion: String, ext: String, tag: String? = "nitro"): String =
        dir(ext) + "/" + fileName(titleId, appVersion, ext, tag)

    fun encode(set: CheatSet, format: CheatFormat): ByteArray = when (format) {
        CheatFormat.SHN -> ShnWriter.write(set)
        CheatFormat.JSON -> JsonCheatWriter.write(set)
    }
}
