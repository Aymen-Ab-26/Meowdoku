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

    private val waitMs = 4000L          // pause on the win screen, and after pressing "next level"
    private val maxFailures = 6         // consecutive failed reads before the loop stops itself

    private val main = Handler(Looper.getMainLooper())
    private val loop = Handler(Looper.getMainLooper())   // timers for the auto-play loop
    private val worker = Executors.newSingleThreadExecutor()
    private var wm: WindowManager? = null
    private var button: TextView? = null
    private var banner: TextView? = null
    private var diag = ""
    private var lastError = ""

    @Volatile private var running = false
    private var failures = 0
    private var levelsDone = 0

    class Solved(val box: IntArray, val n: Int, val sol: IntArray)

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
                val res = try { analyze(bmp) } catch (e: Throwable) {
                    lastError = "Error: ${e.javaClass.simpleName} ${e.message}"
                    null
                }
                main.post {
                    if (!running) return@post
                    if (res == null) {
                        retryOrStop(lastError)
                    } else {
                        failures = 0
                        showMessage("Level ${levelsDone + 1}: ${res.n}x${res.n} board, solving…")
                        tapCats(res.box, res.n, res.sol, 0)
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
        loop.postDelayed({ solveStep() }, waitMs)
    }

    private fun afterSolved() {
        levelsDone++
        showMessage("Level $levelsDone solved 🐱 waiting 4 s")
        loop.postDelayed({ findNext(0) }, waitMs)
    }

    private fun findNext(attempt: Int) {
        if (!running) return
        grab { bmp, err ->
            if (!running) return@grab
            val pt = if (bmp != null) {
                try { findNextButton(bmp) } catch (_: Throwable) { null }
            } else null
            main.post {
                if (!running) return@post
                if (pt == null) {
                    if (attempt >= 4) stopLoop("Next-level button not found ${err ?: ""}")
                    else loop.postDelayed({ findNext(attempt + 1) }, 2000)
                } else {
                    tapOnce(pt[0].toFloat(), pt[1].toFloat()) {
                        showMessage("Next level… waiting 4 s")
                        loop.postDelayed({ solveStep() }, waitMs)
                    }
                }
            }
        }
    }

    // ---------- screenshot ----------
    /** Calls back on a worker thread with the screenshot, or null plus a reason. */
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

    // ---------- detect + solve ----------
    private fun analyze(bmp: Bitmap): Solved? {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val box = findBoard(px, w, h) ?: run {
            lastError = "Board not found ($diag)"
            return null
        }
        val rd = readAndSolve(px, w, box) ?: run {
            lastError = "Couldn't read/solve. Box=${box.toList()} screen=${w}x$h. Colors per size: " +
                (4..12).joinToString(" ") { "$it:${readGrid(px, w, box, it, 30, 0.16, 0.5).second}" }
            return null
        }
        return Solved(box, rd.n, rd.sol)
    }

    /** Win screen = dark overlay + a big orange pill button. Returns its centre, or null. */
    private fun findNextButton(bmp: Bitmap): IntArray? {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val bgp = px[(h / 2) * w + 4]
        if (ch(bgp, 16) + ch(bgp, 8) + ch(bgp, 0) > 250) return null   // game screen is light, win screen is dark

        fun orange(p: Int): Boolean {
            val r = ch(p, 16); val g = ch(p, 8); val b = ch(p, 0)
            return r > 200 && g in 120..190 && b < 100 && r - b > 120
        }

        val rowMask = BooleanArray(h) { y ->
            var c = 0
            for (x in 0 until w) if (orange(px[y * w + x])) c++
            c > w * 0.35
        }
        val (r0, r1) = longestRun(rowMask, 0)
        if (r1 - r0 < 60) return null

        val y = (r0 + r1) / 2
        val topY = r0 + (r1 - r0) / 6                     // top slice: no label text in the way
        val xs = ArrayList<Int>()
        for (x in 0 until w) if (orange(px[topY * w + x])) xs.add(x)
        if (xs.size < w * 0.3) return null
        xs.sort()
        val left = xs[xs.size * 5 / 100]
        val right = xs[xs.size * 95 / 100]
        return intArrayOf((left + right) / 2, y)
    }

    private fun ch(p: Int, shift: Int) = (p shr shift) and 0xFF

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

    /** Board = biggest block that differs from the page background. */
    private fun findBoard(px: IntArray, w: Int, h: Int): IntArray? {
        // background sampled from the left margin at mid-screen
        val bx = 4
        val by = h / 2
        val bg = IntArray(3)
        for ((i, s) in intArrayOf(16, 8, 0).withIndex()) bg[i] = ch(px[by * w + bx], s)

        fun differs(x: Int, y: Int): Boolean {
            val p = px[y * w + x]
            return abs(ch(p, 16) - bg[0]) + abs(ch(p, 8) - bg[1]) + abs(ch(p, 0) - bg[2]) > 45
        }

        val rowMask = BooleanArray(h) { y ->
            var c = 0
            for (x in 0 until w) if (differs(x, y)) c++
            c > w * 0.5
        }
        val gap = (w * 0.017).toInt()
        val (r0, r1) = longestRun(rowMask, gap)
        if (r1 - r0 < 100 || r1 - r0 > h * 0.9) {
            diag = "rows $r0-$r1 of $h, bg=${bg.toList()}"
            return null
        }

        val colMask = BooleanArray(w) { x ->
            var c = 0
            for (y in r0 until r1) if (differs(x, y)) c++
            c > (r1 - r0) * 0.5
        }
        val (c0, c1) = longestRun(colMask, gap)
        if (c1 - c0 < 100) {
            diag = "rows $r0-$r1, cols $c0-$c1 of $w"
            return null
        }
        return intArrayOf(c0, r0, c1, r1)
    }

    // Cells have ~8.5% gaps: board width = n*pitch - gap  =>  pitch = width / (n - 0.085)
    private fun pitchX(box: IntArray, n: Int) = (box[2] - box[0]) / (n - 0.085)
    private fun pitchY(box: IntArray, n: Int) = (box[3] - box[1]) / (n - 0.085)

    private fun cellColor(px: IntArray, w: Int, box: IntArray, n: Int, r: Int, c: Int, ox: Double, oy: Double): IntArray {
        val pw = pitchX(box, n)
        val ph = pitchY(box, n)
        val cx = (box[0] + c * pw + ox * pw).toInt()
        val cy = (box[1] + r * ph + oy * ph).toInt()
        val hh = maxOf(2, (pw * 0.04).toInt())
        val chans = Array(3) { ArrayList<Int>() }
        for (y in cy - hh until cy + hh) for (x in cx - hh until cx + hh) {
            val p = px[y * w + x]
            chans[0].add(ch(p, 16)); chans[1].add(ch(p, 8)); chans[2].add(ch(p, 0))
        }
        return IntArray(3) { chans[it].sort(); chans[it][chans[it].size / 2] }
    }

    private fun readGrid(px: IntArray, w: Int, box: IntArray, n: Int, tol: Int, ox: Double, oy: Double): Pair<List<String>, Int> {
        val centers = ArrayList<IntArray>()
        val grid = ArrayList<String>()
        for (r in 0 until n) {
            val sb = StringBuilder()
            for (c in 0 until n) {
                val col = cellColor(px, w, box, n, r, c, ox, oy)
                var idx = centers.indexOfFirst {
                    abs(it[0] - col[0]) + abs(it[1] - col[1]) + abs(it[2] - col[2]) < tol
                }
                if (idx < 0) { centers.add(col); idx = centers.size - 1 }
                sb.append('A' + idx)
            }
            grid.add(sb.toString())
        }
        return Pair(grid, centers.size)
    }

    class Reading(val n: Int, val grid: List<String>, val sol: IntArray)

    /** Try several sampling spots / color tolerances; accept the first size N that gives
     *  exactly N colors AND a solvable puzzle. */
    private fun readAndSolve(px: IntArray, w: Int, box: IntArray): Reading? {
        val spots = listOf(Pair(0.16, 0.5), Pair(0.5, 0.16), Pair(0.84, 0.5), Pair(0.5, 0.84))
        for ((ox, oy) in spots) for (tol in intArrayOf(30, 20, 45, 60, 12)) {
            for (n in 4..12) {
                val (grid, k) = readGrid(px, w, box, n, tol, ox, oy)
                if (k != n) continue
                val sol = solve(grid) ?: continue
                return Reading(n, grid, sol)
            }
        }
        return null
    }

    private fun solve(grid: List<String>): IntArray? {
        val n = grid.size
        val cols = IntArray(n) { -1 }
        val usedCols = HashSet<Int>()
        val usedRegions = HashSet<Char>()

        fun go(r: Int): Boolean {
            if (r == n) return true
            for (c in 0 until n) {
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

    // ---------- tapping ----------
    private fun tapCats(box: IntArray, n: Int, sol: IntArray, i: Int) {
        if (!running) return
        if (i >= n) { afterSolved(); return }
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
                loop.postDelayed({ tapCats(box, n, sol, i + 1) }, 250)
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
