package com.example.taskmgr

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

data class SProc(val pid: Int, val name: String, val cpu: Float, val resKb: Long)

object Sh {
    fun alive() = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
    fun ready() = try { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }
    fun ask() { try { Shizuku.requestPermission(1) } catch (_: Throwable) {} }

    /** shell権限でコマンド実行。Shizuku.newProcess は private なのでリフレクションで呼ぶ */
    fun sh(cmd: String): String = try {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor(); out
    } catch (e: Throwable) { "error: ${e.message}" }

    private fun res(s: String): Long {
        val n = s.dropLast(1).toDoubleOrNull()
        return when (s.lastOrNull()) { 'K' -> n?.toLong(); 'M' -> n?.times(1024)?.toLong(); 'G' -> n?.times(1048576)?.toLong(); else -> s.toLongOrNull() } ?: 0
    }

    /** 全体CPU使用率(%)と全プロセス一覧。topを2回回して2回目を採用（約1秒） */
    fun top(): Pair<Float, List<SProc>> = try {
        val text = sh("top -b -n 2 -d 1 -m 80")
        val lines = text.lines()
        var cpu = 0f
        Regex("(\\d+)%cpu.*?(\\d+)%idle").findAll(text).lastOrNull()?.let {
            val t = it.groupValues[1].toFloat(); val i = it.groupValues[2].toFloat()
            if (t > 0) cpu = 100f * (t - i) / t
        }
        val hi = lines.indexOfLast { it.trim().startsWith("PID") }
        val h = lines[hi].replace("S[%CPU]", "S %CPU").trim().split(Regex("\\s+"))
        val ip = h.indexOf("PID"); val ir = h.indexOf("RES"); val ic = h.indexOf("%CPU")
        val rows = lines.drop(hi + 1).mapNotNull { l ->
            val t = l.trim().split(Regex("\\s+"))
            if (t.size < h.size) return@mapNotNull null
            val pid = t[ip].toIntOrNull() ?: return@mapNotNull null
            SProc(pid, t[h.size - 1], t[ic].toFloatOrNull() ?: 0f, res(t[ir]))
        }
        cpu to rows
    } catch (e: Throwable) { 0f to emptyList() }

    fun kill(p: SProc): String {
        val pkg = p.name.substringBefore(':')
        return if (pkg.contains('.') && pkg.matches(Regex("[A-Za-z0-9._]+")))
            sh("am force-stop $pkg").ifBlank { "$pkg を強制終了しました" }
        else sh("kill -9 ${p.pid}").ifBlank { "PID ${p.pid} をkillしました" }
    }

    fun services(): List<String> =
        Regex("ServiceRecord\\{[0-9a-f]+ u\\d+ ([^ }]+)\\}").findAll(sh("dumpsys activity services"))
            .map { it.groupValues[1] }.distinct().sorted().toList()

    private val comp = Regex("[A-Za-z0-9._/$]+")
    fun stopService(c: String) = if (comp.matches(c)) sh("am stopservice -n '$c'").lines().firstOrNull { it.isNotBlank() } ?: "停止しました" else "不正な名前"
    fun disableComponent(c: String) = if (comp.matches(c)) sh("pm disable --user 0 '$c'").trim() else "不正な名前"
}