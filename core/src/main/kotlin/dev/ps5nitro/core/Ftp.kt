package dev.ps5nitro.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class FtpException(val code: Int, message: String) : IOException(message)

class FtpReply(val code: Int, val text: String)

/**
 * Minimal passive-mode FTP client, enough for the FTP server inside etaHEN (port 1337, rooted at "/")
 * and similar console servers. One command at a time; discard the client after an error.
 */
class FtpClient(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val timeoutMs: Int = 10_000,
) : Closeable {
    private var sock: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    fun connect() {
        close()
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            throw FtpException(0, "Cannot reach FTP on $host:$port (${e.message}). Enable the FTP server in etaHEN.")
        }
        s.soTimeout = timeoutMs
        s.tcpNoDelay = true
        sock = s
        input = BufferedInputStream(s.getInputStream())
        output = s.getOutputStream()

        val hello = readReply()
        if (hello.code != 220) throw FtpException(hello.code, "unexpected FTP greeting: ${hello.text}")
        var r = cmd("USER anonymous")
        if (r.code == 331 || r.code == 332) r = cmd("PASS nitro@localhost")
        if (r.code != 230 && r.code != 202) throw FtpException(r.code, "FTP login refused: ${r.text}")
        r = cmd("TYPE I")
        if (r.code != 200) throw FtpException(r.code, "TYPE I refused: ${r.text}")
    }

    /** Size of a remote file, or null when it does not exist (or the server cannot tell). */
    fun size(path: String): Long? {
        val r = cmd("SIZE $path")
        return if (r.code == 213) r.text.trim().toLongOrNull() else null
    }

    /** Creates every missing directory of [path]. */
    fun mkdirs(path: String) {
        var cur = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            cur += "/$part"
            val r = cmd("MKD $cur")
            if (r.code / 100 != 2) {
                // Most likely "already exists"; confirm by entering it.
                val c = cmd("CWD $cur")
                if (c.code / 100 != 2) throw FtpException(r.code, "cannot create $cur: ${r.text}")
            }
        }
        cmd("CWD /")
    }

    fun upload(path: String, data: ByteArray, append: Boolean = false) {
        val verb = if (append) "APPE" else "STOR"
        val d = openData()
        try {
            val r = cmd("$verb $path")
            if (r.code !in 100..199) throw FtpException(r.code, "$verb $path: ${r.text}")
            val o = d.getOutputStream()
            o.write(data)
            o.flush()
        } finally {
            try { d.close() } catch (_: IOException) {}
        }
        val done = readReply()
        if (done.code != 226 && done.code != 250) throw FtpException(done.code, "upload of $path failed: ${done.text}")
    }

    fun download(path: String, maxBytes: Int = 16 shl 20): ByteArray {
        val d = openData()
        val bytes = try {
            val r = cmd("RETR $path")
            if (r.code !in 100..199) throw FtpException(r.code, "RETR $path: ${r.text}")
            readAll(d, maxBytes, path)
        } finally {
            try { d.close() } catch (_: IOException) {}
        }
        val done = readReply()
        if (done.code != 226 && done.code != 250) throw FtpException(done.code, "download of $path failed: ${done.text}")
        return bytes
    }

    /** File names inside [path] (NLST); empty when the directory does not exist. */
    fun nlst(path: String): List<String> {
        val d = openData()
        val raw = try {
            val r = cmd("NLST $path")
            if (r.code !in 100..199) return emptyList()
            readAll(d, 4 shl 20, path)
        } finally {
            try { d.close() } catch (_: IOException) {}
        }
        val done = readReply()
        if (done.code != 226 && done.code != 250) throw FtpException(done.code, "listing of $path failed: ${done.text}")
        return String(raw, Charsets.UTF_8).split('\n')
            .map { it.trim().substringAfterLast('/') }
            .filter { it.isNotEmpty() }
    }

    override fun close() {
        val s = sock ?: return
        try {
            output?.write("QUIT\r\n".toByteArray(Charsets.US_ASCII))
            output?.flush()
        } catch (_: IOException) {
            // already gone
        }
        try { s.close() } catch (_: IOException) {}
        sock = null
        input = null
        output = null
    }

    // --- protocol plumbing ---------------------------------------------------------------------

    private fun readAll(d: Socket, maxBytes: Int, what: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        val ins = d.getInputStream()
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            bos.write(buf, 0, n)
            if (bos.size() > maxBytes) throw FtpException(0, "$what is larger than $maxBytes bytes")
        }
        return bos.toByteArray()
    }

    private fun openData(): Socket {
        val r = cmd("PASV")
        if (r.code != 227) throw FtpException(r.code, "PASV failed: ${r.text}")
        val m = PASV.find(r.text) ?: throw FtpException(r.code, "cannot parse PASV reply: ${r.text}")
        val g = m.groupValues
        // Use the address we already reached the server on; the one in the reply may be an internal one.
        val dataPort = g[5].toInt() * 256 + g[6].toInt()
        val d = Socket()
        try {
            d.connect(InetSocketAddress(host, dataPort), timeoutMs)
        } catch (e: IOException) {
            try { d.close() } catch (_: IOException) {}
            throw FtpException(0, "FTP data connection to $host:$dataPort failed: ${e.message}")
        }
        d.soTimeout = timeoutMs
        return d
    }

    private fun cmd(line: String): FtpReply {
        require(line.none { it == '\r' || it == '\n' }) { "illegal character in FTP command" }
        val out = output ?: throw FtpException(0, "FTP is not connected")
        out.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        out.flush()
        return readReply()
    }

    private fun readLine(): String {
        val inp = input ?: throw FtpException(0, "FTP is not connected")
        val sb = ByteArrayOutputStream(80)
        while (true) {
            val b = inp.read()
            if (b < 0) throw FtpException(0, "FTP connection closed by the console")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > 8192) throw FtpException(0, "FTP reply line too long")
        }
        return String(sb.toByteArray(), Charsets.UTF_8)
    }

    private fun readReply(): FtpReply {
        val first = readLine()
        if (first.length < 3 || !first.substring(0, 3).all { it in '0'..'9' }) {
            throw FtpException(0, "bad FTP reply: $first")
        }
        val code = first.substring(0, 3).toInt()
        val text = StringBuilder(first.drop(4))
        if (first.length > 3 && first[3] == '-') {
            val endPrefix = "$code "
            while (true) {
                val l = readLine()
                text.append('\n').append(l)
                if (l.startsWith(endPrefix) || l == code.toString()) break
            }
        }
        return FtpReply(code, text.toString())
    }

    companion object {
        /** etaHEN's optional FTP server. */
        const val DEFAULT_PORT = 1337
        private val PASV = Regex("(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})")
    }
}

