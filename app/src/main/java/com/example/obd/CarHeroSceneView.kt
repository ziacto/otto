package com.example.obd

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * SceneView-backed hero for the Welcome screen. Loads the bundled BMW M4
 * F82 GLB and spins it around the Y axis using Filament's PBR renderer.
 *
 * Kotlin is required by SceneView 2.x — this is the only Kotlin file in
 * the project, kept deliberately tiny so the Java-Kotlin boundary is one
 * View class. Callers add it to a layout like any other View:
 *
 *   <com.example.obd.CarHeroSceneView
 *       android:layout_width="match_parent"
 *       android:layout_height="200dp" />
 *
 * On devices that lack OpenGL ES 3.0 (extremely rare in 2026) or where
 * Filament fails to init, this View no-ops silently — no crash. The
 * calling layout should have a fallback vector behind it in case the
 * scene never binds.
 */
class CarHeroSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SceneView(context, attrs, defStyle) {

    private var modelNode: ModelNode? = null
    private var lastFrameNs = 0L
    private var startNs = 0L
    private var baseY = 0f
    private val rotationDegPerSec = 35f
    private val choreographer = Choreographer.getInstance()

    // Two Filament point lights positioned in front of the car (world +Z),
    // pulsed on each frame to simulate parking lights blinking. Native
    // entities — we destroy them on detach to avoid leaking Filament handles.
    private var leftHeadEntity = 0
    private var rightHeadEntity = 0
    private var leftTailEntity = 0
    private var rightTailEntity = 0
    private val baseHeadIntensity = 60000f
    private val baseTailIntensity = 30000f

