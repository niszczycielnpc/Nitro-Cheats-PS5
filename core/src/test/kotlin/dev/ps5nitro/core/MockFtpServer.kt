package dev.ps5nitro.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** In-memory FTP server that mimics what etaHEN's FTP does for the commands the client uses. */
class MockFtpServer : Closeable {
    private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val files = ConcurrentHashMap<String, ByteArray>()
    val dirs: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add("/"); add("/data") }
    val commands = CopyOnWriteArrayList<String>()

    /** APPE is refused (550), like a server that does not implement it. */
    @Volatile var refuseAppend = false

    private val sockets = Collections.synchronizedList(ArrayList<Socket>())
    @Volatile private var closed = false

    init {
        thread(isDaemon = true, name = "mock-ftp-accept") {
            while (!closed) {
                val c = try { server.accept() } catch (_: IOException) { return@thread }
                sockets.add(c)
                thread(isDaemon = true, name = "mock-ftp-conn") { serve(c) }
            }
        }
    }

    fun text(path: String): String? = files[path]?.toString(Charsets.UTF_8)

    private fun norm(p: String): String {
        val s = "/" + p.split('/').filter { it.isNotEmpty() }.joinToString("/")
        return s
    }

    private fun parent(p: String): String = norm(p.substringBeforeLast('/', "/"))

    private fun serve(c: Socket) {
        var pasv: ServerSocket? = null
        try {
            val inp = c.getInputStream()
            val out = c.getOutputStream()
            fun reply(code: Int, text: String) {
                out.write("$code $text\r\n".toByteArray())
                out.flush()
            }
            fun line(): String? {
                val sb = ByteArrayOutputStream()
                while (true) {
                    val b = inp.read()
                    if (b < 0) return null
                    if (b == '\n'.code) break
                    if (b != '\r'.code) sb.write(b)
                }
                return sb.toString(Charsets.UTF_8)
            }
            fun dataSocket(): Socket? {
                val p = pasv ?: return null
                p.soTimeout = 5000
                return try { p.accept() } catch (_: IOException) { null }
            }
            reply(220, "mock FTP ready")
            while (true) {
                val l = line() ?: break
                commands.add(l)
                val verb = l.substringBefore(' ').uppercase()
                val arg = if (l.contains(' ')) l.substringAfter(' ') else ""
                when (verb) {
                    "USER" -> reply(230, "logged in")
                    "PASS" -> reply(202, "not needed")
                    "TYPE" -> reply(200, "type set")
                    "PASV" -> {
                        try { pasv?.close() } catch (_: IOException) {}
                        val p = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                        pasv = p
                        val pt = p.localPort
                        reply(227, "Entering Passive Mode (127,0,0,1,${pt / 256},${pt % 256})")
                    }
                    "STOR", "APPE" -> {
                        val path = norm(arg)
                        if (verb == "APPE" && refuseAppend) { reply(550, "APPE not supported"); continue }
                        if (parent(path) !in dirs) { reply(550, "no such directory"); continue }
                        reply(150, "send data")
                        val d = dataSocket()
                        if (d == null) { reply(425, "no data connection"); continue }
                        val body = d.use { it.getInputStream().readBytes() }
                        files.merge(path, body) { old, new -> if (verb == "APPE") old + new else new }
                        if (verb == "STOR") files[path] = body
                        reply(226, "transfer complete")
                    }
                    "RETR" -> {
                        val data = files[norm(arg)]
                        if (data == null) { reply(550, "not found"); continue }
                        reply(150, "sending")
                        val d = dataSocket()
                        if (d == null) { reply(425, "no data connection"); continue }
                        d.use { it.getOutputStream().apply { write(data); flush() } }
                        reply(226, "transfer complete")
                    }
                    "SIZE" -> {
                        val data = files[norm(arg)]
                        if (data == null) reply(550, "not found") else reply(213, data.size.toString())
                    }
                    "MKD" -> {
                        val path = norm(arg)
                        when {
                            path in dirs -> reply(550, "exists")
                            parent(path) !in dirs -> reply(550, "parent missing")
                            else -> { dirs.add(path); reply(257, "\"$path\" created") }
                        }
                    }
                    "CWD" -> if (norm(arg) in dirs) reply(250, "ok") else reply(550, "no such directory")
                    "NLST" -> {
                        val dir = norm(arg)
                        if (dir !in dirs) { reply(550, "no such directory"); continue }
                        reply(150, "listing")
                        val d = dataSocket()
                        if (d == null) { reply(425, "no data connection"); continue }
                        val names = files.keys.filter { parent(it) == dir }.map { it.substringAfterLast('/') }.sorted() +
                            dirs.filter { it != "/" && parent(it) == dir }.map { it.substringAfterLast('/') }.sorted()
                        d.use { it.getOutputStream().apply { write(names.joinToString("\r\n", postfix = "\r\n").toByteArray()); flush() } }
                        reply(226, "done")
                    }
                    "QUIT" -> { reply(221, "bye"); break }
                    else -> reply(502, "not implemented")
                }
            }
        } catch (_: IOException) {
            // client went away
        } finally {
            try { pasv?.close() } catch (_: IOException) {}
            try { c.close() } catch (_: IOException) {}
        }
    }

    override fun close() {
        closed = true
        try { server.close() } catch (_: IOException) {}
        synchronized(sockets) { sockets.forEach { try { it.close() } catch (_: IOException) {} } }
    }
}
