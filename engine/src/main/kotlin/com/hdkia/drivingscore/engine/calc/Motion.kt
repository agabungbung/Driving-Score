package com.hdkia.drivingscore.engine.calc

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 처음 속도 [v0](m/s), 가속도 [a](m/s²)로 [d](m)를 가는 데 걸리는 시간(s).
 * `d = v0·s + a·s²/2`의 해. a가 0에 가까워도 안정적인 형태(2d / (v0 + √(v0² + 2ad)))로 푼다.
 * 감속해 멈추느라 닿지 못하면 멈춘 시각을 준다.
 */
fun solveTravelTime(v0: Double, a: Double, d: Double): Double {
    if (d <= 0.0) return 0.0
    val disc = v0 * v0 + 2.0 * a * d
    if (disc < 0.0) return if (abs(a) > 0.0) -v0 / a else 0.0
    val denom = v0 + sqrt(disc)
    return if (denom <= 0.0) 0.0 else 2.0 * d / denom
}
