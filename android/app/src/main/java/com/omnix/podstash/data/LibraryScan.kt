package com.omnix.podstash.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.Normalizer

data class AudioHit(
    val name: String,
    val path: String,
    val size: Long,
    val fromRoot: Boolean = false,
)

object LibraryScan {
    private const val MAX_AUDIO = 2500
    private val AUDIO_EXT_RE = Regex("\\.(mp3|m4a|mp4|aac|ogg|opus|wav|flac)$", RegexOption.IGNORE_CASE)
    private val KEEP_CHARS_RE = Regex("[^0-9a-zA-Z\\u4e00-\\u9fff]+")
    private val PREFIX_RE = Regex(
        "^(?:(?:\\d{4}[-._/年]\\d{1,2}[-._/月]\\d{1,2}日?|\\d{8}|(?:e|ep|vol|s)\\.?\\s*\\d{1,4}(?:e\\d{1,4})?|#\\s*\\d{1,4}|第\\s*\\d+\\s*(?:期|集|话|回|章))(?:[\\s.\\-_—–:：#)\\]】]+|(?=[\\u4e00-\\u9fff])|$)|\\d{1,4}[\\s.\\-_—–:：#)\\]】]+)",
        RegexOption.IGNORE_CASE,
    )

    fun stripAudioExt(name: String): String = AUDIO_EXT_RE.replace(name, "")

    fun normalize(name: String, stripPrefixes: Boolean = true): String {
        var s = Normalizer.normalize(name, Normalizer.Form.NFKC)
        s = stripAudioExt(s).trim()
        if (stripPrefixes) {
            for (i in 0 until 8) {
                val nxt = PREFIX_RE.replaceFirst(s, "").trim(' ', '-', '_', '.')
                if (nxt == s) break
                s = nxt
            }
        }
        return KEEP_CHARS_RE.replace(s.lowercase(), "")
    }

    private fun strongEnough(key: String, forSubstring: Boolean): Boolean {
        if (key.isEmpty()) return false
        val cjk = key.count { it in '\u4e00'..'\u9fff' }
        return if (forSubstring) cjk >= 4 || key.length >= 10 else cjk >= 2 || key.length >= 5
    }

    fun score(epTitle: String, fileName: String, showName: String = ""): Int {
        val stem = stripAudioExt(fileName.substringAfterLast('/').substringAfterLast('\\'))
        if (stem.isBlank()) return 0
        if (Store.sanitize(epTitle) == Store.sanitize(stem)) return 100
        val epN = normalize(epTitle)
        val epRaw = normalize(epTitle, stripPrefixes = false)
        val fileN = normalize(stem)
        val showN = if (showName.isBlank()) "" else normalize(showName)
        val variants = mutableSetOf(fileN)
        if (showN.isNotEmpty() && fileN.length > showN.length + 1) {
            if (fileN.startsWith(showN)) variants += fileN.removePrefix(showN)
            if (fileN.endsWith(showN)) variants += fileN.removeSuffix(showN)
        }
        var best = 0
        for (fn in variants) {
            if (fn.isEmpty()) continue
            if (epN.isNotEmpty() && epN == fn && strongEnough(epN, false)) {
                best = maxOf(best, 90)
                continue
            }
            if (epRaw.isNotEmpty() && epRaw == fn && strongEnough(epRaw, false)) {
                best = maxOf(best, 88)
                continue
            }
            if (epN.isNotEmpty() && strongEnough(epN, true) && fn.endsWith(epN)) {
                best = maxOf(best, 80)
                continue
            }
            if (epN.isNotEmpty() && strongEnough(epN, true) && epN in fn) {
                best = maxOf(best, 75)
                continue
            }
            if (strongEnough(fn, true) && fn in epN) {
                best = maxOf(best, 70)
            }
        }
        return best
    }

    fun isAudio(name: String, mime: String? = null): Boolean {
        val lower = name.lowercase()
        if (Store.AUDIO_EXTS.any { lower.endsWith(it) }) return true
        return mime?.startsWith("audio/") == true
    }

    fun assign(episodes: List<Episode>, files: List<AudioHit>, showName: String): Map<Int, AudioHit> {
        data class Pair(val score: Int, val titleLen: Int, val index: Int, val file: AudioHit)
        val pairs = mutableListOf<Pair>()
        for (ep in episodes) {
            for (f in files) {
                val minScore = if (f.fromRoot) 80 else 70
                val s = score(ep.title, f.name, showName)
                if (s < minScore) continue
                if (f.fromRoot && s < 90) {
                    val fn = normalize(f.name)
                    val sn = normalize(showName)
                    if (sn.isNotEmpty() && sn !in fn) continue
                }
                pairs += Pair(s, normalize(ep.title).length, ep.index, f)
            }
        }
        pairs.sortWith(compareByDescending<Pair> { it.score }.thenByDescending { it.titleLen })
        val used = mutableSetOf<String>()
        val result = mutableMapOf<Int, AudioHit>()
        for (p in pairs) {
            if (p.index in result || p.file.path in used) continue
            result[p.index] = p.file
            used += p.file.path
        }
        return result
    }

