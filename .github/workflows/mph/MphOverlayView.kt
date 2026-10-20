package me.magnum.melonds.ui.emulator.mph

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.widget.Toast
import java.io.File
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
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
        const val SPECIAL_X = 232; const val SPECIAL_Y = 34   // opens the affinity-weapon wheel
        const val MORPH_X = 231;   const val MORPH_Y = 167
        const val SCAN_X = 128;    const val SCAN_Y = 173

        // Direct-aim mode: the stylus rests here (required for aiming) and never lifts while you aim.
        const val HOLD_X = 128;    const val HOLD_Y = 96
        // Game aim units per dp of thumb movement at sensitivity 1.0; Y matches melonPrimeDS's ratio.
        const val AIM_Y_RATIO = 1.5f

        // Safe aiming box on the bottom screen (stays clear of the icons).
        const val AIM_CENTER_X = 128f; const val AIM_CENTER_Y = 104f
        const val AIM_RANGE_X = 76f;   const val AIM_RANGE_Y = 44f
        // Largest stylus step per frame (DS px). Big jumps get spread over frames so the game doesn't drop them.
        const val MAX_STEP = 12f

        // Timing (ms). If recentering ever causes an accidental jump, raise RELEASE_GAP_MS.
        const val RELEASE_GAP_MS = 40L
        const val TAP_HOLD_MS = 70L
        // Morph ball needs a short tap; turning the scan visor ON needs a ~half-second hold.
        const val MORPH_HOLD_MS = 50L
        const val SCAN_ON_HOLD_MS = 520L
        const val SCAN_OFF_HOLD_MS = 50L
        // After any scripted tap, wait before re-resting the stylus so it can't read as a double-tap (jump).
        const val AFTER_TAP_MS = 350L
        // Quick load needs a hold so a stray thumb can't wipe progress.
        const val LOAD_HOLD_MS = 500L

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
    private var sensitivity = prefs.getFloat("sens2", 1.0f)
    private var invertY = prefs.getBoolean("invertY", false)
    private var opacity = prefs.getInt("opacity", 55)
    private var vSens = prefs.getFloat("vsens2", 1.0f)
    private var accel = prefs.getFloat("accel2", 0.25f)
    private var smoothing = prefs.getFloat("smooth", 0.35f)
    // State from the native side (bit 1 = direct aim available, 2 = morph ball, 4 = scan visor).
    private var nativeState = 0
    private val directAim get() = (nativeState and 1) != 0
    private val inBall get() = (nativeState and 2) != 0
    private val inVisor get() = (nativeState and 4) != 0
    // Smoothed aim velocity (game units per tick)
    private var smX = 0f
    private var smY = 0f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private enum class Kind(val label: String) {
        FIRE("FIRE"), JUMP("JUMP"), BOOST("BOOST"), MORPH("BALL"), SCAN("SCAN"),
        BEAM("BEAM"), MISSILE("MSL"), SPECIAL("WPN"), START("START"), MENU("MENU"), SETTINGS("⚙"), BACK("BACK"),
        QSAVE("SAVE"), QLOAD("LOAD")
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
    private var aimLastT = 0L
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
    // Where the stylus re-presses after a recentre (far side of the box, so the next stroke has room).
    private var pendingPressX = AIM_CENTER_X
    private var pendingPressY = AIM_CENTER_Y
    // Quick-load hold timer (0 = not holding).
    private var loadHoldStart = 0L
    // Fractional aim carried between frames so slow, fine movements aren't lost.
    private var sendCarryX = 0f
    private var sendCarryY = 0f
    // Last time you moved/aimed/fired. The resting stylus is only held during play, so it never
    // presses things in the game's own menus.
    private var lastPlayInput = 0L
    private fun touchPlay() { lastPlayInput = SystemClock.uptimeMillis() }

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
        nativeState = MelonEmulator.mphGetState()

        if (loadHoldStart > 0L) {
            if (now - loadHoldStart >= LOAD_HOLD_MS) {
                loadHoldStart = 0L
                releaseAll()
                quickState(save = false)
                return
            }
            invalidate() // animate the hold ring
        }

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

        // 3a. Direct aim (USA Hunters): stylus rests at the centre, aim is written straight into the game.
        if (directAim) {
            val wantHold = !menuMode && !inBall && (aimWanted() || now - lastPlayInput < 3000L)
            if (wantHold && !touching && now - lastReleaseAt >= RELEASE_GAP_MS) stylusPress(HOLD_X.toFloat(), HOLD_Y.toFloat())
            if (!wantHold && touching) stylusRelease()

            // Light smoothing: blend this tick's movement with the last one (0 = raw, 0.8 = very smooth).
            smX += (accX - smX) * (1f - smoothing)
            smY += (accY - smY) * (1f - smoothing)
            val outX = if (aimWanted()) smX else accX
            val outY = if (aimWanted()) smY else accY
            accX = 0f; accY = 0f
            if (!aimWanted()) { smX = 0f; smY = 0f }
            sendCarryX += outX; sendCarryY += outY
            val ix = sendCarryX.toInt(); val iy = sendCarryY.toInt()
            sendCarryX -= ix; sendCarryY -= iy
            if ((ix != 0 || iy != 0) && touching) MelonEmulator.mphAddAim(ix, iy)
            return
        }

        // 3b. Fallback stylus aim (other regions/versions).
        if (aimWanted()) {
            if (!touching) {
                if (now - lastReleaseAt < RELEASE_GAP_MS) return
                // Fresh press; movement is applied from the next frame so the game takes this
                // point as its reference instead of snapping the camera.
                stylusPress(pendingPressX, pendingPressY)
                pendingPressX = AIM_CENTER_X; pendingPressY = AIM_CENTER_Y
                return
            }
            if (accX == 0f && accY == 0f) return
            val stepX = accX.coerceIn(-MAX_STEP, MAX_STEP)
            val stepY = accY.coerceIn(-MAX_STEP, MAX_STEP)
            val nx = sx + stepX
            val ny = sy + stepY
            val minX = AIM_CENTER_X - AIM_RANGE_X; val maxX = AIM_CENTER_X + AIM_RANGE_X
            val minY = AIM_CENTER_Y - AIM_RANGE_Y; val maxY = AIM_CENTER_Y + AIM_RANGE_Y
            if (nx < minX || nx > maxX || ny < minY || ny > maxY) {
                // Hit the edge of the safe box: move as far as we can, lift, and re-press on the
                // opposite side so the next stroke can travel the whole box.
                val cx = nx.coerceIn(minX, maxX)
                val cy = ny.coerceIn(minY, maxY)
                accX -= (cx - sx)
                accY -= (cy - sy)
                stylusMove(cx, cy)
                stylusRelease()
                pendingPressX = AIM_CENTER_X
                pendingPressY = AIM_CENTER_Y
            } else {
                accX -= stepX
                accY -= stepY
                stylusMove(nx, ny)
            }
        } else {
            accX = 0f; accY = 0f
            pendingPressX = AIM_CENTER_X; pendingPressY = AIM_CENTER_Y
            if (touching) stylusRelease()
        }
    }

    private fun queueTap(x: Int, y: Int, holdMs: Long = TAP_HOLD_MS) {
        if (touching || queue.isNotEmpty()) {
            queue.addLast(Step.Release)
            queue.addLast(Step.Wait(RELEASE_GAP_MS))
        }
        queue.addLast(Step.Press(x.toFloat(), y.toFloat()))
        queue.addLast(Step.Wait(holdMs))
        queue.addLast(Step.Release)
        queue.addLast(Step.Wait(if (directAim) AFTER_TAP_MS else RELEASE_GAP_MS))
        accX = 0f; accY = 0f
    }

    private fun addAimDelta(dxPx: Float, dyPx: Float, t: Long) {
        touchPlay()
        // Aim acceleration: quick flicks turn further than slow, precise drags.
        val dt = (t - aimLastT).coerceIn(1L, 100L).toFloat()
        aimLastT = t
        val speed = hypot(dxPx, dyPx) / density / dt // dp per ms
        val gain = 1f + accel * ((speed - 0.25f) * 2f).coerceIn(0f, 2f)
        val yRatio = if (directAim) AIM_Y_RATIO else 1f
        accX += dxPx / density * sensitivity * gain
        accY += (if (invertY) -dyPx else dyPx) / density * sensitivity * vSens * yRatio * gain
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
        loadHoldStart = 0L
        pendingPressX = AIM_CENTER_X; pendingPressY = AIM_CENTER_Y
        smX = 0f; smY = 0f; sendCarryX = 0f; sendCarryY = 0f
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
        var rh = H * 0.27f
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
        val rSys = min(dp(18f), m * 0.11f)

        // Left bar: system buttons under the radar.
        val sysY = smallRect.bottom + rSys + dp(8f)
        place(Kind.MENU, pad + rSys + dp(2f), sysY, rSys)
        place(Kind.START, pad + rSys * 3f + dp(10f), sysY, rSys)
        place(Kind.SETTINGS, pad + rSys * 5f + dp(18f), sysY, rSys)
        val sysY2 = sysY + rSys * 2f + dp(6f)
        place(Kind.QSAVE, pad + rSys + dp(2f), sysY2, rSys)
        place(Kind.QLOAD, pad + rSys * 3f + dp(10f), sysY2, rSys)

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
                pointerDown(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    pointerMove(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
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

    private fun pointerDown(id: Int, x: Float, y: Float, t: Long): Boolean {
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
                    touchPlay()
                    press(Input.L)
                    fireId = id; fireDownX = x; fireDownY = y; fireAiming = false
                    aimLastX = x; aimLastY = y; aimLastT = t
                }
                Kind.JUMP -> { touchPlay(); press(Input.B) }
                Kind.BOOST -> { touchPlay(); press(Input.R) }
                Kind.START -> press(Input.START)
                Kind.MORPH -> queueTap(MORPH_X, MORPH_Y, MORPH_HOLD_MS)
                Kind.SCAN -> queueTap(SCAN_X, SCAN_Y, if (inVisor) SCAN_OFF_HOLD_MS else SCAN_ON_HOLD_MS)
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
                Kind.QSAVE -> {}
                Kind.QLOAD -> loadHoldStart = SystemClock.uptimeMillis()
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
            touchPlay()
            stickId = id
            stickOx = x; stickOy = y; stickX = x; stickY = y
            return true
        }

        if (aimId == -1 && !fireAiming) {
            roles[id] = Role.Aim
            touchPlay()
            aimId = id
            aimLastX = x; aimLastY = y; aimLastT = t
            return true
        }

        roles[id] = Role.Ignored
        return true
    }

    private fun pointerMove(id: Int, x: Float, y: Float, t: Long) {
        when (val role = roles[id]) {
            is Role.Stick -> { touchPlay(); updateStick(x, y) }
            is Role.Direct -> {
                val (dx, dy) = mapToDs(role.rect, x, y)
                directX = dx; directY = dy
            }
            is Role.Aim -> {
                addAimDelta(x - aimLastX, y - aimLastY, t)
                aimLastX = x; aimLastY = y
            }
            is Role.Button -> when (role.kind) {
                Kind.FIRE -> {
                    if (!fireAiming && aimId == -1 && hypot(x - fireDownX, y - fireDownY) > dp(10f)) {
                        fireAiming = true
                        aimLastX = x; aimLastY = y; aimLastT = t
                    } else if (fireAiming) {
                        addAimDelta(x - aimLastX, y - aimLastY, t)
                        aimLastX = x; aimLastY = y
                    }
                }
                Kind.SPECIAL -> {
                    accX += (x - specialLastX) / density * 1.2f
                    accY += (y - specialLastY) / density * 1.2f
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
                queue.addLast(Step.Wait(if (directAim) AFTER_TAP_MS else RELEASE_GAP_MS))
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
                        queue.addLast(Step.Wait(if (directAim) AFTER_TAP_MS else RELEASE_GAP_MS))
                        // The queued release runs before any aiming resumes.
                        specialMode = false
                    }
                    Kind.MENU -> toggleMenuMode()
                    Kind.BACK -> toggleMenuMode()
                    Kind.SETTINGS -> showSettings()
                    Kind.QSAVE -> { releaseAll(); quickState(save = true) }
                    Kind.QLOAD -> loadHoldStart = 0L
                    else -> {}
                }
            }
            else -> {}
        }
    }

    private fun updateStick(x: Float, y: Float) {
        val maxR = dp(55f)
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

    // Own quick-save slot in app storage, so it works no matter where melonDS keeps its save states.
    private fun quickState(save: Boolean) {
        val file = File(context.filesDir, "hunters_quick.mln")
        if (!save && !file.exists()) {
            Toast.makeText(context, "No quick save yet", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            MelonEmulator.pauseEmulation()
            val ok = try {
                val uri = Uri.parse(file.absolutePath)
                if (save) MelonEmulator.saveState(uri) else MelonEmulator.loadState(uri)
            } catch (e: Exception) {
                false
            }
            MelonEmulator.resumeEmulation()
            post {
                val msg = when {
                    save && ok -> "Quick saved"
                    save -> "Quick save failed"
                    ok -> "Quick loaded"
                    else -> "Quick load failed"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

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
            max = 38 // 0.2 .. 4.0
            progress = ((sensitivity - 0.2f) * 10f).roundToInt().coerceIn(0, 38)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    sensitivity = 0.2f + p / 10f
                    sensLabel.text = sensText()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val vLabel = TextView(context)
        fun vText() = "Vertical aim: %.1fx".format(vSens)
        vLabel.text = vText()
        val vBar = SeekBar(context).apply {
            max = 25 // 0.5 .. 3.0
            progress = ((vSens - 0.5f) * 10f).roundToInt().coerceIn(0, 25)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    vSens = 0.5f + p / 10f
                    vLabel.text = vText()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val aLabel = TextView(context)
        fun aText() = "Flick acceleration: %d%%".format((accel * 100f).roundToInt())
        aLabel.text = aText()
        val aBar = SeekBar(context).apply {
            max = 100
            progress = (accel * 100f).roundToInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    accel = p / 100f
                    aLabel.text = aText()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val smLabel = TextView(context)
        fun smText() = "Aim smoothing: %d%%".format((smoothing * 100f).roundToInt())
        smLabel.text = smText()
        val smBar = SeekBar(context).apply {
            max = 80
            progress = (smoothing * 100f).roundToInt().coerceIn(0, 80)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    smoothing = p / 100f
                    smLabel.text = smText()
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
        val modeLabel = TextView(context).apply {
            text = if (directAim) "Aim mode: direct (USA version detected)" else "Aim mode: stylus fallback (direct aim needs the USA version)"
        }
        root.addView(modeLabel)
        root.addView(sensLabel); root.addView(sensBar)
        root.addView(vLabel); root.addView(vBar)
        root.addView(aLabel); root.addView(aBar)
        root.addView(smLabel); root.addView(smBar)
        root.addView(opLabel); root.addView(opBar)
        root.addView(invert)

        AlertDialog.Builder(context)
            .setTitle("Hunters controls")
            .setView(root)
            .setPositiveButton("Done") { _, _ ->
                invertY = invert.isChecked
                prefs.edit()
                    .putFloat("sens2", sensitivity)
                    .putBoolean("invertY", invertY)
                    .putInt("opacity", opacity)
                    .putFloat("vsens2", vSens)
                    .putFloat("accel2", accel)
                    .putFloat("smooth", smoothing)
                    .apply()
                invalidate()
            }
            .show()
    }

    // ---------------------------------------------------------------- drawing (visor style)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.08f
    }
    private val path = Path()
    private val arcRect = RectF()

    private fun alpha(base: Int) = (base * opacity / 100f).roundToInt().coerceIn(0, 255)

    // HUD colours (r, g, b)
    private val hudGreen = intArrayOf(120, 255, 150)
    private val hudAmber = intArrayOf(255, 150, 60)
    private val hudCyan = intArrayOf(90, 215, 255)
    private val hudYellow = intArrayOf(255, 215, 80)

    private fun col(c: IntArray, a: Int) = Color.argb(a.coerceIn(0, 255), c[0], c[1], c[2])

    private fun octagon(cx: Float, cy: Float, r: Float): Path {
        val c = r * 0.42f
        path.reset()
        path.moveTo(cx - r + c, cy - r)
        path.lineTo(cx + r - c, cy - r)
        path.lineTo(cx + r, cy - r + c)
        path.lineTo(cx + r, cy + r - c)
        path.lineTo(cx + r - c, cy + r)
        path.lineTo(cx - r + c, cy + r)
        path.lineTo(cx - r, cy + r - c)
        path.lineTo(cx - r, cy - r + c)
        path.close()
        return path
    }

    private fun colourFor(k: Kind) = when (k) {
        Kind.FIRE -> hudAmber
        Kind.JUMP -> hudCyan
        Kind.MORPH, Kind.BOOST -> hudYellow
        else -> hudGreen
    }

    private fun drawLabel(canvas: Canvas, label: String, cx: Float, cy: Float, size: Float, c: IntArray, a: Int) {
        text.textSize = size
        text.color = col(c, a)
        canvas.drawText(label, cx, cy + size * 0.36f, text)
    }

    private fun drawOctButton(canvas: Canvas, b: Btn) {
        val c = colourFor(b.kind)
        val p = octagon(b.cx, b.cy, b.r)
        fill.color = Color.argb(alpha(if (b.pressed) 190 else 130), 4, 16, 12)
        canvas.drawPath(p, fill)
        if (b.pressed) {
            fill.color = col(c, alpha(80))
            canvas.drawPath(p, fill)
        }
        // soft outer glow, crisp edge, faint inner frame
        stroke.strokeWidth = dp(6f); stroke.color = col(c, alpha(if (b.pressed) 110 else 35))
        canvas.drawPath(p, stroke)
        stroke.strokeWidth = dp(1.8f); stroke.color = col(c, alpha(235))
        canvas.drawPath(p, stroke)
        stroke.strokeWidth = dp(1f); stroke.color = col(c, alpha(80))
        canvas.drawPath(octagon(b.cx, b.cy, b.r * 0.80f), stroke)

        val size = if (b.kind == Kind.SETTINGS) b.r * 0.9f else min(dp(12f), b.r * 0.42f)
        drawLabel(canvas, b.kind.label, b.cx, b.cy, size, c, alpha(if (b.pressed) 255 else 220))

        if (b.kind == Kind.QLOAD && loadHoldStart > 0L) {
            val f = ((SystemClock.uptimeMillis() - loadHoldStart).toFloat() / LOAD_HOLD_MS).coerceIn(0f, 1f)
            val rr = b.r + dp(5f)
            arcRect.set(b.cx - rr, b.cy - rr, b.cx + rr, b.cy + rr)
            stroke.strokeWidth = dp(3f); stroke.color = col(c, 255)
            canvas.drawArc(arcRect, -90f, 360f * f, false, stroke)
        }
    }

    private fun drawFire(canvas: Canvas, b: Btn) {
        val c = colourFor(b.kind)
        fill.color = Color.argb(alpha(if (b.pressed) 190 else 120), 16, 8, 4)
        canvas.drawCircle(b.cx, b.cy, b.r, fill)
        if (b.pressed) {
            fill.color = col(c, alpha(70))
            canvas.drawCircle(b.cx, b.cy, b.r, fill)
        }
        stroke.strokeWidth = dp(7f); stroke.color = col(c, alpha(if (b.pressed) 110 else 35))
        canvas.drawCircle(b.cx, b.cy, b.r, stroke)
        stroke.strokeWidth = dp(2f); stroke.color = col(c, alpha(235))
        canvas.drawCircle(b.cx, b.cy, b.r, stroke)
        // reticle: inner ring + four ticks
        stroke.strokeWidth = dp(1.4f); stroke.color = col(c, alpha(170))
        canvas.drawCircle(b.cx, b.cy, b.r * 0.42f, stroke)
        stroke.strokeWidth = dp(2.2f); stroke.color = col(c, alpha(220))
        val a = b.r * 0.58f; val z = b.r * 0.86f
        canvas.drawLine(b.cx, b.cy - z, b.cx, b.cy - a, stroke)
        canvas.drawLine(b.cx, b.cy + a, b.cx, b.cy + z, stroke)
        canvas.drawLine(b.cx - z, b.cy, b.cx - a, b.cy, stroke)
        canvas.drawLine(b.cx + a, b.cy, b.cx + z, b.cy, stroke)
        drawLabel(canvas, "FIRE", b.cx, b.cy, b.r * 0.22f, c, alpha(if (b.pressed) 255 else 220))
    }

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, knobX: Float, knobY: Float, live: Boolean) {
        val r = dp(55f)
        stroke.strokeWidth = dp(1.5f); stroke.color = col(hudGreen, alpha(if (live) 200 else 70))
        canvas.drawCircle(cx, cy, r, stroke)
        // eight ticks around the ring
        stroke.strokeWidth = dp(2f)
        for (i in 0 until 8) {
            val ang = Math.toRadians(i * 45.0)
            val ca = Math.cos(ang).toFloat(); val sa = Math.sin(ang).toFloat()
            canvas.drawLine(cx + ca * r * 0.86f, cy + sa * r * 0.86f, cx + ca * r, cy + sa * r, stroke)
        }
        if (live) {
            val p = octagon(knobX, knobY, dp(24f))
            fill.color = Color.argb(alpha(150), 4, 16, 12)
            canvas.drawPath(p, fill)
            stroke.strokeWidth = dp(6f); stroke.color = col(hudGreen, alpha(60))
            canvas.drawPath(p, stroke)
            stroke.strokeWidth = dp(1.8f); stroke.color = col(hudGreen, alpha(235))
            canvas.drawPath(p, stroke)
        }
    }

    private fun drawBrackets(canvas: Canvas, r: RectF) {
        val l = dp(14f)
        stroke.strokeWidth = dp(2f); stroke.color = col(hudGreen, alpha(200))
        canvas.drawLine(r.left, r.top, r.left + l, r.top, stroke); canvas.drawLine(r.left, r.top, r.left, r.top + l, stroke)
        canvas.drawLine(r.right, r.top, r.right - l, r.top, stroke); canvas.drawLine(r.right, r.top, r.right, r.top + l, stroke)
        canvas.drawLine(r.left, r.bottom, r.left + l, r.bottom, stroke); canvas.drawLine(r.left, r.bottom, r.left, r.bottom - l, stroke)
        canvas.drawLine(r.right, r.bottom, r.right - l, r.bottom, stroke); canvas.drawLine(r.right, r.bottom, r.right, r.bottom - l, stroke)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return

        if (menuMode) {
            drawOctButton(canvas, buttons.getValue(Kind.BACK))
            return
        }

        drawBrackets(canvas, smallRect)

        if (stickId != -1) {
            drawStick(canvas, stickOx, stickOy, stickX, stickY, true)
        } else {
            drawStick(canvas, max(dp(75f), bigRect.left * 0.5f), height * 0.78f, 0f, 0f, false)
        }

        for (k in visibleKinds()) {
            val b = buttons.getValue(k)
            if (k == Kind.FIRE) drawFire(canvas, b) else drawOctButton(canvas, b)
        }
    }
}
