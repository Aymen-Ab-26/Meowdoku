package com.example.meowbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs

class BotService : AccessibilityService() {

    // "" = floating button shows over every app.
    // Set to the game's package name to show it only while the game is open.
    private val gamePkg = ""

    private val solvedWaitMs = 4000L    // after the last cat, before looking for the next-level button
    private val nextWaitMs = 4500L      // after pressing next level / skip, before solving
    private val retryWaitMs = 3000L     // after a failed read, before trying again
    private val popupWaitMs = 800L      // after dismissing a popup
    private val restartWaitMs = 1500L   // after pressing Restart on "Out of Fishes"
    private val maxFailures = 6         // consecutive failed reads before the loop stops itself
    private val maxScreenTaps = 8       // consecutive popup/menu taps before the loop stops itself

    private val main = Handler(Looper.getMainLooper())
    private val loop = Handler(Looper.getMainLooper())   // timers for the auto-play loop
    private val worker = Executors.newSingleThreadExecutor()
    private var wm: WindowManager? = null
    private var button: TextView? = null
    private var banner: TextView? = null
    private var diag = ""
    private var lastError = ""
    private var pxBuf = IntArray(0)                      // reused for every screenshot
    private var bgR = 0
    private var bgG = 0
    private var bgB = 0

    @Volatile private var running = false
    private var failures = 0
    private var screenTaps = 0
    private var levelsDone = 0

    class Solved(val box: IntArray, val n: Int, val sol: IntArray, val placed: BooleanArray)
    class Board(val grid: List<String>, val colors: Int, val forced: IntArray)
    class ScreenAction(val x: Int, val y: Int, val kind: Int)

