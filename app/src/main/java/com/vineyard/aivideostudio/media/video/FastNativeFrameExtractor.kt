package com.vineyard.aivideostudio.media.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Represents a single extracted frame ready for UI display, OCR scanning, and overlay rendering.
 */
data class ExtractedFrame(
    val index: Int,
    val timeSeconds: Float,
    val timeFormatted: String,
    val fullResImagePath: String,   // Path to the cached high-resolution image on disk
    val thumbBitmap: Bitmap,        // Lightweight in-memory bitmap for the filmstrip UI
    var isHighlightEnabled: Boolean = false,
    var hasMismatch: Boolean = false
)

/**
 * Metadata initialization parameters for one of the concurrent IDM worker tasks.
 */
data class WorkerInitInfo(
    val taskId: Int,
    val startFrame: Int,
    val endFrame: Int,
    val totalFrames: Int
)

/**
 * High-performance, hardware-accelerated native frame extraction engine.
 * Leverages sequential MediaCodec hardware decoding with IDM multi-stream progress reporting
 * and automatic container rotation normalization.
 */
class FastNativeFrameExtractor(private val context: Context) {

    /**
     * Sequential Hardware Extraction with 10-Stream IDM Progress Callbacks.
     */
    suspend fun extractFramesWithWorkers(
        videoUri: Uri,
        targetFps: Int,
        onInitWorkers: (List<WorkerInitInfo>) -> Unit,
        onWorkerProgress: (taskId: Int, doneInWorker: Int, totalInWorker: Int) -> Unit,
        onTotalProgress: (current: Int, total: Int) -> Unit
    ): List<ExtractedFrame> = withContext(Dispatchers.IO) {

        val workspaceDir = File(context.filesDir, "editora_frames_workspace")
        if (workspaceDir.exists()) {
            workspaceDir.deleteRecursively()
        }
        workspaceDir.mkdirs()

        val probeRetriever = MediaMetadataRetriever()
        var probePfd: ParcelFileDescriptor? = null
        val durationSec: Float
        val rawWidth: Int
        val rawHeight: Int
        val rotation: Int

        try {
            probePfd = try {
                context.contentResolver.openFileDescriptor(videoUri, "r")
            } catch (_: Exception) {
                null
            }

            if (probePfd != null) {
                probeRetriever.setDataSource(probePfd.fileDescriptor)
            } else {
                probeRetriever.setDataSource(context, videoUri)
            }

            val durationMs = probeRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            durationSec = durationMs / 1000f

            rawWidth = probeRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            rawHeight = probeRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = probeRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            try { probeRetriever.release() } catch (_: Exception) {}
            try { probePfd?.close() } catch (_: Exception) {}
        }

        val totalFrames = floor(durationSec * targetFps).toInt()
        if (totalFrames <= 0) return@withContext emptyList()

        val workerCount = 10
        val chunkSize = ceil(totalFrames.toFloat() / workerCount.toFloat()).toInt()

        val workerInitList = (0 until workerCount).map { i ->
            val start = i * chunkSize
            val end = min(totalFrames - 1, start + chunkSize - 1)
            val framesInWorker = max(0, end - start + 1)
            WorkerInitInfo(
                taskId = i + 1,
                startFrame = start,
                endFrame = end,
                totalFrames = framesInWorker
            )
        }

        withContext(Dispatchers.Main) {
            onInitWorkers(workerInitList)
        }

        // Try sequential hardware decoding via MediaCodec
        val hardwareResult = tryExtractWithMediaCodec(
            videoUri = videoUri,
            targetFps = targetFps,
            totalFrames = totalFrames,
            chunkSize = chunkSize,
            workerInitList = workerInitList,
            workspaceDir = workspaceDir,
            rotation = rotation,
            onWorkerProgress = onWorkerProgress,
            onTotalProgress = onTotalProgress
        )

        if (hardwareResult.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                onTotalProgress(hardwareResult.size, totalFrames)
            }
            return@withContext hardwareResult
        }

