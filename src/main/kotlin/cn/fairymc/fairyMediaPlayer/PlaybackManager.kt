package cn.fairymc.fairyMediaPlayer

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import java.util.UUID

class PlaybackManager(private val plugin: Main, private val nanoTime: () -> Long = System::nanoTime) : FairyMediaPlayerApi, Listener {
    private data class Session(val request: MediaRequest, val queue: PlaybackQueue<Song>, var deadline: Long = 0)
    private data class PackRequest(val id: UUID, val deadline: Long)
    private data class Follower(var joinAt: Long = Long.MAX_VALUE, var partStart: Long? = null, var sound: Sound? = null)
    private var broadcast: BroadcastTimeline? = null
    private val followers = mutableMapOf<UUID, Follower>()
    private val sessions = mutableMapOf<UUID, Session>()
    private val pending = mutableMapOf<UUID, MediaRequest>()
    private val ready = mutableSetOf<UUID>()
    private val requested = mutableMapOf<UUID, PackRequest>()
    // Guards delayed join tasks against commands, stop, quit and pack replacement.
    private val joinTokens = mutableMapOf<UUID, UUID>()
    var pack: BuiltPack? = null
        private set

    init { Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L) }
    private fun mainThread() { check(Bukkit.isPrimaryThread()) { "FairyMediaPlayer API 必须在服务器主线程调用" } }
    private fun sound(song: Song) = Sound.sound(Key.key("fairymediaplayer", song.id), Sound.Source.RECORD, song.volume, 1f)

    fun isLocked(player: Player): Boolean {
        mainThread()
        return player.uniqueId in followers || sessions[player.uniqueId]?.request?.locked == true || pending[player.uniqueId]?.locked == true
    }
    private fun checkPersonalControl(player: Player) {
        check(!isLocked(player)) { "当前为进服、集体或强制播放，个人控制已锁定。请等待播放结束或联系管理员。" }
    }
    fun playPersonal(player: Player, request: MediaRequest): PlayResult {
        checkPersonalControl(player)
        return play(player, request.copy(locked = false))
    }
    fun stopPersonal(player: Player) { checkPersonalControl(player); stop(player) }
    fun sendPackPersonal(player: Player): Boolean { checkPersonalControl(player); return sendPack(player) }

    fun install(next: BuiltPack) {
        mainThread()
        val first = pack == null
        stopAll(); ready.clear(); requested.clear(); joinTokens.clear()
        val oldId = pack?.id
        if (oldId != null) Bukkit.getOnlinePlayers().forEach { it.removeResourcePacks(oldId) }
        pack = next
        Bukkit.getOnlinePlayers().forEach { player ->
            if (first) join(player) else sendPack(player)
        }
    }

    fun sendPack(player: Player): Boolean {
        mainThread()
        val current = pack ?: return false
        if (current.library.songs.isEmpty()) return false
        if (player.uniqueId in requested) return true
        sessions.remove(player.uniqueId)?.let { player.stopSound(sound(it.queue.current)) }
        followers[player.uniqueId]?.let { follower ->
            follower.sound?.let { player.stopSound(it) }
            follower.sound = null; follower.partStart = null; follower.joinAt = Long.MAX_VALUE
        }
        ready.remove(player.uniqueId)
        requested[player.uniqueId] = PackRequest(current.id, nanoTime() + current.library.pack.loadTimeoutSeconds * 1_000_000_000L)
        try {
            player.setResourcePack(current.id, current.url, current.sha1, ui("§f加载§b音乐资源包§f以收听服务器音乐"), current.library.pack.required)
        } catch (e: Exception) {
            requested.remove(player.uniqueId); pending.remove(player.uniqueId); followers.remove(player.uniqueId)
            player.sendMessage(ui("§c音乐资源包发送失败：${e.message}\n§7使用 §b/fmp pack §7重试。"))
            plugin.logger.warning("资源包发送失败：${e.message}")
            return false
        }
        return true
    }

    private fun join(player: Player) {
        val current = pack ?: return
        val token = UUID.randomUUID()
        joinTokens[player.uniqueId] = token
        // Reserve the lock immediately, including the configurable delay before sending the pack.
        val joinRequest = broadcast?.takeIf { it.position(nanoTime()) != null }?.request
            ?: current.library.autoPlay?.copy(locked = true)
        if (joinRequest != null) pending[player.uniqueId] = joinRequest
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline || joinTokens[player.uniqueId] != token || pack !== current) return@Runnable
            joinTokens.remove(player.uniqueId)
            if (broadcast?.position(nanoTime()) != null) subscribe(player)
            else {
                val auto = current.library.autoPlay
                if (auto != null) play(player, auto.copy(locked = true))
                else { pending.remove(player.uniqueId); sendPack(player) }
            }
        }, current.library.joinDelayTicks.coerceAtLeast(1))
    }

    fun play(player: Player, request: MediaRequest): PlayResult {
        mainThread()
        if (!player.isOnline) return PlayResult.PLAYER_OFFLINE
        val current = pack ?: return PlayResult.NOT_READY
        val resolved = try { current.library.resolve(request) } catch (_: IllegalStateException) { return PlayResult.UNKNOWN_MEDIA }
        stop(player)
        if (player.uniqueId !in ready) {
            pending[player.uniqueId] = request
            return if (sendPack(player)) PlayResult.WAITING_FOR_PACK else PlayResult.NOT_READY
        }
        val session = Session(request, PlaybackQueue(resolved.first, resolved.second))
        sessions[player.uniqueId] = session
        start(player, session)
        return PlayResult.PLAYING
    }

    private fun start(player: Player, session: Session) {
        player.playSound(sound(session.queue.current))
        session.deadline = nanoTime() + (session.queue.current.duration * 1_000_000_000).toLong()
    }

    private fun tick() {
        val now = nanoTime()
        syncBroadcast(now)
        sessions.toMap().forEach { (id, session) ->
            val player = Bukkit.getPlayer(id)
            if (player == null) sessions.remove(id)
            else if (now >= session.deadline) {
                player.stopSound(sound(session.queue.current))
                if (session.queue.advance()) start(player, session) else sessions.remove(id)
            }
        }
        requested.filterValues { now >= it.deadline }.keys.toList().forEach { id ->
            requested.remove(id); pending.remove(id); ready.remove(id); followers.remove(id)
            Bukkit.getPlayer(id)?.sendMessage(ui("§c音乐资源包加载超时。§7使用 §b/fmp pack §7重试，仍失败请联系管理员检查下载地址。"))
        }
    }

    private fun subscribe(player: Player): PlayResult {
        if (!player.isOnline) return PlayResult.PLAYER_OFFLINE
        val group = broadcast ?: return PlayResult.NOT_READY
        if (group.position(nanoTime()) == null) return PlayResult.NOT_READY
        stop(player)
        val follower = Follower()
        followers[player.uniqueId] = follower
        if (player.uniqueId !in ready)
            return if (sendPack(player)) PlayResult.WAITING_FOR_PACK else PlayResult.NOT_READY
        armFollower(player.uniqueId, nanoTime())
        syncBroadcast(nanoTime())
        return PlayResult.PLAYING
    }

    private fun armFollower(id: UUID, now: Long) {
        val position = broadcast?.position(now) ?: return
        // Never replay the beginning of an already-running part to a late listener.
        followers[id]?.joinAt = if (now - position.segmentStartNanos <= 50_000_000L) now else position.segmentEndNanos
    }

    private fun syncBroadcast(now: Long) {
        val group = broadcast ?: return
        val position = group.position(now)
        if (position == null) {
            followers.toMap().forEach { (id, follower) -> follower.sound?.let { Bukkit.getPlayer(id)?.stopSound(it) } }
            followers.clear(); broadcast = null
            return
        }
        followers.toMap().forEach { (id, follower) ->
            val player = Bukkit.getPlayer(id)
            if (player == null) { followers.remove(id); return@forEach }
            if (id !in ready || now < follower.joinAt || follower.partStart == position.segmentStartNanos) return@forEach
            // After a long server stall, wait for the next boundary rather than play an outdated fragment.
            if (now - position.segmentStartNanos > 100_000_000L) {
                follower.sound?.let { player.stopSound(it) }
                follower.sound = null; follower.partStart = null; follower.joinAt = position.segmentEndNanos
                return@forEach
            }
            follower.sound?.let { player.stopSound(it) }
            val part = Sound.sound(Key.key("fairymediaplayer", position.segment.key), Sound.Source.RECORD, position.song.volume, 1f)
            player.playSound(part)
            follower.sound = part; follower.partStart = position.segmentStartNanos
        }
    }

    private fun packFailureMessage(status: PlayerResourcePackStatusEvent.Status): String = when (status) {
        PlayerResourcePackStatusEvent.Status.DECLINED -> "已被拒绝"
        PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD -> "下载失败"
        PlayerResourcePackStatusEvent.Status.INVALID_URL -> "下载地址无效"
        PlayerResourcePackStatusEvent.Status.FAILED_RELOAD -> "加载失败"
        PlayerResourcePackStatusEvent.Status.DISCARDED -> "已被取消"
        else -> "未加载"
    }
    @EventHandler fun onJoin(event: PlayerJoinEvent) = join(event.player)
    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        stop(event.player); ready.remove(event.player.uniqueId); requested.remove(event.player.uniqueId)
    }
    @EventHandler fun onPack(event: PlayerResourcePackStatusEvent) {
        val player = event.player
        val id = player.uniqueId
        val current = pack ?: return
        if (event.id != current.id) return
        when (event.status) {
            PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED -> {
                if (requested[id]?.id != event.id) return
                requested.remove(id); ready.add(id)
                if (id in followers) { armFollower(id, nanoTime()); syncBroadcast(nanoTime()) }
                else pending.remove(id)?.let { play(player, it) }
            }
            PlayerResourcePackStatusEvent.Status.ACCEPTED, PlayerResourcePackStatusEvent.Status.DOWNLOADED -> Unit
            else -> {
                stop(player); requested.remove(id); ready.remove(id)
                player.sendMessage(ui("§c音乐资源包${packFailureMessage(event.status)}，无法播放。\n§7使用 §b/fmp pack §7重试。"))
            }
        }
    }

    override fun playSong(player: Player, id: String, repeat: RepeatMode?) = play(player, MediaRequest(MediaType.SONG, id, repeat))
    override fun playPlaylist(player: Player, id: String, repeat: RepeatMode?) = play(player, MediaRequest(MediaType.PLAYLIST, id, repeat))
    override fun forcePlaySong(player: Player, id: String, repeat: RepeatMode?) = play(player, MediaRequest(MediaType.SONG, id, repeat, true))
    override fun forcePlayPlaylist(player: Player, id: String, repeat: RepeatMode?) = play(player, MediaRequest(MediaType.PLAYLIST, id, repeat, true))
    override fun playAll(type: MediaType, id: String, repeat: RepeatMode?): Map<UUID, PlayResult> {
        mainThread()
        val current = pack ?: return Bukkit.getOnlinePlayers().associate { it.uniqueId to PlayResult.NOT_READY }
        val request = MediaRequest(type, id, repeat, true)
        val resolved = try { current.library.resolve(request) } catch (_: IllegalStateException) {
            return Bukkit.getOnlinePlayers().associate { it.uniqueId to PlayResult.UNKNOWN_MEDIA }
        }
        stopAll()
        broadcast = BroadcastTimeline(request, resolved.first, resolved.second, current.segments, nanoTime())
        return Bukkit.getOnlinePlayers().associate { it.uniqueId to subscribe(it) }
    }
    override fun stop(player: Player) {
        mainThread()
        joinTokens.remove(player.uniqueId); pending.remove(player.uniqueId)
        followers.remove(player.uniqueId)?.sound?.let { player.stopSound(it) }
        sessions.remove(player.uniqueId)?.let { player.stopSound(sound(it.queue.current)) }
    }
    override fun stopAll() {
        mainThread()
        Bukkit.getOnlinePlayers().forEach { stop(it) }
        sessions.clear(); pending.clear(); joinTokens.clear(); followers.clear(); broadcast = null
    }
    override fun status(player: Player): PlaybackStatus? {
        mainThread()
        if (player.uniqueId in followers) {
            val group = broadcast
            val position = group?.position(nanoTime())
            if (group != null && position != null) return PlaybackStatus(group.request, position.song.id, position.index,
                group.tracks.size, group.repeat, player.uniqueId !in ready, position.elapsedSeconds, true,
                player.uniqueId in ready && followers[player.uniqueId]?.sound == null)
        }
        sessions[player.uniqueId]?.let { return PlaybackStatus(it.request, it.queue.current.id, it.queue.index,
            it.queue.tracks.size, it.queue.repeat, false,
            (it.queue.current.duration - (it.deadline - nanoTime()) / 1_000_000_000.0).coerceIn(0.0, it.queue.current.duration)) }
        pending[player.uniqueId]?.let {
            val resolved = pack!!.library.resolve(it)
            return PlaybackStatus(it, null, 0, resolved.first.size, resolved.second, true)
        }
        return null
    }
    override fun songIds(): List<String> { mainThread(); return pack?.library?.songs?.keys?.toList().orEmpty() }
    override fun playlistIds(): List<String> { mainThread(); return pack?.library?.playlists?.keys?.toList().orEmpty() }
}

