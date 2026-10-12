package com.vineyard.aivideostudio.media.tools

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Data representation of a 2D bounding box on video coordinates.
 */
data class ToolsBoundingBox(
    val x0: Float,
    val y0: Float,
    val width: Float,
    val height: Float
) {
    val centerX: Float get() = x0 + (width / 2f)
    val centerY: Float get() = y0 + (height / 2f)
}

/**
 * Detected screen instance matching a keyword or OCR rule.
 */
data class DetectedTargetBox(
    val x0: Float,
    val y0: Float,
    val width: Float,
    val height: Float,
    val text: String,
    val tool: String,
    val frame: Int,
    val time: Float,
    val rawJson: String? = null
) {
    val centerX: Float get() = x0 + (width / 2f)
    val centerY: Float get() = y0 + (height / 2f)
}

/**
 * Represents a spatial group of text instances occurring in the same screen area.
 */
data class SpatialCluster(
    val id: Int,
    var centerX: Float,
    var centerY: Float,
    val boxes: MutableList<DetectedTargetBox> = mutableListOf(),
    val frameIndices: MutableSet<Int> = mutableSetOf(),
    var threshold: Float = 0f,
    var shortLabel: String = "",
    var displayName: String = ""
)

/**
 * Represents a continuous temporal window/session between scene cuts or pauses.
 */
data class TimeSlotSession(
    val id: Int,
    val startFrame: Int,
    val endFrame: Int,
    val startTime: Float,
    val endTime: Float,
    val duration: Float,
    val frameCount: Int,
    val frameSet: Set<Int>,
    val displayName: String
)

/**
 * High-performance native spatial clustering & timeline windowing engine.
 * Uses exact Euclidean threshold math and universal length-agnostic token-span coordinate matching.
 */
object SpatialClusterer {

