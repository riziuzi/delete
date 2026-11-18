package com.example.pushupalarm

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark.LEFT_ANKLE
import com.google.mlkit.vision.pose.PoseLandmark.LEFT_ELBOW
import com.google.mlkit.vision.pose.PoseLandmark.LEFT_HIP
import com.google.mlkit.vision.pose.PoseLandmark.LEFT_KNEE
import com.google.mlkit.vision.pose.PoseLandmark.LEFT_SHOULDER
import com.google.mlkit.vision.pose.PoseLandmark.LEFT_WRIST
import com.google.mlkit.vision.pose.PoseLandmark.NOSE
import com.google.mlkit.vision.pose.PoseLandmark.RIGHT_ANKLE
import com.google.mlkit.vision.pose.PoseLandmark.RIGHT_ELBOW
import com.google.mlkit.vision.pose.PoseLandmark.RIGHT_HIP
import com.google.mlkit.vision.pose.PoseLandmark.RIGHT_KNEE
import com.google.mlkit.vision.pose.PoseLandmark.RIGHT_SHOULDER
import com.google.mlkit.vision.pose.PoseLandmark.RIGHT_WRIST

/** Converts ML Kit poses into what the counter and the skeleton overlay need. */
object PoseUtils {

    /** Arm bones, drawn in a highlight colour because they drive the count. */
    val ARM_BONES = listOf(
        LEFT_SHOULDER to LEFT_ELBOW, LEFT_ELBOW to LEFT_WRIST,
        RIGHT_SHOULDER to RIGHT_ELBOW, RIGHT_ELBOW to RIGHT_WRIST,
    )

    val BODY_BONES = listOf(
        LEFT_SHOULDER to RIGHT_SHOULDER,
        LEFT_SHOULDER to LEFT_HIP, RIGHT_SHOULDER to RIGHT_HIP, LEFT_HIP to RIGHT_HIP,
        LEFT_HIP to LEFT_KNEE, LEFT_KNEE to LEFT_ANKLE,
        RIGHT_HIP to RIGHT_KNEE, RIGHT_KNEE to RIGHT_ANKLE,
    )

    private val DRAWN = setOf(
        NOSE, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST,
        LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE,
    )

    /** Visible landmarks, keyed by landmark type. */
    fun landmarks(pose: Pose, minLikelihood: Float = 0.5f): Map<Int, Pt> =
        pose.allPoseLandmarks
            .filter { it.landmarkType in DRAWN && it.inFrameLikelihood >= minLikelihood }
            .associate { it.landmarkType to Pt(it.position.x, it.position.y) }

    fun leftArm(pose: Pose) = arm(pose, LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST, LEFT_HIP)
    fun rightArm(pose: Pose) = arm(pose, RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST, RIGHT_HIP)

    private fun arm(pose: Pose, shoulder: Int, elbow: Int, wrist: Int, hip: Int): Arm? {
        val ls = listOf(shoulder, elbow, wrist).map { pose.getPoseLandmark(it) ?: return null }
        val pts = ls.map { Pt(it.position.x, it.position.y) }
        val h = pose.getPoseLandmark(hip)?.takeIf { it.inFrameLikelihood >= 0.5f }
        return Arm(pts[0], pts[1], pts[2], h?.let { Pt(it.position.x, it.position.y) }, ls.minOf { it.inFrameLikelihood })
    }
}
