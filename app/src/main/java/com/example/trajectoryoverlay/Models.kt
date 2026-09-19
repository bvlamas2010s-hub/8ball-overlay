package com.example.trajectoryoverlay

import android.graphics.PointF
import android.graphics.RectF

data class Ball(
    val center: PointF,
    val radius: Float,
    val cue: Boolean = false,
    val confidence: Float = 1f
)

enum class PathKind { DIRECT, OBJECT_BANK, CUE_BANK }

data class Trajectory(
    val kind: PathKind,
    val cuePath: List<PointF>,
    val objectPath: List<PointF>,
    val ghost: PointF,
    val cutAngleDeg: Float,
    val score: Float,
    val secondaryCuePath: List<PointF> = emptyList()
)

data class AnalysisResult(
    val table: RectF,
    val balls: List<Ball>,
    val pockets: List<PointF>,
    val trajectories: List<Trajectory>,
    val fps: Float = 0f,
    val message: String = ""
)
