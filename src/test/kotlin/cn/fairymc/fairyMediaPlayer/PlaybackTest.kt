package cn.fairymc.fairyMediaPlayer

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.MockedStatic
import java.io.File
import java.util.UUID

class PlaybackTest {
    private lateinit var commandPlugin: Main
    private var now = 0L
    private lateinit var tick: Runnable
    private lateinit var staticBukkit: MockedStatic<Bukkit>
    private fun fixture(test: (PlaybackManager, Player, BuiltPack) -> Unit) {
        mockStatic(Bukkit::class.java).use { bukkit ->
            staticBukkit = bukkit
            val scheduler = mock(BukkitScheduler::class.java)
            val plugin = mock(Main::class.java)
            commandPlugin = plugin
            val player = mock(Player::class.java)
            `when`(player.uniqueId).thenReturn(UUID.randomUUID())
            `when`(player.isOnline).thenReturn(true)
            `when`(player.name).thenReturn("Steve")
            `when`(player.hasPermission("fairymediaplayer.use")).thenReturn(true)
            bukkit.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(true)
            bukkit.`when`<BukkitScheduler> { Bukkit.getScheduler() }.thenReturn(scheduler)
            bukkit.`when`<Collection<Player>> { Bukkit.getOnlinePlayers() }.thenReturn(listOf(player))
            bukkit.`when`<Player> { Bukkit.getPlayer(player.uniqueId) }.thenReturn(player)
            val song = Song("a", "A", File("a.mp3"), true, "", 1f, 1.0)
            val settings = PackSettings("ffmpeg", "ffprobe", 10, 75, true, "127.0.0.1", 8163,
                "http://localhost:8163/{sha1}.zip", false, 120)
            val pack = BuiltPack(Library(mapOf("a" to song), emptyMap(), settings, null, 1), File("test.zip"), "a".repeat(40))
            val manager = PlaybackManager(plugin) { now }
            `when`(plugin.playback).thenReturn(manager)
            tick = mockingDetails(scheduler).invocations.first { it.method.name == "runTaskTimer" }.arguments[1] as Runnable
            manager.install(pack)
            test(manager, player, pack)
        }
    }
    private fun loaded(manager: PlaybackManager, player: Player, id: UUID) {
        val event = mock(PlayerResourcePackStatusEvent::class.java)
        `when`(event.player).thenReturn(player)
        `when`(event.id).thenReturn(id)
        `when`(event.status).thenReturn(PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED)
        manager.onPack(event)
    }

    private fun joined(manager: PlaybackManager, player: Player) {
        staticBukkit.`when`<Player> { Bukkit.getPlayer(player.uniqueId) }.thenReturn(player)
        val event = mock(org.bukkit.event.player.PlayerJoinEvent::class.java)
        `when`(event.player).thenReturn(player)
        manager.onJoin(event)
        val task = mockingDetails(Bukkit.getScheduler()).invocations.last { it.method.name == "runTaskLater" }.arguments[1] as Runnable
        task.run()
    }
    private fun newcomer(): Player = mock(Player::class.java).also {
        `when`(it.uniqueId).thenReturn(UUID.randomUUID())
        `when`(it.isOnline).thenReturn(true)
        `when`(it.hasPermission("fairymediaplayer.use")).thenReturn(true)
    }
    private fun segmented(pack: BuiltPack): BuiltPack {
        val song = pack.library.songs.getValue("a").copy(duration = 6.0)
        return BuiltPack(pack.library.copy(songs = mapOf("a" to song)), pack.file, pack.sha1,
            mapOf("a" to (0..2).map { AudioSegment("sync/a/$it", it * 2.0, (it + 1) * 2.0) }))
    }

