package com.vineyard.aivideostudio.media.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.vineyard.aivideostudio.media.tools.OcrLineData
import com.vineyard.aivideostudio.media.tools.OcrWordData
import com.vineyard.aivideostudio.media.tools.ToolsBoundingBox
import com.vineyard.aivideostudio.media.video.ExtractedFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * Structured OCR result representing all detected text blocks, lines, and words on a single frame.
 */
data class FrameOcrData(
    val frameIndex: Int,
    val time: Float,
    val lines: List<OcrLineData>
) {
    /**
     * Serializes this frame's OCR bounding boxes into standard JSON format
     * matching the exact schema expected by the timeline exporter and ZIP parser.
     */
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("frame", frameIndex)
        val formattedTime = String.format(Locale.US, "%.2f", time).toDoubleOrNull() ?: time.toDouble()
        root.put("time", formattedTime)

        val linesArray = JSONArray()
        for (line in lines) {
            val lineObj = JSONObject()
            lineObj.put("text", line.text)

            val bboxObj = JSONObject()
            bboxObj.put("x0", line.bbox.x0.toInt())
            bboxObj.put("y0", line.bbox.y0.toInt())
            bboxObj.put("width", line.bbox.width.toInt())
            bboxObj.put("height", line.bbox.height.toInt())
            lineObj.put("bbox", bboxObj)

            val wordsArray = JSONArray()
            for (word in line.words) {
                val wordObj = JSONObject()
                wordObj.put("text", word.text)

                val wordBboxObj = JSONObject()
                wordBboxObj.put("x0", word.bbox.x0.toInt())
                wordBboxObj.put("y0", word.bbox.y0.toInt())
                wordBboxObj.put("width", word.bbox.width.toInt())
                wordBboxObj.put("height", word.bbox.height.toInt())
                wordObj.put("bbox", wordBboxObj)

                wordsArray.put(wordObj)
            }
            lineObj.put("words", wordsArray)
            linesArray.put(lineObj)
        }
        root.put("lines", linesArray)
        return root.toString(2)
    }
}

/**
 * High-speed native OCR engine using on-device Google ML Kit Vision.
 * Features automatic programmatic ROI masking and perceptual frame deduplication.
 */
