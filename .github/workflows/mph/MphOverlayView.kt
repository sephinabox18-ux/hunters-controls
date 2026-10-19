package me.magnum.melonds.ui.emulator.mph

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.Rect as DsRect
import me.magnum.melonds.domain.model.rom.Rom
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phone-native multi-touch controls for Metroid Prime Hunters (Stylus Mode, right-handed).
 *
 * The DS only sees one stylus point and a set of buttons. This view tracks every finger on the
 * phone and merges them into that single stylus + buttons:
 *   left thumb  -> floating stick -> D-pad (move / strafe)
 *   right thumb -> stylus drag on the (hidden) bottom screen -> aim
 *   FIRE        -> L (tap = one shot, hold = auto/charge, hold + slide = fire while aiming)
 *   JUMP        -> B (face buttons jump in right-handed Stylus Mode)
 *   BOOST       -> R
 *   MORPH / SCAN / BEAM / MISSILE / SPECIAL -> stylus taps on the bottom-screen icons
 */
class MphOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        // ---- Bottom-screen icon positions (DS pixels, 256x192). Tweak here if a tap misses. ----
        const val BEAM_X = 85;     const val BEAM_Y = 32
        const val MISSILE_X = 125; const val MISSILE_Y = 32
        const val SPECIAL_X = 165; const val SPECIAL_Y = 32
        const val MORPH_X = 228;   const val MORPH_Y = 32
        const val SCAN_X = 128;    const val SCAN_Y = 168

        // Safe aiming box on the bottom screen (stays clear of the icons).
        const val AIM_CENTER_X = 128f; const val AIM_CENTER_Y = 104f
        const val AIM_RANGE_X = 76f;   const val AIM_RANGE_Y = 40f

        // Timing (ms). If recentering ever causes an accidental jump, raise RELEASE_GAP_MS.
        const val RELEASE_GAP_MS = 40L
        const val TAP_HOLD_MS = 70L

        private const val PREFS = "mph_controls"
    }

    // ---------------------------------------------------------------- state

    /** Tells the renderer where to draw the DS top and bottom screens (view pixels). */
    var screenAreaApplier: ((DsRect?, DsRect?) -> Unit)? = null
    /** Called when Hunters mode switches on/off so the normal layout can be hidden/restored. */
    var onActiveChanged: ((Boolean) -> Unit)? = null

    // Screen areas: the big centred screen and the small radar in the top-left.
    private val bigRect = RectF()
    private val smallRect = RectF()

    private var active = false
    private var menuMode = false

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var sensitivity = prefs.getFloat("sens", 0.8f)
    private var invertY = prefs.getBoolean("invertY", false)
    private var opacity = prefs.getInt("opacity", 55)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private enum class Kind(val label: String) {
        FIRE("FIRE"), JUMP("JUMP"), BOOST("BOOST"), MORPH("BALL"), SCAN("SCAN"),
        BEAM("BEAM"), MISSILE("MSL"), SPECIAL("WPN"), START("START"), MENU("MENU"), SETTINGS("⚙"), BACK("BACK")
    }

    private class Btn(val kind: Kind, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f, var pressed: Boolean = false)

    private val buttons = Kind.values().associateWith { Btn(it) }

    private sealed class Role {
        object Stick : Role()
        object Aim : Role()
        class Button(val kind: Kind) : Role()
        class Direct(val rect: RectF) : Role()
        object Ignored : Role()
    }

    private val roles = HashMap<Int, Role>()

    // Stick
    private var stickId = -1
    private var stickOx = 0f; private var stickOy = 0f
    private var stickX = 0f; private var stickY = 0f
    private val heldDirs = HashSet<Input>()

    // Aim (plain right-thumb aim, or a FIRE press that started sliding)
    private var aimId = -1
    private var aimLastX = 0f; private var aimLastY = 0f
    private var fireId = -1
    private var fireDownX = 0f; private var fireDownY = 0f
    private var fireAiming = false

    // Special-weapon drag
    private var specialId = -1
    private var specialLastX = 0f; private var specialLastY = 0f

    // Direct touch: a finger on the radar (or the big touch screen in menu mode) drives the stylus 1:1.
    private var directMode = false
    private var directX = 0f
    private var directY = 0f

    private val heldInputs = HashSet<Input>()

    // ---------------------------------------------------------------- stylus state machine

    private sealed class Step {
        class Press(val x: Float, val y: Float) : Step()
        object Release : Step()
        class Wait(val ms: Long) : Step()
    }

    private val queue = ArrayDeque<Step>()
    private var waitUntil = 0L
    private var touching = false
    private var sx = AIM_CENTER_X
    private var sy = AIM_CENTER_Y
    private var lastSentX = -1
    private var lastSentY = -1
    private var lastReleaseAt = 0L
    private var accX = 0f
    private var accY = 0f
    private var specialMode = false

    private fun stylusPress(x: Float, y: Float) {
        sx = x; sy = y
        val ix = x.roundToInt().coerceIn(0, 255)
        val iy = y.roundToInt().coerceIn(0, 191)
        MelonEmulator.onInputDown(Input.TOUCHSCREEN)
        MelonEmulator.onScreenTouch(ix, iy)
        lastSentX = ix; lastSentY = iy
        touching = true
    }

    private fun stylusMove(x: Float, y: Float) {
        sx = x; sy = y
        val ix = x.roundToInt().coerceIn(0, 255)
        val iy = y.roundToInt().coerceIn(0, 191)
        if (ix != lastSentX || iy != lastSentY) {
            MelonEmulator.onScreenTouch(ix, iy)
            lastSentX = ix; lastSentY = iy
        }
    }

    private fun stylusRelease() {
        if (!touching) return
        MelonEmulator.onInputUp(Input.TOUCHSCREEN)
        MelonEmulator.onScreenRelease()
        touching = false
        lastReleaseAt = SystemClock.uptimeMillis()
    }

    private fun aimWanted() = aimId != -1 || fireAiming

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return
            tick()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()

        // 1. Scripted steps (icon taps, gaps) take priority.
        while (queue.isNotEmpty()) {
            if (now < waitUntil) return
            when (val step = queue.removeFirst()) {
                is Step.Press -> stylusPress(step.x, step.y)
                is Step.Release -> stylusRelease()
                is Step.Wait -> { waitUntil = now + step.ms; return }
            }
        }

        // 2. Direct touch on the radar / menu screen.
        if (directMode) {
            if (touching) stylusMove(directX, directY)
            accX = 0f; accY = 0f
            return
        }

        // 2b. Special-weapon drag: stylus follows the finger from the icon, no recentering.
        if (specialMode) {
            if (touching) {
                stylusMove((sx + accX).coerceIn(0f, 255f), (sy + accY).coerceIn(0f, 191f))
            }
            accX = 0f; accY = 0f
            return
        }

        // 3. Aiming.
        if (aimWanted()) {
            if (!touching) {
                if (now - lastReleaseAt < RELEASE_GAP_MS) return
                // Fresh press at the centre; movement is applied from the next frame so the
                // game takes this point as its reference instead of snapping the camera.
                stylusPress(AIM_CENTER_X, AIM_CENTER_Y)
                return
            }
            if (accX == 0f && accY == 0f) return
            val nx = sx + accX
            val ny = sy + accY
            if (abs(nx - AIM_CENTER_X) > AIM_RANGE_X || abs(ny - AIM_CENTER_Y) > AIM_RANGE_Y) {
                // Hit the edge of the safe box: move as far as we can, then lift and recentre.
                val cx = nx.coerceIn(AIM_CENTER_X - AIM_RANGE_X, AIM_CENTER_X + AIM_RANGE_X)
                val cy = ny.coerceIn(AIM_CENTER_Y - AIM_RANGE_Y, AIM_CENTER_Y + AIM_RANGE_Y)
                accX = nx - cx
                accY = ny - cy
                stylusMove(cx, cy)
                stylusRelease()
            } else {
                accX = 0f; accY = 0f
                stylusMove(nx, ny)
            }
        } else {
            accX = 0f; accY = 0f
            if (touching) stylusRelease()
        }
    }

    private fun queueTap(x: Int, y: Int) {
        if (touching || queue.isNotEmpty()) {
            queue.addLast(Step.Release)
            queue.addLast(Step.Wait(RELEASE_GAP_MS))
        }
        queue.addLast(Step.Press(x.toFloat(), y.toFloat()))
        queue.addLast(Step.Wait(TAP_HOLD_MS))
        queue.addLast(Step.Release)
        queue.addLast(Step.Wait(RELEASE_GAP_MS))
        accX = 0f; accY = 0f
    }

    private fun addAimDelta(dxPx: Float, dyPx: Float) {
        accX += dxPx / density * sensitivity
        accY += (if (invertY) -dyPx else dyPx) / density * sensitivity
    }

    // ---------------------------------------------------------------- activation

    fun setActiveForRom(rom: Rom) {
        val text = (rom.name + " " + rom.fileName).lowercase()
        val isHunters = text.contains("hunters") && (text.contains("metroid") || text.contains("prime"))
        if (isHunters) activate() else deactivate()
    }

    fun activate() {
        if (active) return
        active = true
        menuMode = false
        visibility = VISIBLE
        Choreographer.getInstance().postFrameCallback(frameCallback)
        onActiveChanged?.invoke(true)
        pushScreenAreas()
        invalidate()
    }

    fun deactivate() {
        if (!active) { visibility = GONE; return }
        releaseAll()
        active = false
        menuMode = false
        visibility = GONE
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        onActiveChanged?.invoke(false)
    }

    /** Sends our screen placement to the renderer. Returns false when Hunters mode is off. */
    fun pushScreenAreas(): Boolean {
        if (!active || width == 0 || height == 0) return false
        val big = DsRect(bigRect.left.toInt(), bigRect.top.toInt(), bigRect.width().toInt(), bigRect.height().toInt())
        val small = DsRect(smallRect.left.toInt(), smallRect.top.toInt(), smallRect.width().toInt(), smallRect.height().toInt())
        // Normal play: game (DS top screen) big, radar (DS bottom screen) small.
        // Menu mode: touch screen big, game small.
        if (menuMode) screenAreaApplier?.invoke(small, big) else screenAreaApplier?.invoke(big, small)
        return true
    }

    private fun releaseAll() {
        heldInputs.toList().forEach { MelonEmulator.onInputUp(it) }
        heldInputs.clear()
        heldDirs.clear()
        roles.clear()
        stickId = -1; aimId = -1; fireId = -1; specialId = -1
        fireAiming = false; specialMode = false; directMode = false
        queue.clear(); waitUntil = 0L
        accX = 0f; accY = 0f
        buttons.values.forEach { it.pressed = false }
        stylusRelease()
        invalidate()
    }

    private fun press(input: Input) {
        if (heldInputs.add(input)) MelonEmulator.onInputDown(input)
    }

    private fun release(input: Input) {
        if (heldInputs.remove(input)) MelonEmulator.onInputUp(input)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus && active) releaseAll()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val W = w.toFloat(); val H = h.toFloat()
        val pad = dp(6f)

        // Big screen: 4:3, full height, centred (no stretching).
        var gw = H * 4f / 3f
        var gh = H
        if (gw > W) { gw = W; gh = W * 3f / 4f }
        bigRect.set((W - gw) / 2f, (H - gh) / 2f, (W + gw) / 2f, (H + gh) / 2f)

        // Radar: ~30% of screen height, top-left, kept inside the left black bar when there's room.
        var rh = H * 0.30f
        var rw = rh * 4f / 3f
        if (bigRect.left > dp(60f) && rw > bigRect.left - pad * 2f) { rw = bigRect.left - pad * 2f; rh = rw * 3f / 4f }
        smallRect.set(pad, pad, pad + rw, pad + rh)

        // Side bar width used to size/place buttons (falls back to 120dp on narrow screens).
        val m = max(bigRect.left, dp(120f))
        fun place(k: Kind, x: Float, y: Float, r: Float) { buttons.getValue(k).apply { cx = x; cy = y; this.r = r } }
        val rFire = min(dp(46f), m * 0.30f)
        val rJump = min(dp(32f), m * 0.21f)
        val rSmall = min(dp(24f), m * 0.15f)
        val rWeapon = min(dp(22f), m * 0.14f)
        val rSys = min(dp(20f), m * 0.12f)

        // Left bar: system buttons under the radar.
        val sysY = smallRect.bottom + rSys + dp(10f)
        place(Kind.MENU, pad + rSys + dp(2f), sysY, rSys)
        place(Kind.START, pad + rSys * 3f + dp(10f), sysY, rSys)
        place(Kind.SETTINGS, pad + rSys * 5f + dp(18f), sysY, rSys)

        // Right bar: weapons along the top, action cluster below.
        val rightEdge = W - m
        place(Kind.BEAM, rightEdge + m * 0.2f, dp(30f), rWeapon)
        place(Kind.MISSILE, rightEdge + m * 0.5f, dp(30f), rWeapon)
        place(Kind.SPECIAL, rightEdge + m * 0.8f, dp(30f), rWeapon)
        place(Kind.SCAN, W - m * 0.72f, H * 0.30f, rSmall)
        place(Kind.BOOST, W - m * 0.25f, H * 0.40f, rSmall)
        place(Kind.MORPH, W - m * 0.72f, H * 0.50f, rSmall)
        place(Kind.FIRE, W - m * 0.40f, H * 0.68f, rFire)
        place(Kind.JUMP, W - m * 0.72f, H * 0.88f, rJump)

        place(Kind.BACK, W - m * 0.5f, H * 0.5f, min(dp(40f), m * 0.35f))

        // Stop Android's edge-swipe back gesture from stealing thumb drags near the bottom edges.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ex = dp(200f).toInt()
            systemGestureExclusionRects = listOf(
                android.graphics.Rect(0, max(0, h - ex), dp(40f).toInt(), h),
                android.graphics.Rect(w - dp(40f).toInt(), max(0, h - ex), w, h),
            )
        }

        pushScreenAreas()
    }

    private fun visibleKinds(): List<Kind> =
        if (menuMode) listOf(Kind.BACK)
        else Kind.values().filter { it != Kind.BACK }

    private fun hit(x: Float, y: Float): Btn? {
        for (k in visibleKinds()) {
            val b = buttons.getValue(k)
            if (hypot(x - b.cx, y - b.cy) <= b.r + dp(6f)) return b
        }
        return null
    }

    private fun mapToDs(r: RectF, x: Float, y: Float): Pair<Float, Float> {
        val dx = ((x - r.left) / r.width() * 256f).coerceIn(0f, 255f)
        val dy = ((y - r.top) / r.height() * 192f).coerceIn(0f, 191f)
        return dx to dy
    }

    private fun startDirect(id: Int, r: RectF, x: Float, y: Float) {
        roles[id] = Role.Direct(r)
        val (dx, dy) = mapToDs(r, x, y)
        directX = dx; directY = dy
        directMode = true
        specialMode = false
        if (touching || queue.isNotEmpty()) {
            queue.addLast(Step.Release)
            queue.addLast(Step.Wait(RELEASE_GAP_MS))
        }
        queue.addLast(Step.Press(dx, dy))
        accX = 0f; accY = 0f
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                pointerDown(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    pointerMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                pointerUp(event.getPointerId(i))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float): Boolean {
        val b = hit(x, y)
        if (menuMode) {
            if (b?.kind == Kind.BACK) {
                roles[id] = Role.Button(Kind.BACK)
                b.pressed = true
                return true
            }
            if (bigRect.contains(x, y) && !directMode) {
                startDirect(id, bigRect, x, y)
                return true
            }
            roles[id] = Role.Ignored
            return true
        }

        if (b != null) {
            roles[id] = Role.Button(b.kind)
            b.pressed = true
            when (b.kind) {
                Kind.FIRE -> {
                    press(Input.L)
                    fireId = id; fireDownX = x; fireDownY = y; fireAiming = false
                    aimLastX = x; aimLastY = y
                }
                Kind.JUMP -> press(Input.B)
                Kind.BOOST -> press(Input.R)
                Kind.START -> press(Input.START)
                Kind.MORPH -> queueTap(MORPH_X, MORPH_Y)
                Kind.SCAN -> queueTap(SCAN_X, SCAN_Y)
                Kind.BEAM -> queueTap(BEAM_X, BEAM_Y)
                Kind.MISSILE -> queueTap(MISSILE_X, MISSILE_Y)
                Kind.SPECIAL -> {
                    // Hold on the icon; sliding picks from the weapon wheel, a quick tap just selects.
                    specialId = id; specialLastX = x; specialLastY = y
                    specialMode = true
                    if (touching || queue.isNotEmpty()) {
                        queue.addLast(Step.Release)
                        queue.addLast(Step.Wait(RELEASE_GAP_MS))
                    }
                    queue.addLast(Step.Press(SPECIAL_X.toFloat(), SPECIAL_Y.toFloat()))
                    accX = 0f; accY = 0f
                }
                Kind.MENU -> {}
                Kind.SETTINGS -> {}
                Kind.BACK -> {}
            }
            return true
        }

        if (smallRect.contains(x, y)) {
            if (!directMode) {
                startDirect(id, smallRect, x, y)
            } else {
                roles[id] = Role.Ignored
            }
            return true
        }

        if (x < width * 0.42f && stickId == -1) {
            roles[id] = Role.Stick
            stickId = id
            stickOx = x; stickOy = y; stickX = x; stickY = y
            return true
        }

        if (aimId == -1 && !fireAiming) {
            roles[id] = Role.Aim
            aimId = id
            aimLastX = x; aimLastY = y
            return true
        }

        roles[id] = Role.Ignored
        return true
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        when (val role = roles[id]) {
            is Role.Stick -> updateStick(x, y)
            is Role.Direct -> {
                val (dx, dy) = mapToDs(role.rect, x, y)
                directX = dx; directY = dy
            }
            is Role.Aim -> {
                addAimDelta(x - aimLastX, y - aimLastY)
                aimLastX = x; aimLastY = y
            }
            is Role.Button -> when (role.kind) {
                Kind.FIRE -> {
                    if (!fireAiming && aimId == -1 && hypot(x - fireDownX, y - fireDownY) > dp(10f)) {
                        fireAiming = true
                        aimLastX = x; aimLastY = y
                    } else if (fireAiming) {
                        addAimDelta(x - aimLastX, y - aimLastY)
                        aimLastX = x; aimLastY = y
                    }
                }
                Kind.SPECIAL -> {
                    accX += (x - specialLastX) / density * 0.6f
                    accY += (y - specialLastY) / density * 0.6f
                    specialLastX = x; specialLastY = y
                }
                else -> {}
            }
            else -> {}
        }
    }

    private fun pointerUp(id: Int) {
        when (val role = roles.remove(id)) {
            is Role.Stick -> {
                stickId = -1
                heldDirs.toList().forEach { release(it) }
                heldDirs.clear()
            }
            is Role.Aim -> aimId = -1
            is Role.Direct -> {
                queue.addLast(Step.Release)
                queue.addLast(Step.Wait(RELEASE_GAP_MS))
                directMode = false
            }
            is Role.Button -> {
                buttons.getValue(role.kind).pressed = false
                when (role.kind) {
                    Kind.FIRE -> { release(Input.L); fireId = -1; fireAiming = false }
                    Kind.JUMP -> release(Input.B)
                    Kind.BOOST -> release(Input.R)
                    Kind.START -> release(Input.START)
                    Kind.SPECIAL -> {
                        specialId = -1
                        queue.addLast(Step.Release)
                        queue.addLast(Step.Wait(RELEASE_GAP_MS))
                        // The queued release runs before any aiming resumes.
                        specialMode = false
                    }
                    Kind.MENU -> toggleMenuMode()
                    Kind.BACK -> toggleMenuMode()
                    Kind.SETTINGS -> showSettings()
                    else -> {}
                }
            }
            else -> {}
        }
    }

    private fun updateStick(x: Float, y: Float) {
        val maxR = dp(60f)
        var dx = x - stickOx
        var dy = y - stickOy
        val d = hypot(dx, dy)
        if (d > maxR) {
            // Floating stick: drag the base along so reversing direction is instant.
            stickOx = x - dx / d * maxR
            stickOy = y - dy / d * maxR
            dx = x - stickOx; dy = y - stickOy
        }
        stickX = x; stickY = y

        val want = HashSet<Input>()
        val dist = hypot(dx, dy)
        if (dist > dp(14f)) {
            val nx = dx / dist; val ny = dy / dist
            if (nx > 0.38f) want.add(Input.RIGHT)
            if (nx < -0.38f) want.add(Input.LEFT)
            if (ny > 0.38f) want.add(Input.DOWN)
            if (ny < -0.38f) want.add(Input.UP)
        }
        for (dir in heldDirs.toList()) if (dir !in want) { release(dir); heldDirs.remove(dir) }
        for (dir in want) if (heldDirs.add(dir)) press(dir)
    }

    private fun toggleMenuMode() {
        releaseAll()
        menuMode = !menuMode
        pushScreenAreas()
        invalidate()
    }

    // ---------------------------------------------------------------- settings

    private fun showSettings() {
        releaseAll()
        val pad = dp(20f).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val sensLabel = TextView(context)
        fun sensText() = "Aim sensitivity: %.1f".format(sensitivity)
        sensLabel.text = sensText()
        val sensBar = SeekBar(context).apply {
            max = 28 // 0.2 .. 3.0
            progress = ((sensitivity - 0.2f) * 10f).roundToInt().coerceIn(0, 28)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    sensitivity = 0.2f + p / 10f
                    sensLabel.text = sensText()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val opLabel = TextView(context).apply { text = "Button opacity" }
        val opBar = SeekBar(context).apply {
            max = 100
            progress = opacity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    opacity = p.coerceAtLeast(10); this@MphOverlayView.invalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val invert = CheckBox(context).apply { text = "Invert vertical aim"; isChecked = invertY }
        root.addView(sensLabel); root.addView(sensBar)
        root.addView(opLabel); root.addView(opBar)
        root.addView(invert)

        AlertDialog.Builder(context)
            .setTitle("Hunters controls")
            .setView(root)
            .setPositiveButton("Done") { _, _ ->
                invertY = invert.isChecked
                prefs.edit()
                    .putFloat("sens", sensitivity)
                    .putBoolean("invertY", invertY)
                    .putInt("opacity", opacity)
                    .apply()
                invalidate()
            }
            .show()
    }

    // ---------------------------------------------------------------- drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    private fun alpha(base: Int) = (base * opacity / 100f).roundToInt().coerceIn(0, 255)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return

        if (menuMode) {
            val b = buttons.getValue(Kind.BACK)
            fill.color = Color.argb(if (b.pressed) 230 else 170, 20, 120, 160)
            canvas.drawCircle(b.cx, b.cy, b.r, fill)
            text.textSize = min(dp(14f), b.r * 0.5f)
            text.alpha = 255
            canvas.drawText(b.kind.label, b.cx, b.cy + text.textSize * 0.35f, text)
            return
        }

        // Floating stick
        if (stickId != -1) {
            fill.color = Color.argb(alpha(90), 255, 255, 255)
            stroke.color = Color.argb(alpha(200), 255, 255, 255)
            canvas.drawCircle(stickOx, stickOy, dp(60f), stroke)
            canvas.drawCircle(stickX, stickY, dp(26f), fill)
        } else {
            stroke.color = Color.argb(alpha(70), 255, 255, 255)
            canvas.drawCircle(max(dp(75f), bigRect.left * 0.5f), height * 0.72f, dp(60f), stroke)
        }

        // Thin outline so the radar reads as tappable.
        stroke.color = Color.argb(alpha(120), 255, 255, 255)
        canvas.drawRect(smallRect, stroke)

        for (k in visibleKinds()) {
            val b = buttons.getValue(k)
            val (r, g, bl) = when (k) {
                Kind.FIRE -> Triple(230, 110, 40)
                Kind.JUMP -> Triple(60, 140, 230)
                Kind.MORPH, Kind.BOOST -> Triple(230, 190, 40)
                Kind.SCAN -> Triple(60, 200, 140)
                else -> Triple(40, 40, 50)
            }
            fill.color = Color.argb(alpha(if (b.pressed) 230 else 140), r, g, bl)
            stroke.color = Color.argb(alpha(220), 255, 255, 255)
            canvas.drawCircle(b.cx, b.cy, b.r, fill)
            canvas.drawCircle(b.cx, b.cy, b.r, stroke)
            text.textSize = if (k == Kind.SETTINGS) dp(20f) else min(dp(13f), b.r * 0.55f)
            text.alpha = alpha(255)
            canvas.drawText(k.label, b.cx, b.cy + text.textSize * 0.35f, text)
        }
    }
}