    @Test fun `join music is locked before delay and while waiting then unlocks at end`() = fixture { manager, player, pack ->
        val next = BuiltPack(pack.library.copy(autoPlay = MediaRequest(MediaType.SONG, "a", RepeatMode.ONCE)), pack.file, pack.sha1)
        manager.install(next)
        val event = mock(org.bukkit.event.player.PlayerJoinEvent::class.java)
        `when`(event.player).thenReturn(player)
        manager.onJoin(event)
        assertTrue(manager.isLocked(player))
        assertThrows(IllegalStateException::class.java) { manager.stopPersonal(player) }
        val task = mockingDetails(Bukkit.getScheduler()).invocations.last { it.method.name == "runTaskLater" }.arguments[1] as Runnable
        task.run()
        assertTrue(manager.status(player)!!.request.locked)
        assertThrows(IllegalStateException::class.java) { manager.sendPackPersonal(player) }
        loaded(manager, player, next.id)
        assertThrows(IllegalStateException::class.java) { manager.playPersonal(player, MediaRequest(MediaType.SONG, "a")) }
        now = 1_000_000_000L; tick.run()
        assertFalse(manager.isLocked(player))
    }

    @Test fun `late player waits for boundary and joins same broadcast fragment`() = fixture { manager, player, pack ->
        val next = segmented(pack)
        manager.install(next)
        manager.playAll(MediaType.SONG, "a", RepeatMode.LOOP)
        loaded(manager, player, next.id)
        now = 2_000_000_000L; tick.run()
        now = 2_700_000_000L
        val late = newcomer()
        joined(manager, late)
        assertTrue(manager.isLocked(late))
        loaded(manager, late, next.id)
        assertTrue(manager.status(late)!!.waitingForSync)
        assertEquals(2.7, manager.status(late)!!.elapsedSeconds, 0.001)
        assertEquals(0, mockingDetails(late).invocations.count { it.method.name == "playSound" })
        now = 4_000_000_000L; tick.run()
        assertFalse(manager.status(late)!!.waitingForSync)
        val oldSound = mockingDetails(player).invocations.last { it.method.name == "playSound" }.arguments[0]
        val newSound = mockingDetails(late).invocations.last { it.method.name == "playSound" }.arguments[0]
        assertEquals(oldSound, newSound)
        assertEquals("sync/a/2", (newSound as net.kyori.adventure.sound.Sound).name().value())
        now = 6_000_000_000L; tick.run()
        assertEquals(0.0, manager.status(late)!!.elapsedSeconds)
        assertTrue(manager.isLocked(late))
    }

    @Test fun `resource download crossing playlist track joins latest track not requested track`() = fixture { manager, player, pack ->
        val base = segmented(pack)
        val next = base.copy(library = base.library.copy(playlists = mapOf("list" to
            Playlist("list", "List", listOf("a", "a"), true, "", RepeatMode.ONCE))))
        manager.install(next)
        manager.playAll(MediaType.PLAYLIST, "list", RepeatMode.ONCE)
        loaded(manager, player, next.id)
        now = 1_000_000_000L
        val late = newcomer(); joined(manager, late)
        now = 6_700_000_000L
        loaded(manager, late, next.id)
        assertEquals(1, manager.status(late)!!.index)
        assertEquals(0.7, manager.status(late)!!.elapsedSeconds, 0.001)
        now = 8_000_000_000L; tick.run()
        assertFalse(manager.status(late)!!.waitingForSync)
        now = 12_000_000_000L; tick.run()
        assertNull(manager.status(late)); assertFalse(manager.isLocked(late))
    }

    @Test fun `broadcast persists without listeners and stopAll prevents new joins from reviving it`() = fixture { manager, player, pack ->
        val next = segmented(pack); manager.install(next)
        manager.playAll(MediaType.SONG, "a", RepeatMode.LOOP)
        loaded(manager, player, next.id)
        val quit = mock(org.bukkit.event.player.PlayerQuitEvent::class.java)
        `when`(quit.player).thenReturn(player); manager.onQuit(quit)
        now = 3_000_000_000L; tick.run()
        val late = newcomer(); joined(manager, late)
        assertTrue(manager.status(late)!!.broadcast)
        staticBukkit.`when`<Collection<Player>> { Bukkit.getOnlinePlayers() }.thenReturn(listOf(late))
        manager.stopAll()
        loaded(manager, late, next.id)
        assertNull(manager.status(late))
        val newer = newcomer(); joined(manager, newer)
        loaded(manager, newer, next.id)
        assertNull(manager.status(newer)); assertFalse(manager.isLocked(newer))
    }

