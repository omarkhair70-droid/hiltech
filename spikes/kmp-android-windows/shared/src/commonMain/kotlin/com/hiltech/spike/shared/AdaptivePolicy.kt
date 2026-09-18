package com.hiltech.spike.shared

enum class AdaptiveMode {
    STACKED,
    SPLIT,
    WIDE,
}

fun adaptiveMode(widthDp: Int): AdaptiveMode =
    when {
        widthDp < 600 -> AdaptiveMode.STACKED
        widthDp < 1100 -> AdaptiveMode.SPLIT
        else -> AdaptiveMode.WIDE
    }

fun ltrIsolate(value: String): String =
    "\u2066" + value + "\u2069"

fun mixedArabicProjectLabel(): String =
    "مشروع القاهرة • " +
        ltrIsolate("WO-42") +
        " • " +
        ltrIsolate("10.20.30.4") +
        " • " +
        ltrIsolate("Fluke-03")
