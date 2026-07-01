package com.example.obd

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import androidx.lifecycle.findViewTreeLifecycleOwner
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
                // Camera swings around the car on a horizontal arc, dipping
                // up and down slightly so it feels like a hand-held rig.
                val orbitPeriodS = 9.0
                val angle = (t / orbitPeriodS * 2.0 * PI).toFloat()
                val radius = 4.2f
                val camX = radius * sin(angle)
                val camZ = radius * cos(angle)
                val camY = 1.4f + 0.25f * sin((t / 3.6 * 2.0 * PI).toFloat())
                cameraNode.position = Position(camX, camY, camZ)
                cameraNode.lookAt(node)

                // ---- Model motion ----
                // Very slight scale breathing so the shell feels alive even
                // when the camera is briefly stationary at the arc endpoints.
                val s = 1.0f + 0.010f * sin((t / 4.2 * 2.0 * PI).toFloat())
                node.scale = Scale(s, s, s)
                // Y-bob (tiny — heavy visual movement is already coming from
                // the camera orbit).
                val bob = 0.02f * sin((t / 3.2 * 2.0 * PI).toFloat())
                val pos = node.position
                node.position = Position(pos.x, baseY + bob, pos.z)
            }
            lastFrameNs = frameTimeNanos
            if (startNs == 0L) startNs = frameTimeNanos
            if (isAttachedToWindow) choreographer.postFrameCallback(this)
        }
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
        // Memory leak protection: cancel the frame callback (would keep this
        // View alive indefinitely via Choreographer's callback queue), drop
        // the ModelNode ref (holds a big native handle), and let SceneView's
        // own destroy() release the Filament engine.
        choreographer.removeFrameCallback(frameCallback)
        modelNode?.let {
            try { it.destroy() } catch (_: Throwable) {}
        }
        modelNode = null
        super.onDetachedFromWindow()
    }

    private fun tryLoadModel() {
        try {
            val modelInstance = modelLoader.createModelInstance("models/car.glb")
            // scaleToUnits=1.0 tells SceneView to normalise the model's
            // bounding box to a 1-unit sphere. Combined with a camera
            // radius of ~4.2 units this leaves the whole car comfortably
            // inside the viewport at every camera angle.
            val node = ModelNode(
                modelInstance = modelInstance,
                autoAnimate = true,
                scaleToUnits = 1.0f,
                centerOrigin = Position(0f, 0f, 0f)
            )
            addChildNode(node)
            modelNode = node
            baseY = node.position.y
            lastFrameNs = 0L
            startNs = 0L
            choreographer.postFrameCallback(frameCallback)
        } catch (e: Throwable) {
            Log.e(TAG, "GLB load failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "CarHeroSceneView"
    }
}
