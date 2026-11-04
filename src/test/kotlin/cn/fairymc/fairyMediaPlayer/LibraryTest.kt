package cn.fairymc.fairyMediaPlayer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LibraryTest {
    @TempDir lateinit var folder: File
    private fun config(extra: String = ""): Library {
        File(folder, "music").mkdirs()
        File(folder, "music/a.mp3").writeBytes(byteArrayOf(1))
        File(folder, "config.yml").writeText("""
            songs:
              a:
                file: a.mp3
                repeat: loop
            playlists:
              list:
                songs: [a, a]
                repeat: once
        """.trimIndent() + "\n" + extra)
        return Library.read(folder)
    }
    @Test fun `single song stops or loops`() {
        val once = PlaybackQueue(listOf("a"), RepeatMode.ONCE)
        assertFalse(once.advance())
        val loop = PlaybackQueue(listOf("a"), RepeatMode.LOOP)
        repeat(5) { assertTrue(loop.advance()); assertEquals("a", loop.current) }
    }
    @Test fun `playlist loops only after last song`() {
        val queue = PlaybackQueue(listOf("a", "b"), RepeatMode.LOOP)
        assertEquals("a", queue.current)
        assertTrue(queue.advance()); assertEquals("b", queue.current)
        assertTrue(queue.advance()); assertEquals("a", queue.current)
    }
    @Test fun `legacy song repeat is ignored and playlist allows duplicates`() {
        val library = config()
        val (songs, repeat) = library.resolve(MediaRequest(MediaType.PLAYLIST, "list"))
        assertEquals(RepeatMode.ONCE, repeat)
        assertEquals(listOf("a", "a"), songs.map { it.id })
        val queue = PlaybackQueue(songs, repeat)
        assertTrue(queue.advance()); assertFalse(queue.advance())
        assertEquals(RepeatMode.ONCE, library.resolve(MediaRequest(MediaType.SONG, "a")).second)
        assertEquals(RepeatMode.LOOP, library.resolve(MediaRequest(MediaType.SONG, "a", RepeatMode.LOOP)).second)
        assertEquals(RepeatMode.ONCE, library.resolve(MediaRequest(MediaType.SONG, "a", RepeatMode.ONCE)).second)
    }
    @Test fun `song repetition applies only to the current playback request`() {
        val library = config()
        library.resolve(MediaRequest(MediaType.SONG, "a", RepeatMode.LOOP))
        assertEquals(RepeatMode.ONCE, library.resolve(MediaRequest(MediaType.SONG, "a")).second)
    }
    @Test fun `playlist retains configured repeat with optional playback override`() {
        val library = config()
        val list = library.playlists.getValue("list").copy(repeat = RepeatMode.LOOP)
        val updated = library.copy(playlists = mapOf("list" to list))
        assertEquals(RepeatMode.LOOP, updated.resolve(MediaRequest(MediaType.PLAYLIST, "list")).second)
        assertEquals(RepeatMode.ONCE, updated.resolve(MediaRequest(MediaType.PLAYLIST, "list", RepeatMode.ONCE)).second)
        assertEquals(RepeatMode.LOOP, updated.resolve(MediaRequest(MediaType.PLAYLIST, "list")).second)
    }
    @Test fun `automatic song repetition is a playback setting`() {
        val library = config("auto-play: {enabled: true, type: song, id: a, repeat: loop}")
        assertEquals(RepeatMode.LOOP, library.resolve(library.autoPlay!!).second)
        assertEquals(RepeatMode.ONCE, library.resolve(MediaRequest(MediaType.SONG, "a")).second)
    }
    @Test fun `invalid automatic target is rejected`() {
        assertThrows(IllegalStateException::class.java) { config("auto-play: {enabled: true, type: song, id: missing}") }
    }
    @Test fun `invalid YAML leaves caller with an exception`() {
        File(folder, "config.yml").writeText("songs: [broken")
        assertThrows(Exception::class.java) { Library.read(folder) }
    }
    @Test fun `rejects file outside music directory`() {
        config()
        File(folder, "outside.mp3").writeBytes(byteArrayOf(1))
        File(folder, "config.yml").writeText("songs:\n  a:\n    file: ../outside.mp3")
        assertThrows(IllegalArgumentException::class.java) { Library.read(folder) }
    }
    @Test fun `rejects empty or invalid playlist`() {
        config()
        File(folder, "config.yml").writeText("playlists:\n  bad:\n    songs: []")
        assertThrows(IllegalArgumentException::class.java) { Library.read(folder) }
        File(folder, "config.yml").writeText("playlists:\n  bad:\n    songs: [missing]")
        assertThrows(IllegalArgumentException::class.java) { Library.read(folder) }
    }
}