    /**
     * Cleans text for uniform casing and punctuation removal (a-z0-9 only).
     */
    fun clean(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val builder = StringBuilder()
        for (ch in input.lowercase()) {
            if (ch in 'a'..'z' || ch in '0'..'9') {
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    /**
     * Groups detected keyword bounding boxes across all video frames into spatial clusters
     * using Euclidean distance thresholds and classifies UI regions dynamically.
     */
    fun clusterBoxes(
        boxes: List<DetectedTargetBox>,
        videoWidth: Int = 1080,
        videoHeight: Int = 2400
    ): List<SpatialCluster> {
        if (boxes.isEmpty()) return emptyList()

        val maxBoxX = boxes.maxOfOrNull { it.x0 + it.width } ?: 1080f
        val maxBoxY = boxes.maxOfOrNull { it.y0 + it.height } ?: 2400f
        val effectiveWidth = max(videoWidth.toFloat(), maxBoxX).coerceAtLeast(100f)
        val effectiveHeight = max(videoHeight.toFloat(), maxBoxY).coerceAtLeast(100f)

        val dynamicThreshold = max(
            50f,
            min(effectiveWidth, effectiveHeight) * 0.085f
        )

        val clusters = mutableListOf<SpatialCluster>()

        for (box in boxes) {
            val bx = box.centerX
            val by = box.centerY

            var matchedCluster: SpatialCluster? = null
            for (cluster in clusters) {
                val dist = hypot(bx - cluster.centerX, by - cluster.centerY)
                if (dist <= dynamicThreshold) {
                    matchedCluster = cluster
                    break
                }
            }

            if (matchedCluster != null) {
                matchedCluster.boxes.add(box)
                matchedCluster.frameIndices.add(box.frame)
                val totalCount = matchedCluster.boxes.size.toFloat()
                matchedCluster.centerX = ((matchedCluster.centerX * (totalCount - 1f)) + bx) / totalCount
                matchedCluster.centerY = ((matchedCluster.centerY * (totalCount - 1f)) + by) / totalCount
            } else {
                val newCluster = SpatialCluster(
                    id = clusters.size,
                    centerX = bx,
                    centerY = by,
                    boxes = mutableListOf(box),
                    frameIndices = mutableSetOf(box.frame),
                    threshold = dynamicThreshold
                )
                clusters.add(newCluster)
            }
        }

        // Universal UI Region Classification based on screen geometry
        for ((index, cluster) in clusters.withIndex()) {
            val rx = cluster.centerX / effectiveWidth
            val ry = cluster.centerY / effectiveHeight

            val regionLabel = when {
                ry <= 0.15f -> "Top Header Area"
                ry >= 0.85f -> "Bottom Footer Area"
                rx <= 0.15f -> "Left Panel / Sidebar"
                rx >= 0.85f -> "Right Panel / Edge"
                else -> "Main Content Area"
            }

            cluster.shortLabel = regionLabel
            cluster.threshold = dynamicThreshold
            val roundedX = cluster.centerX.roundToInt()
            val roundedY = cluster.centerY.roundToInt()
            val frameCount = cluster.frameIndices.size
            cluster.displayName = "📍 Location ${index + 1}: $regionLabel (X ~ ${roundedX}px, Y ~ ${roundedY}px • $frameCount frames)"
        }

        return clusters
    }

    /**
     * Splits bounding box detections into discrete continuous sessions/time-slots based on frame gaps.
     * Prevents visual overlay drift when a UI element disappears and reappears later in the video.
     */
    fun segmentTimeSlots(
        boxes: List<DetectedTargetBox>,
        targetFps: Int = 12
    ): List<TimeSlotSession> {
        if (boxes.isEmpty()) return emptyList()

        val frameMap = LinkedHashMap<Int, Float>()
        for (box in boxes) {
            if (!frameMap.containsKey(box.frame)) {
                frameMap[box.frame] = box.time
            }
        }

        val sortedFrames = frameMap.keys.sorted()
        if (sortedFrames.isEmpty()) return emptyList()

        val sessions = mutableListOf<MutableList<Int>>()
        var currentSession = mutableListOf(sortedFrames[0])
        val gapThresholdFrames = max(8, (targetFps * 0.9f).roundToInt())

        for (i in 1 until sortedFrames.size) {
            val prevFrame = sortedFrames[i - 1]
            val curFrame = sortedFrames[i]

            if (curFrame - prevFrame > gapThresholdFrames) {
                sessions.add(currentSession)
                currentSession = mutableListOf(curFrame)
            } else {
                currentSession.add(curFrame)
            }
        }
        sessions.add(currentSession)

        return sessions.mapIndexed { index, sessionFrames ->
            val startFrame = sessionFrames.first()
            val endFrame = sessionFrames.last()
            val startTime = frameMap[startFrame] ?: (startFrame.toFloat() / targetFps.toFloat())
            val endTime = frameMap[endFrame] ?: (endFrame.toFloat() / targetFps.toFloat())
            val duration = max(0.1f, endTime - startTime)

            val startTimeFormatted = String.format(java.util.Locale.US, "%.1f", startTime)
            val endTimeFormatted = String.format(java.util.Locale.US, "%.1f", endTime)
            val durationFormatted = String.format(java.util.Locale.US, "%.1f", duration)

            TimeSlotSession(
                id = index,
                startFrame = startFrame,
                endFrame = endFrame,
                startTime = startTime,
                endTime = endTime,
                duration = duration,
                frameCount = sessionFrames.size,
                frameSet = sessionFrames.toSet(),
                displayName = "⏱️ Slot ${index + 1}: ${startTimeFormatted}s ➔ ${endTimeFormatted}s (${durationFormatted}s duration • ${sessionFrames.size} frames)"
            )
        }
    }

    /**
     * Universal Length-Agnostic Coordinate Matcher:
     * Handles single words ("playground"), multi-word phrases ("all models"),
     * domains ("aistudio.google.com"), and arbitrary sentences (5 to 50+ words).
     *
     * Finds the exact contiguous sequence of tokens in each line and merges
     * their coordinates into a single unified bounding box.
     */
    fun findMatchingBoundingBoxes(
        lines: List<OcrLineData>,
        query: String
    ): List<ToolsBoundingBox> {
        if (lines.isEmpty() || query.isBlank()) return emptyList()

        val cleanQuery = clean(query)
        if (cleanQuery.isEmpty()) return emptyList()

        val queryTokens = query.trim()
            .split(Regex("\\s+"))
            .map { clean(it) }
            .filter { it.isNotEmpty() }

        val matches = mutableListOf<ToolsBoundingBox>()

        for (line in lines) {
            val cleanLine = clean(line.text)
            if (!cleanLine.contains(cleanQuery)) {
                // Skip line if it doesn't contain the full query string
                continue
            }

            val words = line.words
            if (words.isEmpty()) {
                matches.add(line.bbox)
                continue
            }

            // Strategy 1: Contiguous Token Sequence Match (Word by Word)
            if (queryTokens.isNotEmpty()) {
                val qSize = queryTokens.size
                var matchedSpan: List<OcrWordData>? = null

                for (i in 0..(words.size - qSize)) {
                    var allMatch = true
                    for (j in 0 until qSize) {
                        val wordClean = clean(words[i + j].text)
                        val qWordClean = queryTokens[j]
                        if (wordClean != qWordClean && !wordClean.startsWith(qWordClean)) {
                            allMatch = false
                            break
                        }
                    }
                    if (allMatch) {
                        matchedSpan = words.subList(i, i + qSize)
                        break
                    }
                }

                if (matchedSpan != null && matchedSpan.isNotEmpty()) {
                    val minX = matchedSpan.minOf { it.bbox.x0 }
                    val minY = matchedSpan.minOf { it.bbox.y0 }
                    val maxX = matchedSpan.maxOf { it.bbox.x0 + it.bbox.width }
                    val maxY = matchedSpan.maxOf { it.bbox.y0 + it.bbox.height }
                    matches.add(ToolsBoundingBox(minX, minY, maxX - minX, maxY - minY))
                    continue
                }
            }

            // Strategy 2: Substring Token Span Accumulator (for domains, symbols, and hyphenated text)
            val accumulated = StringBuilder()
            val wordIndices = mutableListOf<Int>()
            var foundSpan = false

            for ((wIdx, word) in words.withIndex()) {
                val cWord = clean(word.text)
                if (cWord.isEmpty()) continue

                accumulated.append(cWord)
                wordIndices.add(wIdx)

                val accStr = accumulated.toString()
                if (accStr.contains(cleanQuery)) {
                    while (wordIndices.size > 1) {
                        val firstIdx = wordIndices.first()
                        val withoutFirst = accStr.substring(clean(words[firstIdx].text).length)
                        if (withoutFirst.contains(cleanQuery)) {
                            wordIndices.removeAt(0)
                        } else {
                            break
                        }
                    }

                    val span = wordIndices.map { words[it] }
                    val minX = span.minOf { it.bbox.x0 }
                    val minY = span.minOf { it.bbox.y0 }
                    val maxX = span.maxOf { it.bbox.x0 + it.bbox.width }
                    val maxY = span.maxOf { it.bbox.y0 + it.bbox.height }
                    matches.add(ToolsBoundingBox(minX, minY, maxX - minX, maxY - minY))
                    foundSpan = true
                    break
                }
            }

            if (!foundSpan) {
                matches.add(line.bbox)
            }
        }

        return matches
    }
}

/**
 * Lightweight OCR model representation used across clustering and calibration.
 */
data class OcrWordData(
    val text: String,
    val bbox: ToolsBoundingBox
)

data class OcrLineData(
    val text: String,
    val bbox: ToolsBoundingBox,
    val words: List<OcrWordData> = emptyList()
)