        // Fallback to MediaMetadataRetriever if needed
        return@withContext fallbackExtractWithRetriever(
            videoUri = videoUri,
            targetFps = targetFps,
            totalFrames = totalFrames,
            chunkSize = chunkSize,
            workerInitList = workerInitList,
            workspaceDir = workspaceDir,
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            rotation = rotation,
            onWorkerProgress = onWorkerProgress,
            onTotalProgress = onTotalProgress
        )
    }

    /**
     * Ultra-fast sequential hardware extraction using MediaExtractor and MediaCodec.
     */
    private suspend fun tryExtractWithMediaCodec(
        videoUri: Uri,
        targetFps: Int,
        totalFrames: Int,
        chunkSize: Int,
        workerInitList: List<WorkerInitInfo>,
        workspaceDir: File,
        rotation: Int,
        onWorkerProgress: (taskId: Int, doneInWorker: Int, totalInWorker: Int) -> Unit,
        onTotalProgress: (current: Int, total: Int) -> Unit
    ): List<ExtractedFrame> {
        val extractedFrames = mutableListOf<ExtractedFrame>()
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            extractor = MediaExtractor()
            pfd = try {
                context.contentResolver.openFileDescriptor(videoUri, "r")
            } catch (_: Exception) {
                null
            }

            if (pfd != null) {
                extractor.setDataSource(pfd.fileDescriptor)
            } else {
                extractor.setDataSource(context, videoUri, null)
            }

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) return emptyList()

            extractor.selectTrack(videoTrackIndex)
            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()

            val intervalUs = 1_000_000L / targetFps
            var nextTargetFrameIndex = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var isEosInput = false
            var isEosOutput = false

            val workerDoneMap = IntArray(workerInitList.size) { 0 }

            while (!isEosOutput && nextTargetFrameIndex < totalFrames) {
                if (!isEosInput) {
                    val inIndex = decoder.dequeueInputBuffer(5000)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEosInput = true
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 5000)
                if (outIndex >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEosOutput = true
                    }

                    val targetTimeUs = nextTargetFrameIndex * intervalUs
                    val pts = bufferInfo.presentationTimeUs

                    if (pts >= targetTimeUs - (intervalUs / 2) && bufferInfo.size > 0) {
                        val outputImage = decoder.getOutputImage(outIndex)
                        if (outputImage != null) {
                            val frameFile = File(workspaceDir, "frame_$nextTargetFrameIndex.jpg")
                            val saved = saveImageAsJpeg(outputImage, frameFile, rotation)
                            outputImage.close()

                            if (saved && frameFile.exists()) {
                                val timeSec = targetTimeUs / 1_000_000f
                                val timeFormatted = String.format(Locale.US, "%.2f", timeSec)

                                val thumbBitmap = decodeSmallThumb(frameFile)

                                extractedFrames.add(
                                    ExtractedFrame(
                                        index = nextTargetFrameIndex,
                                        timeSeconds = timeSec,
                                        timeFormatted = timeFormatted,
                                        fullResImagePath = frameFile.absolutePath,
                                        thumbBitmap = thumbBitmap
                                    )
                                )

                                val currentWorkerIdx = (nextTargetFrameIndex / chunkSize).coerceIn(0, workerInitList.size - 1)
                                workerDoneMap[currentWorkerIdx]++
                                val workerInfo = workerInitList[currentWorkerIdx]

                                withContext(Dispatchers.Main) {
                                    onWorkerProgress(workerInfo.taskId, workerDoneMap[currentWorkerIdx], workerInfo.totalFrames)
                                    onTotalProgress(extractedFrames.size, totalFrames)
                                }

                                nextTargetFrameIndex++
                            }
                        }
                    }

                    decoder.releaseOutputBuffer(outIndex, false)
                }
            }

            return extractedFrames
        } catch (_: Exception) {
            return emptyList()
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Resilient fallback frame extraction using MediaMetadataRetriever across 10 streams.
     */
    private suspend fun fallbackExtractWithRetriever(
        videoUri: Uri,
        targetFps: Int,
        totalFrames: Int,
        chunkSize: Int,
        workerInitList: List<WorkerInitInfo>,
        workspaceDir: File,
        rawWidth: Int,
        rawHeight: Int,
        rotation: Int,
        onWorkerProgress: (taskId: Int, doneInWorker: Int, totalInWorker: Int) -> Unit,
        onTotalProgress: (current: Int, total: Int) -> Unit
    ): List<ExtractedFrame> {
        val extractedFrames = mutableListOf<ExtractedFrame>()
        val retriever = MediaMetadataRetriever()
        var pfd: ParcelFileDescriptor? = null

        val isRotated = rotation == 90 || rotation == 270
        val origWidth = if (isRotated) rawHeight else rawWidth
        val origHeight = if (isRotated) rawWidth else rawHeight

        val maxAllowed = 1280
        var decW = rawWidth
        var decH = rawHeight

        if (origWidth > 0 && origHeight > 0) {
            if (origWidth >= origHeight && origWidth > maxAllowed) {
                val scale = maxAllowed.toFloat() / origWidth
                decW = if (isRotated) (rawWidth * scale).roundToInt() else maxAllowed
                decH = if (isRotated) maxAllowed else (rawHeight * scale).roundToInt()
            } else if (origHeight > origWidth && origHeight > maxAllowed) {
                val scale = maxAllowed.toFloat() / origHeight
                decW = if (isRotated) maxAllowed else (rawWidth * scale).roundToInt()
                decH = if (isRotated) (rawHeight * scale).roundToInt() else maxAllowed
            }
        }

        try {
            pfd = try {
                context.contentResolver.openFileDescriptor(videoUri, "r")
            } catch (_: Exception) {
                null
            }

            if (pfd != null) {
                retriever.setDataSource(pfd.fileDescriptor)
            } else {
                retriever.setDataSource(context, videoUri)
            }

            val intervalUs = 1_000_000L / targetFps
            val workerDoneMap = IntArray(workerInitList.size) { 0 }

            for (i in 0 until totalFrames) {
                val targetTimeUs = i * intervalUs
                val timeSec = targetTimeUs / 1_000_000f

                val frameBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && decW > 0 && decH > 0) {
                    retriever.getScaledFrameAtTime(
                        targetTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        decW,
                        decH
                    ) ?: retriever.getScaledFrameAtTime(
                        targetTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        decW,
                        decH
                    ) ?: retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } else {
                    retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: continue

                val thumbWidth = 120
                val aspect = frameBitmap.height.toFloat() / frameBitmap.width.toFloat()
                val thumbHeight = (thumbWidth * aspect).roundToInt().coerceAtLeast(1)
                val thumbBitmap = Bitmap.createScaledBitmap(frameBitmap, thumbWidth, thumbHeight, false)

                val frameFile = File(workspaceDir, "frame_$i.jpg")
                try {
                    BufferedOutputStream(FileOutputStream(frameFile), 65536).use { outStream ->
                        frameBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outStream)
                    }
                } catch (_: Exception) {}

                frameBitmap.recycle()

                val timeFormatted = String.format(Locale.US, "%.2f", timeSec)
                extractedFrames.add(
                    ExtractedFrame(
                        index = i,
                        timeSeconds = timeSec,
                        timeFormatted = timeFormatted,
                        fullResImagePath = frameFile.absolutePath,
                        thumbBitmap = thumbBitmap
                    )
                )

                val currentWorkerIdx = (i / chunkSize).coerceIn(0, workerInitList.size - 1)
                workerDoneMap[currentWorkerIdx]++
                val workerInfo = workerInitList[currentWorkerIdx]

                withContext(Dispatchers.Main) {
                    onWorkerProgress(workerInfo.taskId, workerDoneMap[currentWorkerIdx], workerInfo.totalFrames)
                    onTotalProgress(extractedFrames.size, totalFrames)
                }
            }
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }

        return extractedFrames
    }

    /**
     * Selective Frame Range Extraction for Audio Cue Sync Mode.
     */
    suspend fun extractFrameRange(
        videoUri: Uri,
        startFrame: Int,
        endFrame: Int,
        targetFps: Int,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<ExtractedFrame> = withContext(Dispatchers.IO) {
        val workspaceDir = File(context.filesDir, "editora_frames_workspace")
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
        }

        val intervalUs = 1_000_000L / targetFps
        val totalToExtract = (endFrame - startFrame + 1).coerceAtLeast(0)
        if (totalToExtract <= 0) return@withContext emptyList()

        val retriever = MediaMetadataRetriever()
        var pfd: ParcelFileDescriptor? = null
        val resultFrames = mutableListOf<ExtractedFrame>()

        try {
            pfd = try {
                context.contentResolver.openFileDescriptor(videoUri, "r")
            } catch (_: Exception) {
                null
            }

            if (pfd != null) {
                retriever.setDataSource(pfd.fileDescriptor)
            } else {
                retriever.setDataSource(context, videoUri)
            }

            var doneCount = 0
            for (i in startFrame..endFrame) {
                if (!isActive) break

                val targetTimeUs = i * intervalUs
                val timeSec = targetTimeUs / 1_000_000f

                val frameBitmap = retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: continue

                val thumbWidth = 120
                val aspect = frameBitmap.height.toFloat() / frameBitmap.width.toFloat()
                val thumbHeight = (thumbWidth * aspect).roundToInt().coerceAtLeast(1)
                val thumbBitmap = Bitmap.createScaledBitmap(frameBitmap, thumbWidth, thumbHeight, false)

                val frameFile = File(workspaceDir, "frame_$i.jpg")
                try {
                    BufferedOutputStream(FileOutputStream(frameFile), 65536).use { outStream ->
                        frameBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outStream)
                    }
                } catch (_: Exception) {}

                frameBitmap.recycle()

                val timeFormatted = String.format(Locale.US, "%.2f", timeSec)
                resultFrames.add(
                    ExtractedFrame(
                        index = i,
                        timeSeconds = timeSec,
                        timeFormatted = timeFormatted,
                        fullResImagePath = frameFile.absolutePath,
                        thumbBitmap = thumbBitmap
                    )
                )

                doneCount++
                withContext(Dispatchers.Main) {
                    onProgress(doneCount, totalToExtract)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }

        return@withContext resultFrames
    }

    /**
     * Converts a raw YUV_420_888 MediaCodec output image directly to JPEG,
     * rotating by [rotation] degrees so vertical videos are never displayed sideways.
     */
    private fun saveImageAsJpeg(image: Image, outputFile: File, rotation: Int): Boolean {
        return try {
            val nv21Bytes = yuv420888ToNv21(image)
            val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, image.width, image.height, null)

            if (rotation == 90 || rotation == 180 || rotation == 270) {
                val baos = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 75, baos)
                val rawBytes = baos.toByteArray()

                val rawBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                if (rawBitmap != null) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotatedBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                    rawBitmap.recycle()

                    BufferedOutputStream(FileOutputStream(outputFile), 65536).use { fos ->
                        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, fos)
                    }
                    rotatedBitmap.recycle()
                    return true
                }
            }

            // Normal unrotated orientation
            BufferedOutputStream(FileOutputStream(outputFile), 65536).use { fos ->
                yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 75, fos)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Efficiently converts android.media.Image (YUV_420_888) to NV21 byte array.
     */
    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        var pos = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            val yRow = ByteArray(yRowStride)
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                if (yPixelStride == 1) {
                    yBuffer.get(nv21, pos, width)
                    pos += width
                } else {
                    yBuffer.get(yRow, 0, min(yRowStride, yBuffer.remaining()))
                    for (col in 0 until width) {
                        nv21[pos++] = yRow[col * yPixelStride]
                    }
                }
            }
        }

        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        val uvHeight = height / 2
        val uvWidth = width / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val vIndex = row * uvRowStride + col * uvPixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }

        return nv21
    }

    /**
     * Decodes a fast subsampled thumbnail bitmap for the UI filmstrip.
     */
    private fun decodeSmallThumb(file: File): Bitmap {
        return try {
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            val fullBmp = BitmapFactory.decodeFile(file.absolutePath, options)
            if (fullBmp != null) {
                val thumbWidth = 120
                val aspect = fullBmp.height.toFloat() / fullBmp.width.toFloat()
                val thumbHeight = (thumbWidth * aspect).roundToInt().coerceAtLeast(1)
                val thumb = Bitmap.createScaledBitmap(fullBmp, thumbWidth, thumbHeight, false)
                fullBmp.recycle()
                thumb
            } else {
                Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
            }
        } catch (_: Exception) {
            Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * Backward-compatible frame extraction interface.
     */
    suspend fun extractFrames(
        videoUri: Uri,
        targetFps: Int,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<ExtractedFrame> {
        return extractFramesWithWorkers(
            videoUri = videoUri,
            targetFps = targetFps,
            onInitWorkers = {},
            onWorkerProgress = { _, _, _ -> },
            onTotalProgress = onProgress
        )
    }

    /**
     * Cleans up the disk workspace. Should be called when a new video is loaded or the app is closed.
     */
    fun clearWorkspace() {
        try {
            val workspaceDir = File(context.filesDir, "editora_frames_workspace")
            if (workspaceDir.exists()) {
                workspaceDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }
}