/** Uploads a cheat file where etaHEN reads it. */
object CheatPush {
    class Result(val remotePath: String, val replaced: Boolean, val registered: Boolean)

    /**
     * @param registerInCache also append the new file to etaHEN's `<ext>.txt` list so it shows up without
     *   waiting for the next cache rebuild (etaHEN adds the very same line itself later; it never duplicates).
     */
    fun push(
        host: String,
        port: Int,
        set: CheatSet,
        format: CheatFormat,
        registerInCache: Boolean = true,
        tag: String? = "nitro",
    ): Result {
        val ext = format.ext
        val data = CheatFiles.encode(set, format)
        val name = CheatFiles.fileName(set.titleId, set.appVersion, ext, tag)
        val dir = CheatFiles.dir(ext)
        FtpClient(host, port).use { ftp ->
            ftp.connect()
            ftp.mkdirs(dir)
            val remote = "$dir/$name"
            val existed = ftp.size(remote) != null
            ftp.upload(remote, data)
            var registered = false
            if (registerInCache && !existed) {
                val line = "\n" + name + "=" + set.gameName.replace('\n', ' ').replace('\r', ' ') + "\n"
                try {
                    ftp.upload("$dir.txt", line.toByteArray(Charsets.UTF_8), append = true)
                    registered = true
                } catch (_: FtpException) {
                    // the file itself is on the console; etaHEN will pick it up on its own cache rebuild
                }
            }
            return Result(remote, existed, registered)
        }
    }
}
