package com.example.pushupalarm

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/** Runs the on-device ML Kit pose model on each camera frame and feeds the counter. */
class PoseAnalyzer(
    private val counter: PushUpCounter,
    private val onResult: (PushUpCounter.Result) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build(),
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { pose ->
                val left = arm(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST, PoseLandmark.LEFT_HIP)
                val right = arm(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, PoseLandmark.RIGHT_HIP)
                onResult(counter.update(left, right))
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun arm(pose: Pose, shoulder: Int, elbow: Int, wrist: Int, hip: Int): Arm? {
        val ls = listOf(shoulder, elbow, wrist, hip).map { pose.getPoseLandmark(it) }
        if (ls.any { it == null }) return null
        val pts = ls.map { Pt(it!!.position.x, it.position.y) }
        return Arm(pts[0], pts[1], pts[2], pts[3], ls.minOf { it!!.inFrameLikelihood })
    }

    fun close() = detector.close()
}