class NativeBatchOcrEngine {

    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Scans a single frame with optional programmatic ROI masking.
     * When [roiMask] is provided, isolates only the target coordinate window in memory.
     */
    suspend fun scanFrame(
        frame: ExtractedFrame,
        roiMask: ToolsBoundingBox? = null
    ): FrameOcrData = withContext(Dispatchers.IO) {
        val file = File(frame.fullResImagePath)
        if (!file.exists()) {
            return@withContext FrameOcrData(frame.index, frame.timeSeconds, emptyList())
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val fullBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            ?: return@withContext FrameOcrData(frame.index, frame.timeSeconds, emptyList())

        var targetBitmap = fullBitmap
        var offsetX = 0f
        var offsetY = 0f

        // Programmatic Blackout / ROI Crop Masking (Zero Drag)
        if (roiMask != null && roiMask.width > 5f && roiMask.height > 5f) {
            val rx = roiMask.x0.toInt().coerceIn(0, fullBitmap.width - 1)
            val ry = roiMask.y0.toInt().coerceIn(0, fullBitmap.height - 1)
            val rw = roiMask.width.toInt().coerceIn(1, fullBitmap.width - rx)
            val rh = roiMask.height.toInt().coerceIn(1, fullBitmap.height - ry)

            try {
                targetBitmap = Bitmap.createBitmap(fullBitmap, rx, ry, rw, rh)
                offsetX = rx.toFloat()
                offsetY = ry.toFloat()
            } catch (_: Exception) {
                targetBitmap = fullBitmap
            }
        }

        return@withContext try {
            val inputImage = InputImage.fromBitmap(targetBitmap, 0)
            val visionText: Text = textRecognizer.process(inputImage).await()
            val extractedLines = parseVisionText(visionText, offsetX, offsetY)

            FrameOcrData(
                frameIndex = frame.index,
                time = frame.timeSeconds,
                lines = extractedLines
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            FrameOcrData(frame.index, frame.timeSeconds, emptyList())
        } finally {
            if (targetBitmap != fullBitmap && !targetBitmap.isRecycled) {
                targetBitmap.recycle()
            }
            if (!fullBitmap.isRecycled) {
                fullBitmap.recycle()
            }
        }
    }

    /**
     * Scans multiple frames in parallel with optional programmatic ROI masking.
     */
    suspend fun scanBatch(
        frames: List<ExtractedFrame>,
        roiMask: ToolsBoundingBox? = null,
        parallelWorkers: Int = 5,
        onProgress: (current: Int, total: Int) -> Unit
    ): Map<Int, FrameOcrData> = withContext(Dispatchers.IO) {
        val totalCount = frames.size
        if (totalCount == 0) return@withContext emptyMap()

        val sortedFrames = frames.sortedBy { it.index }
        val resultsMap = ConcurrentHashMap<Int, FrameOcrData>()
        val completedCounter = AtomicInteger(0)

        val workerCount = min(parallelWorkers.coerceAtLeast(1), 5)
        val chunkSize = ceil(totalCount.toFloat() / workerCount.toFloat()).toInt()

        val deferredWorkers = (0 until workerCount).map { workerIndex ->
            val startIdx = workerIndex * chunkSize
            val endIdx = min(totalCount, startIdx + chunkSize)

            async(Dispatchers.IO) {
                if (startIdx >= endIdx) return@async

                var lastScannedFrame: ExtractedFrame? = null
                var lastOcrData: FrameOcrData? = null

                val batchSize = 10
                var batchStart = startIdx

                while (batchStart < endIdx && isActive) {
                    val batchEnd = min(endIdx, batchStart + batchSize)
                    var batchDoneCount = 0

                    for (i in batchStart until batchEnd) {
                        if (!isActive) break
                        val frame = sortedFrames[i]

                        // Check perceptual similarity within this continuous timeline segment
                        if (roiMask == null && lastScannedFrame != null && lastOcrData != null &&
                            areBitmapsSimilar(lastScannedFrame.thumbBitmap, frame.thumbBitmap)
                        ) {
                            val reusedData = FrameOcrData(
                                frameIndex = frame.index,
                                time = frame.timeSeconds,
                                lines = lastOcrData.lines
                            )
                            resultsMap[frame.index] = reusedData
                        } else {
                            val ocrData = scanFrame(frame, roiMask)
                            resultsMap[frame.index] = ocrData
                            lastScannedFrame = frame
                            lastOcrData = ocrData
                        }

                        batchDoneCount++
                    }

                    if (batchDoneCount > 0) {
                        val done = completedCounter.addAndGet(batchDoneCount)
                        withContext(Dispatchers.Main) {
                            onProgress(done, totalCount)
                        }
                    }

                    batchStart = batchEnd
                }
            }
        }

        deferredWorkers.awaitAll()

        withContext(Dispatchers.Main) {
            onProgress(resultsMap.size, totalCount)
        }

        resultsMap.toSortedMap()
    }

    /**
     * Fast sub-millisecond perceptual difference check using the in-memory thumbnails.
     */
    private fun areBitmapsSimilar(bmpA: Bitmap?, bmpB: Bitmap?, thresholdPercent: Float = 2.0f): Boolean {
        if (bmpA == null || bmpB == null) return false
        if (bmpA.isRecycled || bmpB.isRecycled) return false
        if (bmpA.width != bmpB.width || bmpA.height != bmpB.height) return false

        val w = bmpA.width
        val h = bmpA.height
        val step = 4
        var diffCount = 0
        var sampledCount = 0

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val pixelA = bmpA.getPixel(x, y)
                val pixelB = bmpB.getPixel(x, y)
                sampledCount++

                if (pixelA != pixelB) {
                    val rDiff = abs((pixelA shr 16 and 0xFF) - (pixelB shr 16 and 0xFF))
                    val gDiff = abs((pixelA shr 8 and 0xFF) - (pixelB shr 8 and 0xFF))
                    val bDiff = abs((pixelA and 0xFF) - (pixelB and 0xFF))
                    if (rDiff + gDiff + bDiff > 40) {
                        diffCount++
                    }
                }
            }
        }

        if (sampledCount == 0) return false
        val diffRatio = (diffCount.toFloat() / sampledCount.toFloat()) * 100f
        return diffRatio <= thresholdPercent
    }

    /**
     * Converts ML Kit Vision Text structure into normalized [OcrLineData] and [OcrWordData],
     * mapping cropped patch coordinates back to the full video surface via [offsetX] and [offsetY].
     */
    private fun parseVisionText(visionText: Text, offsetX: Float = 0f, offsetY: Float = 0f): List<OcrLineData> {
        val linesList = mutableListOf<OcrLineData>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim().replace(Regex("\\s+"), " ")
                if (lineText.isEmpty()) continue

                val lineRect = line.boundingBox ?: Rect(0, 0, 0, 0)
                val lineBoundingBox = ToolsBoundingBox(
                    x0 = lineRect.left.toFloat() + offsetX,
                    y0 = lineRect.top.toFloat() + offsetY,
                    width = lineRect.width().toFloat(),
                    height = lineRect.height().toFloat()
                )

                val wordsList = mutableListOf<OcrWordData>()
                for (element in line.elements) {
                    val wordText = element.text.trim()
                    if (wordText.isEmpty()) continue

                    val elementRect = element.boundingBox ?: Rect(0, 0, 0, 0)
                    val wordBoundingBox = ToolsBoundingBox(
                        x0 = elementRect.left.toFloat() + offsetX,
                        y0 = elementRect.top.toFloat() + offsetY,
                        width = elementRect.width().toFloat(),
                        height = elementRect.height().toFloat()
                    )

                    wordsList.add(
                        OcrWordData(
                            text = wordText,
                            bbox = wordBoundingBox
                        )
                    )
                }

                linesList.add(
                    OcrLineData(
                        text = lineText,
                        bbox = lineBoundingBox,
                        words = wordsList
                    )
                )
            }
        }

        return linesList
    }

    /**
     * Releases ML Kit native resources when shutting down the OCR subsystem.
     */
    fun close() {
        try {
            textRecognizer.close()
        } catch (_: Exception) {}
    }
}