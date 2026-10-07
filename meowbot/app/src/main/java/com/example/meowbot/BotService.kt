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

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var wm: WindowManager? = null
    private var button: TextView? = null
    private var busy = false
    private var banner: TextView? = null
    private var diag = ""

    /** On-screen message that stays ~6 s (toasts get suppressed on some phones). */
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
        try { button?.let { wm?.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
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

    private fun onButtonTap() {
        if (busy) return
        busy = true
        banner?.let { try { wm?.removeView(it) } catch (_: Exception) {}; banner = null }
        button?.visibility = View.INVISIBLE          // keep it out of the screenshot
        main.postDelayed({ capture() }, 250)
    }

    private fun done(msg: String) {
        busy = false
        main.post {
            if (gamePkg.isEmpty()) button?.visibility = View.VISIBLE
        }
        showMessage(msg)
    }

    // ---------- screenshot ----------
    private fun capture() {
        try { doCapture() } catch (e: Exception) { done("Capture error: ${e.javaClass.simpleName} ${e.message}") }
    }

    private fun doCapture() {
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (bmp == null) { done("Screenshot failed (null bitmap)"); return }
                try { process(bmp) } catch (e: Throwable) {
                    done("Error: ${e.javaClass.simpleName} ${e.message}")
                }
            }

            override fun onFailure(errorCode: Int) {
                done("Screenshot failed (code $errorCode)")
            }
        })
    }

    // ---------- detect + solve ----------
    private fun process(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val box = findBoard(px, w, h) ?: return done("Board not found ($diag)")
        val sized = detectSize(px, w, box) ?: return done(
            "Couldn't read the board. Box=${box.toList()} screen=${w}x$h. Colors per size: " +
                (4..12).joinToString(" ") { "$it:${readGrid(px, w, box, it).second}" }
        )
        val n = sized.first
        val sol = solve(sized.second) ?: return done("No solution (colors misread?)")

        main.post {
            showMessage("Found ${n}x$n board, solving…")
            tapCats(box, n, sol, 0)
        }
    }

    private fun ch(p: Int, shift: Int) = (p shr shift) and 0xFF

    private fun longestRun(mask: BooleanArray): Pair<Int, Int> {
        var best = Pair(0, 0)
        var start = -1
        for (i in 0..mask.size) {
            val v = i < mask.size && mask[i]
            if (v && start < 0) start = i
            else if (!v && start >= 0) {
                if (i - start > best.second - best.first) best = Pair(start, i)
                start = -1
            }
        }
        return best
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
        val (r0, r1) = longestRun(rowMask)
        if (r1 - r0 < 100 || r1 - r0 > h * 0.9) {
            diag = "rows $r0-$r1 of $h, bg=${bg.toList()}"
            return null
        }

        val colMask = BooleanArray(w) { x ->
            var c = 0
            for (y in r0 until r1) if (differs(x, y)) c++
            c > (r1 - r0) * 0.8
        }
        val (c0, c1) = longestRun(colMask)
        if (c1 - c0 < 100) {
            diag = "rows $r0-$r1, cols $c0-$c1 of $w"
            return null
        }
        return intArrayOf(c0, r0, c1, r1)
    }

    private fun cellColor(px: IntArray, w: Int, box: IntArray, n: Int, r: Int, c: Int): IntArray {
        val cw = (box[2] - box[0]).toDouble() / n
        val chh = (box[3] - box[1]).toDouble() / n
        val cx = (box[0] + (c + 0.28) * cw).toInt()      // near top-left: avoids the cat icon
        val cy = (box[1] + (r + 0.28) * chh).toInt()
        val hh = maxOf(2, (minOf(cw, chh) * 0.06).toInt())
        val chans = Array(3) { ArrayList<Int>() }
        for (y in cy - hh until cy + hh) for (x in cx - hh until cx + hh) {
            val p = px[y * w + x]
            chans[0].add(ch(p, 16)); chans[1].add(ch(p, 8)); chans[2].add(ch(p, 0))
        }
        return IntArray(3) { chans[it].sort(); chans[it][chans[it].size / 2] }
    }

    private fun readGrid(px: IntArray, w: Int, box: IntArray, n: Int): Pair<List<String>, Int> {
        val centers = ArrayList<IntArray>()
        val grid = ArrayList<String>()
        for (r in 0 until n) {
            val sb = StringBuilder()
            for (c in 0 until n) {
                val col = cellColor(px, w, box, n, r, c)
                var idx = centers.indexOfFirst {
                    abs(it[0] - col[0]) + abs(it[1] - col[1]) + abs(it[2] - col[2]) < 30
                }
                if (idx < 0) { centers.add(col); idx = centers.size - 1 }
                sb.append('A' + idx)
            }
            grid.add(sb.toString())
        }
        return Pair(grid, centers.size)
    }

    /** Try N = 4..12; only the true size gives exactly N distinct colors. */
    private fun detectSize(px: IntArray, w: Int, box: IntArray): Pair<Int, List<String>>? {
        for (n in 4..12) {
            val (grid, k) = readGrid(px, w, box, n)
            if (k == n) return Pair(n, grid)
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
        if (i >= n) { done("Solved! 🐱"); return }
        val cw = (box[2] - box[0]).toFloat() / n
        val chh = (box[3] - box[1]).toFloat() / n
        val x = box[0] + (sol[i] + 0.5f) * cw
        val y = box[1] + (i + 0.5f) * chh

        // the game wants a double-tap: two short taps 130 ms apart
        val p1 = Path().apply { moveTo(x, y) }
        val p2 = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, 40))
            .addStroke(GestureDescription.StrokeDescription(p2, 130, 40))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                main.postDelayed({ tapCats(box, n, sol, i + 1) }, 250)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done("Tap cancelled")
            }
        }, null)
    }
}
