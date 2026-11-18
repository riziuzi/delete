package com.example.pushupalarm

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
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/**
 * Runs the on-device pose model on each camera frame.
 *
 * Delivers (on the main thread) the raw pose, used for counting, and the landmarks mapped onto
 * [previewView]'s pixels (rotation, scaling and front-camera mirroring applied), used to draw
 * the skeleton exactly over the body.
 */
class PoseFrameAnalyzer(
    private val previewView: PreviewView,
    private val onFrame: (pose: Pose?, viewPoints: Map<Int, Pt>) -> Unit,
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
                main.post {
                    val target = previewView.outputTransform
                    val points = if (target == null) {
                        emptyMap()
                    } else {
                        mapToView(PoseUtils.landmarks(pose), CoordinateTransform(source, target))
                    }
                    onFrame(pose, points)
                }
            }
            .addOnFailureListener { main.post { onFrame(null, emptyMap()) } }
            .addOnCompleteListener { proxy.close() }
    }

    private fun mapToView(points: Map<Int, Pt>, t: CoordinateTransform): Map<Int, Pt> {
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
}
