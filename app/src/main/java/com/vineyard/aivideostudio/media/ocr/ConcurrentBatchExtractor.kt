package com.vineyard.aivideostudio.media.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * IDM-style concurrent batch extractor.
 * Segments total video frames into discrete chunks and runs up to
 * [maxConcurrentWorkers] parallel streams pulling [batchSize] frames simultaneously.
 */
class ConcurrentBatchExtractor(
    private val maxConcurrentWorkers: Int = 10,
    private val batchSize: Int = 100
) {

    data class BatchChunk(
        val chunkId: Int,
        val timestampsMs: List<Long>,
        val indices: List<Int>
    )

    data class ChunkResult(
        val chunkId: Int,
        val frames: List<Pair<Int, Bitmap>>
    )

    /**
     * Executes parallel frame extraction across [timestampsMs].
     *
     * @param timestampsMs Ordered timestamps to extract.
     * @param onProgress Callback receiving (completedFrames, totalFrames).
     * @param extractSingleFrame Lambda executing the extraction of one frame by timestamp.
     * @return Map of frame index to extracted [Bitmap].
     */
    suspend fun extractInParallel(
        timestampsMs: List<Long>,
        onProgress: (completed: Int, total: Int) -> Unit,
        extractSingleFrame: suspend (timestampMs: Long, index: Int) -> Bitmap?
    ): Map<Int, Bitmap> = withContext(Dispatchers.IO) {
        val total = timestampsMs.size
        if (total == 0) return@withContext emptyMap()

        val indexedTimestamps = timestampsMs.mapIndexed { idx, time -> Pair(idx, time) }
        val chunks = indexedTimestamps.chunked(batchSize).mapIndexed { chunkIdx, items ->
            BatchChunk(
                chunkId = chunkIdx,
                timestampsMs = items.map { it.second },
                indices = items.map { it.first }
            )
        }

        val completedCounter = AtomicInteger(0)
        val concurrencyLimiter = Semaphore(maxConcurrentWorkers)
        val resultMap = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()

        coroutineScope {
            val taskList = chunks.map { chunk ->
                async(Dispatchers.IO) {
                    concurrencyLimiter.withPermit {
                        var processedInBatch = 0
                        for (i in chunk.indices.indices) {
                            val frameIdx = chunk.indices[i]
                            val timeMs = chunk.timestampsMs[i]
                            
                            val bmp = extractSingleFrame(timeMs, frameIdx)
                            if (bmp != null) {
                                resultMap[frameIdx] = bmp
                            }
                            processedInBatch++
                        }
                        
                        // Emit progress per batch (steps of 100) instead of frame-by-frame (+1)
                        if (processedInBatch > 0) {
                            val done = completedCounter.addAndGet(processedInBatch)
                            onProgress(done, total)
                        }
                    }
                }
            }
            taskList.awaitAll()
        }

        resultMap.toSortedMap()
    }
}