    // ---- Animation curves (all wall-clock parametric so no easing state) ----
    // Continuous yaw rotation is the primary motion. Layered on top:
    //   * floatY  — vertical bob every 3.2 s
    //   * nodX    — subtle pitch oscillation every 5.8 s
    //   * roll    — very slight bank every 7.4 s
    //   * scale   — 1.00 ↔ 1.03 breathing every 4.2 s
    // Every animation writes into the same ModelNode so they compose cheaply
    // without spawning multiple ObjectAnimators.

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val node = modelNode
            if (node != null && lastFrameNs != 0L) {
                val t = (frameTimeNanos - startNs) / 1_000_000_000f

                // ---- Stylish camera orbit ----
                val orbitPeriodS = 9.0
                val angle = (t / orbitPeriodS * 2.0 * PI).toFloat()
                val radius = 4.2f
                val camX = radius * sin(angle)
                val camZ = radius * cos(angle)
                val camY = 1.4f + 0.25f * sin((t / 3.6 * 2.0 * PI).toFloat())
                cameraNode.position = Position(camX, camY, camZ)
                cameraNode.lookAt(node)

                // ---- Model motion ----
                val s = 1.0f + 0.010f * sin((t / 4.2 * 2.0 * PI).toFloat())
                node.scale = Scale(s, s, s)
                val bob = 0.02f * sin((t / 3.2 * 2.0 * PI).toFloat())
                val pos = node.position
                node.position = Position(pos.x, baseY + bob, pos.z)

                // ---- Headlight parking-pulse ----
                // Two-phase pulse: fast blink at 0.85 Hz for the "parked with
                // hazards on" feel. Duty cycle sine → clamped so lights spend
                // more time bright than dim. Tail lights pulse counter-phase
                // so front + rear alternate — reads as "car is running".
                pulseLights(t)
            }
            lastFrameNs = frameTimeNanos
            if (startNs == 0L) startNs = frameTimeNanos
            if (isAttachedToWindow) choreographer.postFrameCallback(this)
        }
    }

    private fun pulseLights(t: Float) {
        val lm = try { engine.lightManager } catch (_: Throwable) { return }
        val headPhase = 0.5f + 0.5f * sin(t * (2 * PI) / 1.2).toFloat()
        val tailPhase = 0.5f + 0.5f * sin(t * (2 * PI) / 1.2 + PI.toFloat()).toFloat()
        // Bias so lights never fully turn off — parking lights hover.
        val headIntensity = baseHeadIntensity * (0.55f + 0.65f * headPhase)
        val tailIntensity = baseTailIntensity * (0.45f + 0.75f * tailPhase)
        if (leftHeadEntity != 0)  lm.setIntensity(lm.getInstance(leftHeadEntity),  headIntensity)
        if (rightHeadEntity != 0) lm.setIntensity(lm.getInstance(rightHeadEntity), headIntensity)
        if (leftTailEntity != 0)  lm.setIntensity(lm.getInstance(leftTailEntity),  tailIntensity)
        if (rightTailEntity != 0) lm.setIntensity(lm.getInstance(rightTailEntity), tailIntensity)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // SceneView 2.x wires up its own lifecycle observer from the parent
        // ViewTreeLifecycleOwner — no manual setLifecycle needed. Defer the
        // heavy GLB load one frame so the view is fully bound + measured
        // before we hit Filament.
        post { tryLoadModel() }
    }

    override fun onDetachedFromWindow() {
        // Memory leak protection: cancel the frame callback, tear down the
        // light entities (Filament EntityManager leaks otherwise), destroy
        // the ModelNode, drop refs. SceneView takes care of the engine.
        choreographer.removeFrameCallback(frameCallback)
        try {
            val em = EntityManager.get()
            for (e in intArrayOf(leftHeadEntity, rightHeadEntity, leftTailEntity, rightTailEntity)) {
                if (e != 0) {
                    try { scene.removeEntity(e) } catch (_: Throwable) {}
                    try { engine.lightManager.destroy(e) } catch (_: Throwable) {}
                    em.destroy(e)
                }
            }
        } catch (_: Throwable) {}
        leftHeadEntity = 0
        rightHeadEntity = 0
        leftTailEntity = 0
        rightTailEntity = 0
        modelNode?.let {
            try { it.destroy() } catch (_: Throwable) {}
        }
        modelNode = null
        super.onDetachedFromWindow()
    }

    private fun tryLoadModel() {
        try {
            val modelInstance = modelLoader.createModelInstance("models/car.glb")
            val node = ModelNode(
                modelInstance = modelInstance,
                autoAnimate = true,
                scaleToUnits = 1.0f,
                centerOrigin = Position(0f, 0f, 0f)
            )
            addChildNode(node)
            modelNode = node
            baseY = node.position.y

            // Add two Filament point lights at approximate headlight positions
            // (front of the car, +Z) and two at the tail (-Z). Positions are
            // in world space; because the car is stationary and the camera
            // orbits, the "headlights" stay locked to the front of the body
            // from any viewing angle.
            try {
                leftHeadEntity  = addPointLight(-0.20f, 0.15f,  0.55f, 1.0f, 0.95f, 0.80f, baseHeadIntensity)
                rightHeadEntity = addPointLight( 0.20f, 0.15f,  0.55f, 1.0f, 0.95f, 0.80f, baseHeadIntensity)
                leftTailEntity  = addPointLight(-0.20f, 0.15f, -0.55f, 1.0f, 0.20f, 0.15f, baseTailIntensity)
                rightTailEntity = addPointLight( 0.20f, 0.15f, -0.55f, 1.0f, 0.20f, 0.15f, baseTailIntensity)
            } catch (e: Throwable) {
                Log.w(TAG, "Headlight setup failed (non-fatal): ${e.message}")
            }

            lastFrameNs = 0L
            startNs = 0L
            choreographer.postFrameCallback(frameCallback)
        } catch (e: Throwable) {
            Log.e(TAG, "GLB load failed: ${e.message}", e)
        }
    }

    /** Create a Filament POINT light at (x,y,z) with the given RGB colour + intensity.
     *  Returns the entity ID so we can pulse or destroy it later. */
    private fun addPointLight(
        x: Float, y: Float, z: Float,
        r: Float, g: Float, b: Float,
        intensity: Float
    ): Int {
        val entity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.POINT)
            .position(x, y, z)
            .color(r, g, b)
            .intensity(intensity)
            .falloff(2.5f)
            .castShadows(false)
            .build(engine, entity)
        scene.addEntity(entity)
        return entity
    }

    companion object {
        private const val TAG = "CarHeroSceneView"
    }
}