    @Test fun `expired broadcast does not restart on late resource pack load`() = fixture { manager, _, pack ->
        val next = segmented(pack); manager.install(next)
        manager.playAll(MediaType.SONG, "a", RepeatMode.ONCE)
        val late = newcomer(); joined(manager, late)
        now = 7_000_000_000L; tick.run()
        loaded(manager, late, next.id)
        assertNull(manager.status(late)); assertFalse(manager.isLocked(late))
        assertEquals(0, mockingDetails(late).invocations.count { it.method.name == "playSound" })
    }

    @Test fun `new broadcast replaces pending listeners and pack callback uses newest request`() = fixture { manager, player, pack ->
        val next = segmented(pack); manager.install(next)
        manager.playAll(MediaType.SONG, "a", RepeatMode.LOOP)
        now = 1_000_000_000L
        manager.playAll(MediaType.SONG, "a", RepeatMode.ONCE)
        loaded(manager, player, next.id)
        assertEquals(RepeatMode.ONCE, manager.status(player)!!.repeat)
        assertEquals(0.0, manager.status(player)!!.elapsedSeconds)
        now = 7_000_000_000L; tick.run()
        assertFalse(manager.isLocked(player))
    }

    @Test fun `long stall skips stale fragments and rejoins at next boundary`() = fixture { manager, player, pack ->
        val next = segmented(pack); manager.install(next)
        manager.playAll(MediaType.SONG, "a", RepeatMode.LOOP); loaded(manager, player, next.id)
        now = 3_000_000_000L; tick.run()
        assertTrue(manager.status(player)!!.waitingForSync)
        assertEquals(1, mockingDetails(player).invocations.count { it.method.name == "playSound" })
        now = 4_000_000_000L; tick.run()
        assertFalse(manager.status(player)!!.waitingForSync)
        assertEquals(2, mockingDetails(player).invocations.count { it.method.name == "playSound" })
    }

    @Test fun `broadcast timeout releases only failed listener without stopping timeline`() = fixture { manager, player, pack ->
        val next = segmented(pack); manager.install(next)
        manager.playAll(MediaType.SONG, "a", RepeatMode.LOOP); loaded(manager, player, next.id)
        val late = newcomer(); joined(manager, late)
        now = 121_000_000_000L; tick.run()
        assertFalse(manager.isLocked(late)); assertNull(manager.status(late))
        assertTrue(manager.status(player)!!.broadcast)
        val newer = newcomer(); joined(manager, newer)
        assertTrue(manager.status(newer)!!.broadcast)
        assertEquals(1.0, manager.status(newer)!!.elapsedSeconds)
    }

