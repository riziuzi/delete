package com.riziuzi.utho.camera

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.riziuzi.utho.core.Body
import com.riziuzi.utho.core.Joint
import com.riziuzi.utho.core.Pt

/**
 * Runs the on-device pose model on each camera frame. Delivers on the main thread:
 * the body in upright image coordinates (for counting) and the same joints mapped onto
 * [previewView] pixels (rotation, scaling and front-camera mirroring applied) to draw the skeleton.
 */
class PoseFrameAnalyzer(
    private val previewView: PreviewView,
    private val onFrame: (body: Body?, viewPoints: Map<Joint, Pt>, timeMs: Long) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build(),
    )
    private val transformFactory = ImageProxyTransformFactory().apply { setUsingRotationDegrees(true) }
    private val main = Handler(Looper.getMainLooper())

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val source = transformFactory.getOutputTransform(proxy)
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { pose ->
                val body = toBody(pose)
                main.post {
                    val target = previewView.outputTransform
                    val points = if (target == null) emptyMap() else mapToView(body.points, CoordinateTransform(source, target))
                    onFrame(body, points, System.currentTimeMillis())
                }
            }
            .addOnFailureListener { main.post { onFrame(null, emptyMap(), System.currentTimeMillis()) } }
            .addOnCompleteListener { proxy.close() }
    }

    private fun mapToView(points: Map<Joint, Pt>, t: CoordinateTransform): Map<Joint, Pt> {
        val keys = points.keys.toList()
        val arr = FloatArray(keys.size * 2)
        keys.forEachIndexed { i, k ->
            arr[2 * i] = points.getValue(k).x
            arr[2 * i + 1] = points.getValue(k).y
        }
        t.mapPoints(arr)
        return keys.withIndex().associate { (i, k) -> k to Pt(arr[2 * i], arr[2 * i + 1]) }
    }

    fun close() = detector.close()

    companion object {
        private val JOINTS = mapOf(
            PoseLandmark.NOSE to Joint.NOSE,
            PoseLandmark.LEFT_SHOULDER to Joint.L_SHOULDER, PoseLandmark.RIGHT_SHOULDER to Joint.R_SHOULDER,
            PoseLandmark.LEFT_ELBOW to Joint.L_ELBOW, PoseLandmark.RIGHT_ELBOW to Joint.R_ELBOW,
            PoseLandmark.LEFT_WRIST to Joint.L_WRIST, PoseLandmark.RIGHT_WRIST to Joint.R_WRIST,
            PoseLandmark.LEFT_HIP to Joint.L_HIP, PoseLandmark.RIGHT_HIP to Joint.R_HIP,
            PoseLandmark.LEFT_KNEE to Joint.L_KNEE, PoseLandmark.RIGHT_KNEE to Joint.R_KNEE,
            PoseLandmark.LEFT_ANKLE to Joint.L_ANKLE, PoseLandmark.RIGHT_ANKLE to Joint.R_ANKLE,
        )

        fun toBody(pose: Pose, minLikelihood: Float = 0.5f): Body = Body(
            pose.allPoseLandmarks
                .filter { it.inFrameLikelihood >= minLikelihood }
                .mapNotNull { l -> JOINTS[l.landmarkType]?.let { it to Pt(l.position.x, l.position.y) } }
                .toMap(),
        )

        val ARM_BONES = listOf(
            Joint.L_SHOULDER to Joint.L_ELBOW, Joint.L_ELBOW to Joint.L_WRIST,
            Joint.R_SHOULDER to Joint.R_ELBOW, Joint.R_ELBOW to Joint.R_WRIST,
        )
        val BODY_BONES = listOf(
            Joint.L_SHOULDER to Joint.R_SHOULDER,
            Joint.L_SHOULDER to Joint.L_HIP, Joint.R_SHOULDER to Joint.R_HIP, Joint.L_HIP to Joint.R_HIP,
            Joint.L_HIP to Joint.L_KNEE, Joint.L_KNEE to Joint.L_ANKLE,
            Joint.R_HIP to Joint.R_KNEE, Joint.R_KNEE to Joint.R_ANKLE,
        )
        val LEG_BONES = listOf(
            Joint.L_HIP to Joint.L_KNEE, Joint.L_KNEE to Joint.L_ANKLE,
            Joint.R_HIP to Joint.R_KNEE, Joint.R_KNEE to Joint.R_ANKLE,
        )
    }
}