    fun listAppLibrary(root: File, showName: String): List<AudioHit> {
        if (!root.exists()) return emptyList()
        val want = normalize(showName)
        val exact = Store.sanitize(showName.ifBlank { "Podcast" })
        val hits = mutableListOf<AudioHit>()
        val seen = mutableSetOf<String>()

        fun addFile(f: File, fromRoot: Boolean) {
            if (hits.size >= MAX_AUDIO) return
            if (!f.isFile || f.name.startsWith(".")) return
            if (!isAudio(f.name) || f.length() <= 0) return
            val key = f.absolutePath
            if (!seen.add(key)) return
            hits += AudioHit(f.name, key, f.length(), fromRoot)
        }

        fun addDirFiles(dir: File, extraDepth: Int, fromRoot: Boolean) {
            if (!dir.isDirectory) return
            val kids = dir.listFiles() ?: return
            for (p in kids) {
                if (p.isFile) addFile(p, fromRoot)
            }
            if (extraDepth <= 0) return
            for (p in kids) {
                if (p.isDirectory && !p.name.startsWith(".")) {
                    addDirFiles(p, extraDepth - 1, fromRoot)
                }
            }
        }

        fun isShowDir(dir: File): Boolean {
            val n = normalize(dir.name)
            if (n.isEmpty() || want.isEmpty()) return false
            if (n == want || dir.name == exact) return true
            return want.length >= 4 && (want in n || n in want)
        }

        val showDir = File(root, exact)
        if (showDir.isDirectory) addDirFiles(showDir, extraDepth = 1, fromRoot = false)
        val children = root.listFiles() ?: emptyArray()
        for (p in children.take(400)) {
            if (!p.isDirectory || p.name.startsWith(".")) continue
            if (isShowDir(p)) addDirFiles(p, extraDepth = 1, fromRoot = false)
            val nested = p.listFiles() ?: continue
            for (q in nested.take(80)) {
                if (q.isDirectory && !q.name.startsWith(".") && isShowDir(q)) {
                    addDirFiles(q, extraDepth = 1, fromRoot = false)
                }
            }
        }
        for (p in children) {
            if (p.isFile) addFile(p, fromRoot = true)
        }
        return hits
    }

    fun listSaf(context: Context, treeUri: String, showName: String): List<AudioHit> {
        if (treeUri.isBlank()) return emptyList()
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return emptyList()
        val want = normalize(showName)
        val exact = Store.sanitize(showName.ifBlank { "Podcast" })
        val hits = mutableListOf<AudioHit>()
        val seen = mutableSetOf<String>()

        fun addFile(f: DocumentFile, fromRoot: Boolean) {
            if (hits.size >= MAX_AUDIO) return
            if (!f.isFile) return
            val name = f.name.orEmpty()
            if (name.startsWith(".")) return
            val size = try { f.length() } catch (_: Exception) { 0L }
            if (size <= 0 || !isAudio(name, f.type)) return
            val path = f.uri.toString()
            if (!seen.add(path)) return
            hits += AudioHit(name, path, size, fromRoot)
        }

        fun addDirFiles(dir: DocumentFile, extraDepth: Int, fromRoot: Boolean) {
            val kids = try { dir.listFiles() } catch (_: Exception) { return }
            for (f in kids) {
                if (f.isFile) addFile(f, fromRoot)
            }
            if (extraDepth <= 0) return
            for (f in kids) {
                if (f.isDirectory && !f.name.orEmpty().startsWith(".")) {
                    addDirFiles(f, extraDepth - 1, fromRoot)
                }
            }
        }

        fun isShowDir(name: String): Boolean {
            val n = normalize(name)
            if (n.isEmpty() || want.isEmpty()) return false
            if (n == want || name == exact) return true
            return want.length >= 4 && (want in n || n in want)
        }

        val kids = try { root.listFiles() } catch (_: Exception) { emptyArray() }
        for (f in kids) {
            if (f.isDirectory) {
                val name = f.name.orEmpty()
                if (isShowDir(name)) addDirFiles(f, extraDepth = 1, fromRoot = false)
                val inner = try { f.listFiles() } catch (_: Exception) { emptyArray() }
                for (q in inner) {
                    val qName = q.name.orEmpty()
                    if (q.isDirectory && !qName.startsWith(".") && isShowDir(qName)) {
                        addDirFiles(q, extraDepth = 1, fromRoot = false)
                    }
                }
            } else if (f.isFile) {
                addFile(f, fromRoot = true)
            }
        }
        return hits
    }
}
