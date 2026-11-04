package cn.fairymc.fairyMediaPlayer

import kotlin.math.floor

internal data class BroadcastPosition(val song: Song, val index: Int, val elapsedSeconds: Double,
                                      val segment: AudioSegment, val segmentStartNanos: Long,
                                      val segmentEndNanos: Long)

/** Absolute server timeline; downloads, disconnects and an empty audience never restart it. */
internal class BroadcastTimeline(val request: MediaRequest, val tracks: List<Song>, val repeat: RepeatMode,
                                 private val segments: Map<String, List<AudioSegment>>, val startedNanos: Long) {
    private val total = tracks.sumOf { it.duration }
    init { require(tracks.isNotEmpty() && total.isFinite() && tracks.all { it.duration > 0 }) }

    fun position(now: Long): BroadcastPosition? {
        val elapsed = ((now - startedNanos).coerceAtLeast(0) / 1_000_000_000.0)
        if (repeat == RepeatMode.ONCE && elapsed >= total) return null
        val cycle = if (repeat == RepeatMode.LOOP) floor(elapsed / total) else 0.0
        var within = elapsed - cycle * total
        var trackStart = cycle * total
        tracks.forEachIndexed { index, song ->
            if (within < song.duration || index == tracks.lastIndex) {
                val parts = segments[song.id] ?: listOf(AudioSegment(song.id, 0.0, song.duration))
                val part = parts.firstOrNull { within < it.endSeconds } ?: parts.last()
                return BroadcastPosition(song, index, within, part,
                    startedNanos + ((trackStart + part.startSeconds) * 1_000_000_000).toLong(),
                    startedNanos + ((trackStart + part.endSeconds) * 1_000_000_000).toLong())
            }
            within -= song.duration
            trackStart += song.duration
        }
        return null
    }
}