    @Test fun `stop all command clears timeline with an empty online audience`() = fixture { manager, _, pack ->
        val next = segmented(pack); manager.install(next)
        staticBukkit.`when`<Collection<Player>> { Bukkit.getOnlinePlayers() }.thenReturn(emptyList())
        manager.playAll(MediaType.SONG, "a", RepeatMode.LOOP)
        val console = mock(org.bukkit.command.CommandSender::class.java)
        `when`(console.hasPermission("fairymediaplayer.admin")).thenReturn(true)
        MusicCommand(commandPlugin, mock(MusicMenu::class.java)).onCommand(console,
            mock(org.bukkit.command.Command::class.java), "fmp", arrayOf("stop", "all"))
        val late = newcomer(); joined(manager, late); loaded(manager, late, next.id)
        assertNull(manager.status(late)); assertFalse(manager.isLocked(late))
    }
    @Test fun `request waits until matching pack loaded`() = fixture { manager, player, pack ->
        assertEquals(PlayResult.WAITING_FOR_PACK, manager.playSong(player, "a", null))
        loaded(manager, player, UUID.randomUUID())
        assertTrue(manager.status(player)!!.waitingForPack)
        loaded(manager, player, pack.id)
        assertFalse(manager.status(player)!!.waitingForPack)
        assertEquals("a", manager.status(player)!!.songId)
    }
    @Test fun `stop cancels pending playback`() = fixture { manager, player, pack ->
        manager.playSong(player, "a", null)
        manager.stop(player)
        loaded(manager, player, pack.id)
        assertNull(manager.status(player))
    }
    @Test fun `latest request wins during download`() = fixture { manager, player, pack ->
        manager.playSong(player, "a", RepeatMode.ONCE)
        manager.playSong(player, "a", RepeatMode.LOOP)
        loaded(manager, player, pack.id)
        assertEquals(RepeatMode.LOOP, manager.status(player)!!.repeat)
    }
    @Test fun `unknown song does not cancel current request`() = fixture { manager, player, _ ->
        manager.playSong(player, "a", null)
        assertEquals(PlayResult.UNKNOWN_MEDIA, manager.playSong(player, "bad", null))
        assertNotNull(manager.status(player))
    }
    @Test fun `old resource pack callback cannot start after reload`() = fixture { manager, player, pack ->
        manager.playSong(player, "a", null)
        val replacement = BuiltPack(pack.library, pack.file, pack.sha1)
        manager.install(replacement)
        manager.playSong(player, "a", null)
        loaded(manager, player, pack.id)
        assertTrue(manager.status(player)!!.waitingForPack)
        loaded(manager, player, replacement.id)
        assertFalse(manager.status(player)!!.waitingForPack)
    }
    @Test fun `song stops after duration and loop starts another iteration`() = fixture { manager, player, pack ->
        manager.playSong(player, "a", RepeatMode.ONCE)
        loaded(manager, player, pack.id)
        now = 999_000_000L; tick.run()
        assertNotNull(manager.status(player))
        now = 1_000_000_000L; tick.run()
        assertNull(manager.status(player))
        manager.playSong(player, "a", RepeatMode.LOOP)
        now = 2_000_000_000L; tick.run()
        assertNotNull(manager.status(player))
        assertEquals(RepeatMode.LOOP, manager.status(player)!!.repeat)
        assertEquals(3, mockingDetails(player).invocations.count { it.method.name == "playSound" })
    }
    @Test fun `timed out download cancels pending song and late callback`() = fixture { manager, player, pack ->
        manager.playSong(player, "a", null)
        now = 121_000_000_000L; tick.run()
        assertNull(manager.status(player))
        loaded(manager, player, pack.id)
        assertNull(manager.status(player))
        assertEquals(PlayResult.WAITING_FOR_PACK, manager.playSong(player, "a", null))
    }
    @Test fun `declined resource pack cancels pending playback`() = fixture { manager, player, pack ->
        manager.playSong(player, "a", null)
        val event = mock(PlayerResourcePackStatusEvent::class.java)
        `when`(event.player).thenReturn(player)
        `when`(event.id).thenReturn(pack.id)
        `when`(event.status).thenReturn(PlayerResourcePackStatusEvent.Status.DECLINED)
        manager.onPack(event)
        assertNull(manager.status(player))
        loaded(manager, player, pack.id)
        assertNull(manager.status(player))
    }
    @Test fun `broadcast locks pending and active personal controls`() = fixture { manager, player, pack ->
        manager.playAll(MediaType.SONG, "a", RepeatMode.LOOP)
        assertTrue(manager.isLocked(player))
        fun blocked() {
            assertThrows(IllegalStateException::class.java) { manager.playPersonal(player, MediaRequest(MediaType.SONG, "a")) }
            assertThrows(IllegalStateException::class.java) { manager.stopPersonal(player) }
            assertThrows(IllegalStateException::class.java) { manager.sendPackPersonal(player) }
            assertTrue(manager.isLocked(player))
        }
        blocked()
        loaded(manager, player, pack.id)
        blocked()
        now = 1_000_000_000L; tick.run()
        assertTrue(manager.isLocked(player))
    }
    @Test fun `forced once unlocks on natural completion`() = fixture { manager, player, pack ->
        manager.forcePlaySong(player, "a", RepeatMode.ONCE)
        loaded(manager, player, pack.id)
        assertTrue(manager.isLocked(player))
        now = 1_000_000_000L; tick.run()
        assertFalse(manager.isLocked(player))
        assertEquals(PlayResult.PLAYING, manager.playPersonal(player, MediaRequest(MediaType.SONG, "a")))
    }
    @Test fun `forced playlist stays locked between tracks`() = fixture { manager, player, pack ->
        val list = Playlist("list", "List", listOf("a", "a"), true, "", RepeatMode.ONCE)
        val next = BuiltPack(pack.library.copy(playlists = mapOf("list" to list)), pack.file, pack.sha1)
        manager.install(next)
        manager.forcePlayPlaylist(player, "list", RepeatMode.ONCE)
        loaded(manager, player, next.id)
        now = 1_000_000_000L; tick.run()
        assertTrue(manager.isLocked(player))
        assertEquals(1, manager.status(player)!!.index)
        now = 2_000_000_000L; tick.run()
        assertFalse(manager.isLocked(player))
    }
    @Test fun `admin stop unlocks pending and prevents late start`() = fixture { manager, player, pack ->
        manager.forcePlaySong(player, "a", RepeatMode.LOOP)
        manager.stop(player)
        assertFalse(manager.isLocked(player))
        loaded(manager, player, pack.id)
        assertNull(manager.status(player))
    }
    @Test fun `timeout and reload release forced lock`() = fixture { manager, player, pack ->
        manager.forcePlaySong(player, "a", RepeatMode.LOOP)
        now = 121_000_000_000L; tick.run()
        assertFalse(manager.isLocked(player))
        manager.forcePlaySong(player, "a", RepeatMode.LOOP)
        manager.install(BuiltPack(pack.library, pack.file, pack.sha1))
        assertFalse(manager.isLocked(player))
    }
    @Test fun `personal commands cannot bypass broadcast and admin can stop`() = fixture { manager, player, _ ->
        val commands = MusicCommand(commandPlugin, mock(MusicMenu::class.java))
        val command = mock(org.bukkit.command.Command::class.java)
        val console = mock(org.bukkit.command.CommandSender::class.java)
        `when`(console.hasPermission("fairymediaplayer.admin")).thenReturn(true)
        commands.onCommand(console, command, "fmp", arrayOf("play", "song", "a", "all", "loop"))
        assertTrue(manager.isLocked(player))
        for (args in listOf(arrayOf("play", "song", "a"), arrayOf("play", "song", "a", "Steve"),
            arrayOf("stop"), arrayOf("pack"), arrayOf("forceplay", "song", "a"))) {
            commands.onCommand(player, command, "fmp", args)
            assertTrue(manager.isLocked(player), args.joinToString())
            assertEquals(RepeatMode.LOOP, manager.status(player)!!.repeat)
        }
        commands.onCommand(console, command, "fmp", arrayOf("stop", "all"))
        assertFalse(manager.isLocked(player))
        commands.onCommand(player, command, "fmp", arrayOf("play", "song", "a"))
        assertNotNull(manager.status(player))
        assertFalse(manager.isLocked(player))
    }
    @Test fun `explicit forceplay command locks self for administrator`() = fixture { manager, player, _ ->
        `when`(player.hasPermission("fairymediaplayer.admin")).thenReturn(true)
        MusicCommand(commandPlugin, mock(MusicMenu::class.java)).onCommand(player,
            mock(org.bukkit.command.Command::class.java), "fmp", arrayOf("forceplay", "song", "a"))
        assertTrue(manager.isLocked(player))
    }
}