    private val kNext = 0
    private val kSkip = 1
    private val kPopup = 2
    private val kRestart = 3

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || gamePkg.isEmpty()) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        button?.visibility = if (pkg == gamePkg) View.VISIBLE else View.GONE
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        loop.removeCallbacksAndMessages(null)
        try { button?.let { wm?.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------- on-screen message (toasts get suppressed on some phones) ----------
    private fun showMessage(msg: String) {
        main.post {
            banner?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
            val tv = TextView(this).apply {
                text = msg
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(32, 24, 32, 24)
                setBackgroundColor(Color.argb(235, 30, 30, 30))
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP; y = 120 }
            try { wm?.addView(tv, lp) } catch (_: Exception) { return@post }
            banner = tv
            main.postDelayed({
                try { wm?.removeView(tv) } catch (_: Exception) {}
                if (banner === tv) banner = null
            }, 6000)
        }
    }

    private fun clearMessage() {
        banner?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        banner = null
    }

    // ---------- floating button ----------
    private fun addButton() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "🐱"
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 255, 255, 255))
                setStroke(3, Color.DKGRAY)
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 400
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = ox + dx.toInt()
                        lp.y = oy + dy.toInt()
                        wm?.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> { if (!moved) onButtonTap() }
            }
            true
        }
        wm?.addView(tv, lp)
        button = tv
    }

    /** Tap once = start auto-play (solve, next level, repeat). Tap again (⏹) = stop. */
    private fun onButtonTap() {
        if (running) {
            stopLoop("Stopped after $levelsDone level(s)")
            return
        }
        running = true
        failures = 0
        screenTaps = 0
        levelsDone = 0
        button?.text = "⏹"
        solveStep()
    }

    private fun stopLoop(msg: String) {
        running = false
        loop.removeCallbacksAndMessages(null)
        main.post {
            button?.text = "🐱"
            button?.visibility = View.VISIBLE
        }
        showMessage(msg)
    }

    // ---------- the loop: solve -> wait -> next level -> wait -> solve ... ----------
    private fun solveStep() {
        if (!running) return
        clearMessage()
        button?.visibility = View.INVISIBLE          // keep it out of the screenshot
        loop.postDelayed({
            if (!running) return@postDelayed
            grab { bmp, err ->
                main.post { if (running) button?.visibility = View.VISIBLE }
                if (!running) return@grab
                if (bmp == null) {
                    main.post { retryOrStop("Screenshot failed ($err)") }
                    return@grab
                }
                val w = bmp.width
                val h = bmp.height
                var solved: Solved? = null
                var act: ScreenAction? = null
                try {
                    val px = pixelsOf(bmp)
                    solved = analyze(px, w, h)
                    if (solved == null) act = findScreenAction(px, w, h)   // popup / menu instead of a board?
                } catch (e: Throwable) {
                    lastError = "Error: ${e.javaClass.simpleName} ${e.message}"
                }
                bmp.recycle()
                main.post {
                    if (!running) return@post
                    val s = solved
                    val a = act
                    when {
                        s != null -> {
                            failures = 0
                            screenTaps = 0
                            showMessage("Level ${levelsDone + 1}: ${s.n}x${s.n} board, solving…")
                            tapCats(s.box, s.n, s.sol, s.placed, 0)
                        }
                        a != null -> pressScreenAction(a)
                        else -> retryOrStop(lastError)
                    }
                }
            }
        }, 250)
    }

    private fun retryOrStop(msg: String) {
        failures++
        if (failures >= maxFailures) {
            stopLoop("$msg\n(stopped after $maxFailures tries)")
            return
        }
        showMessage("$msg\nRetrying in 3 s…")
        loop.postDelayed({ solveStep() }, retryWaitMs)
    }

    /** Press a button found on a non-board screen, then carry on after the right delay. */
    private fun pressScreenAction(a: ScreenAction) {
        screenTaps++
        if (screenTaps > maxScreenTaps) {
            stopLoop("Stuck on a screen (pressed $maxScreenTaps buttons without reaching a board)")
            return
        }
        val (label, wait) = when (a.kind) {
            kSkip -> Pair("Skipping golden-fish level…", nextWaitMs)
            kPopup -> Pair("Closing popup…", popupWaitMs)
            kRestart -> Pair("Out of fishes: restarting…", restartWaitMs)
            else -> Pair("Next level…", nextWaitMs)
        }
        showMessage(label)
        tapOnce(a.x.toFloat(), a.y.toFloat()) {
            loop.postDelayed({ solveStep() }, wait)
        }
    }

    private fun afterSolved() {
        levelsDone++
        showMessage("Level $levelsDone solved 🐱 waiting 3.5 s")
        loop.postDelayed({ findNext(0) }, solvedWaitMs)
    }

    private fun findNext(attempt: Int) {
        if (!running) return
        grab { bmp, err ->
            if (!running) return@grab
            var act: ScreenAction? = null
            if (bmp != null) {
                try { act = findScreenAction(pixelsOf(bmp), bmp.width, bmp.height) } catch (_: Throwable) {}
                bmp.recycle()
            }
            main.post {
                if (!running) return@post
                val a = act
                if (a == null) {
                    if (attempt >= 4) stopLoop("Next-level button not found ${err ?: ""}")
                    else loop.postDelayed({ findNext(attempt + 1) }, 2000)
                } else {
                    pressScreenAction(a)
                }
            }
        }
    }

    // ---------- screenshot ----------
    /** Calls back on the worker thread with the screenshot, or null plus a reason. */
    private fun grab(onResult: (Bitmap?, String?) -> Unit) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = result.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hw.close()
                    onResult(bmp, if (bmp == null) "null bitmap" else null)
                }

                override fun onFailure(errorCode: Int) {
                    onResult(null, "code $errorCode")
                }
            })
        } catch (e: Exception) {
            onResult(null, "${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun pixelsOf(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        if (pxBuf.size != w * h) pxBuf = IntArray(w * h)
        bmp.getPixels(pxBuf, 0, w, 0, 0, w, h)
        return pxBuf
    }

    // ---------- board detection ----------
    private fun ch(p: Int, shift: Int) = (p shr shift) and 0xFF

    private fun differs(p: Int) =
        abs(ch(p, 16) - bgR) + abs(ch(p, 8) - bgG) + abs(ch(p, 0) - bgB) > 45

    /** Longest run of true, treating gaps of up to maxGap false values as part of the run. */
    private fun longestRun(mask: BooleanArray, maxGap: Int): Pair<Int, Int> {
        val runs = ArrayList<IntArray>()
        var start = -1
        for (i in 0..mask.size) {
            val v = i < mask.size && mask[i]
            if (v && start < 0) start = i
            else if (!v && start >= 0) { runs.add(intArrayOf(start, i)); start = -1 }
        }
        val merged = ArrayList<IntArray>()
        for (r in runs) {
            if (merged.isNotEmpty() && r[0] - merged.last()[1] <= maxGap) merged.last()[1] = r[1]
            else merged.add(r)
        }
        val best = merged.maxByOrNull { it[1] - it[0] } ?: return Pair(0, 0)
        return Pair(best[0], best[1])
    }

    /** Board = biggest block that differs from the page background (strided scan). */
    private fun findBoard(px: IntArray, w: Int, h: Int): IntArray? {
        val bgp = px[(h / 2) * w + 5]
        bgR = ch(bgp, 16); bgG = ch(bgp, 8); bgB = ch(bgp, 0)

        val rowMask = BooleanArray(h) { y ->
            var c = 0
            var x = 0
            val base = y * w
            while (x < w) { if (differs(px[base + x])) c++; x += 3 }
            c * 3 > w * 0.5
        }
        val gap = (w * 0.017).toInt()
        val (r0, r1) = longestRun(rowMask, gap)
        if (r1 - r0 < 100 || r1 - r0 > h * 0.9) {
            diag = "rows $r0-$r1 of $h"
            return null
        }

        val colMask = BooleanArray(w) { x ->
            var c = 0
            var cnt = 0
            var y = r0
            while (y < r1) { if (differs(px[y * w + x])) c++; cnt++; y += 3 }
            c > cnt * 0.5
        }
        val (c0, c1) = longestRun(colMask, gap)
        if (c1 - c0 < 100) {
            diag = "rows $r0-$r1, cols $c0-$c1 of $w"
            return null
        }
        return intArrayOf(c0, r0, c1, r1)
    }

    /**
     * Grid size from the cell gaps: for the true N, every expected gap position is empty
     * (background colour). Wrong sizes put gap positions inside cells. Not a board -> null.
     */
    private fun pickBoardSize(px: IntArray, w: Int, box: IntArray): Int? {
        val x0 = box[0]
        val y0 = box[1]
        val bw = box[2] - x0
        val bh = box[3] - y0
        val colCnt = IntArray(bw)
        val rowCnt = IntArray(bh)
        for (x in 0 until bw) {
            var y = 0
            while (y < bh) { if (differs(px[(y0 + y) * w + x0 + x])) colCnt[x]++; y += 3 }
        }
        for (y in 0 until bh) {
            var x = 0
            val base = (y0 + y) * w + x0
            while (x < bw) { if (differs(px[base + x])) rowCnt[y]++; x += 3 }
        }
        val colN = ((bh + 2) / 3).toDouble()
        val rowN = ((bw + 2) / 3).toDouble()

        var best = -1
        var bestScore = 2.0
        for (n in 4..12) {
            var score = 0.0
            val pw = bw / (n - 0.085)
            val ph = bh / (n - 0.085)
            for (k in 1 until n) {
                val gx = (k * pw - 0.0425 * pw).toInt()
                val gy = (k * ph - 0.0425 * ph).toInt()
                for (d in -2..2) {
                    if (gx + d in 0 until bw) score = maxOf(score, colCnt[gx + d] / colN)
                    if (gy + d in 0 until bh) score = maxOf(score, rowCnt[gy + d] / rowN)
                }
            }
            if (score < bestScore) { bestScore = score; best = n }
        }
        return if (bestScore < 0.25) best else null
    }

    private fun dist(a: IntArray, b: IntArray) =
        abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])

    private fun avgPatch(px: IntArray, w: Int, cx: Int, cy: Int, hh: Int, out: IntArray) {
        var r = 0; var g = 0; var b = 0; var n = 0
        for (y in cy - hh until cy + hh) for (x in cx - hh until cx + hh) {
            val p = px[y * w + x]
            r += ch(p, 16); g += ch(p, 8); b += ch(p, 0); n++
        }
        out[0] = r / n; out[1] = g / n; out[2] = b / n
    }

    // sample spots inside a cell (fractions of the cell): corners and edge mid-points.
    // The centre is skipped because X marks and the cat icon live there.
    private val spotX = doubleArrayOf(.12, .88, .12, .88, .5, .5, .12, .88)
    private val spotY = doubleArrayOf(.12, .12, .88, .88, .12, .88, .5, .5)

    /** Reads region letters for each cell, and finds cats that are already placed. */
    private fun readBoard(px: IntArray, w: Int, box: IntArray, n: Int, tol: Int): Board {
        val pw = (box[2] - box[0]) / (n - 0.085)
        val ph = (box[3] - box[1]) / (n - 0.085)
        val hh = maxOf(2, (pw * 0.03).toInt())
        val centers = ArrayList<IntArray>()
        val grid = ArrayList<String>()
        val forced = IntArray(n) { -1 }
        val samples = Array(8) { IntArray(3) }

        for (r in 0 until n) {
            val sb = StringBuilder()
            for (c in 0 until n) {
                for (i in 0 until 8) {
                    val cx = (box[0] + c * pw + spotX[i] * pw * 0.915).toInt()
                    val cy = (box[1] + r * ph + spotY[i] * ph * 0.915).toInt()
                    avgPatch(px, w, cx, cy, hh, samples[i])
                }
                // vote: the colour most of the 8 spots agree on is the cell's region colour
                var bestI = 0
                var bestV = -1
                for (i in 0 until 8) {
                    var v = 0
                    for (j in 0 until 8) if (dist(samples[i], samples[j]) < 24) v++
                    if (v > bestV) { bestV = v; bestI = i }
                }
                val col = samples[bestI]
                var idx = centers.indexOfFirst { dist(it, col) < tol }
                if (idx < 0) { centers.add(col.copyOf()); idx = centers.size - 1 }
                sb.append('A' + idx)

                // already-placed cat: black fur pixels in the middle of the cell
                if (forced[r] < 0) {
                    val xa = (box[0] + c * pw + 0.25 * pw * 0.915).toInt()
                    val xb = (box[0] + c * pw + 0.75 * pw * 0.915).toInt()
                    val ya = (box[1] + r * ph + 0.25 * ph * 0.915).toInt()
                    val yb = (box[1] + r * ph + 0.75 * ph * 0.915).toInt()
                    var dark = 0
                    var tot = 0
                    var y = ya
                    while (y < yb) {
                        var x = xa
                        while (x < xb) {
                            val p = px[y * w + x]
                            if (ch(p, 16) + ch(p, 8) + ch(p, 0) < 150) dark++
                            tot++
                            x += 3
                        }
                        y += 3
                    }
                    if (tot > 0 && dark > tot * 0.06) forced[r] = c
                }
            }
            grid.add(sb.toString())
        }
        return Board(grid, centers.size, forced)
    }

    /** Reads the board and solves it. Cats already on the board are treated as fixed. */
    private fun analyze(px: IntArray, w: Int, h: Int): Solved? {
        val box = findBoard(px, w, h) ?: run {
            lastError = "Board not found ($diag)"
            return null
        }
        val n = pickBoardSize(px, w, box) ?: run {
            lastError = "No board on screen"
            return null
        }
        for (tol in intArrayOf(30, 20, 45, 60, 12)) {
            val b = readBoard(px, w, box, n, tol)
            if (b.colors != n) continue
            val s1 = solve(b.grid, b.forced)
            if (s1 != null) return Solved(box, n, s1, BooleanArray(n) { b.forced[it] >= 0 })
            val s2 = solve(b.grid, IntArray(n) { -1 })       // pre-placed cats misread? ignore them
            if (s2 != null) return Solved(box, n, s2, BooleanArray(n))
        }
        lastError = "Couldn't read/solve ${n}x$n board. Box=${box.toList()}"
        return null
    }

    /** Backtracking; rows listed in `forced` (row -> column) are fixed to that column. */
    private fun solve(grid: List<String>, forced: IntArray): IntArray? {
        val n = grid.size
        val cols = IntArray(n) { -1 }
        val usedCols = HashSet<Int>()
        val usedRegions = HashSet<Char>()

        fun go(r: Int): Boolean {
            if (r == n) return true
            val from = if (forced[r] >= 0) forced[r] else 0
            val to = if (forced[r] >= 0) forced[r] else n - 1
            for (c in from..to) {
                val reg = grid[r][c]
                if (c in usedCols || reg in usedRegions) continue
                if (r > 0 && abs(cols[r - 1] - c) <= 1) continue
                cols[r] = c; usedCols.add(c); usedRegions.add(reg)
                if (go(r + 1)) return true
                usedCols.remove(c); usedRegions.remove(reg); cols[r] = -1
            }
            return false
        }
        return if (go(0)) cols else null
    }

    // ---------- non-board screens: next level / skip / popups / out of fishes ----------
    private fun pillColor(kind: Int, r: Int, g: Int, b: Int): Boolean =
        if (kind == 0) r > 205 && g in 125..172 && b in 35..90 && r - b > 130     // orange button
        else r in 195..240 && g in 215..250 && b in 238..255 && b - r > 15       // light-blue "Restart"

    /** Big pill-shaped button of the given colour: returns [left, top, right, bottom] or null. */
    private fun findPill(px: IntArray, w: Int, h: Int, kind: Int): IntArray? {
        val rowMask = BooleanArray(h) { y ->
            var c = 0
            var x = 0
            val base = y * w
            while (x < w) {
                val p = px[base + x]
                if (pillColor(kind, ch(p, 16), ch(p, 8), ch(p, 0))) c++
                x += 3
            }
            c * 3 > w * 0.35
        }
        val (r0, r1) = longestRun(rowMask, 0)
        if (r1 - r0 < 60) return null

        val topY = r0 + (r1 - r0) / 6                     // top slice: no label text in the way
        val xs = IntArray(w)
        var m = 0
        for (x in 0 until w) {
            val p = px[topY * w + x]
            if (pillColor(kind, ch(p, 16), ch(p, 8), ch(p, 0))) xs[m++] = x
        }
        if (m < w * 0.3) return null
        return intArrayOf(xs[m * 5 / 100], r0, xs[m * 95 / 100], r1)
    }

    /** Small light text under the next-level pill ("Skip to level XX"): returns its centre or null. */
    private fun findSkip(px: IntArray, w: Int, h: Int, pillBottom: Int): IntArray? {
        var count = 0
        var sumX = 0L
        var sumY = 0L
        var y = pillBottom + 10
        val yEnd = minOf(h, pillBottom + 300)
        while (y < yEnd) {
            var x = (w * 0.1).toInt()
            val xEnd = (w * 0.9).toInt()
            while (x < xEnd) {
                val p = px[y * w + x]
                val r = ch(p, 16); val g = ch(p, 8); val b = ch(p, 0)
                if (minOf(r, g, b) > 180 && maxOf(r, g, b) - minOf(r, g, b) < 50) {
                    count++; sumX += x; sumY += y
                }
                x += 2
            }
            y += 2
        }
        if (count < 150) return null
        return intArrayOf((sumX / count).toInt(), (sumY / count).toInt())
    }

    private fun findScreenAction(px: IntArray, w: Int, h: Int): ScreenAction? {
        val lm = px[(h / 2) * w + 5]
        val darkScreen = ch(lm, 16) + ch(lm, 8) + ch(lm, 0) < 250      // win screen has a dark overlay

        val o = findPill(px, w, h, 0)
        if (o != null) {
            val cx = (o[0] + o[2]) / 2
            val cy = (o[1] + o[3]) / 2
            // a popup card is cream-coloured just above its button; the win screen is dark there
            val above = px[maxOf(0, o[1] - 30) * w + o[0] + 20]
            if (minOf(ch(above, 16), ch(above, 8), ch(above, 0)) > 235) return ScreenAction(cx, cy, kPopup)
            if (darkScreen) {
                val sk = findSkip(px, w, h, o[3])
                if (sk != null) return ScreenAction(sk[0], sk[1], kSkip)   // golden-fish level: skip it
            }
            return ScreenAction(cx, cy, kNext)
        }
        val b = findPill(px, w, h, 1)
        if (b != null) return ScreenAction((b[0] + b[2]) / 2, (b[1] + b[3]) / 2, kRestart)
        return null
    }

    // ---------- tapping ----------
    // Cells have ~8.5% gaps: board width = n*pitch - gap  =>  pitch = width / (n - 0.085)
    private fun pitchX(box: IntArray, n: Int) = (box[2] - box[0]) / (n - 0.085)
    private fun pitchY(box: IntArray, n: Int) = (box[3] - box[1]) / (n - 0.085)

    private fun tapCats(box: IntArray, n: Int, sol: IntArray, placed: BooleanArray, i: Int) {
        if (!running) return
        if (i >= n) { afterSolved(); return }
        if (placed[i]) { tapCats(box, n, sol, placed, i + 1); return }   // cat already there

        val x = (box[0] + (sol[i] + 0.46) * pitchX(box, n)).toFloat()
        val y = (box[1] + (i + 0.46) * pitchY(box, n)).toFloat()

        // the game wants a double-tap: two short taps 130 ms apart
        val p1 = Path().apply { moveTo(x, y) }
        val p2 = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, 40))
            .addStroke(GestureDescription.StrokeDescription(p2, 130, 40))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                loop.postDelayed({ tapCats(box, n, sol, placed, i + 1) }, 250)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                stopLoop("Tap cancelled")
            }
        }, null)
    }

    private fun tapOnce(x: Float, y: Float, then: () -> Unit) {
        val p = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { then() }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                stopLoop("Tap cancelled")
            }
        }, null)
    }
}
