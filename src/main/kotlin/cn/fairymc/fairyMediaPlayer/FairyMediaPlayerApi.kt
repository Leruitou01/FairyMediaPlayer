package cn.fairymc.fairyMediaPlayer

import org.bukkit.entity.Player
import java.util.UUID

enum class PlayResult { PLAYING, WAITING_FOR_PACK, NOT_READY, PLAYER_OFFLINE, UNKNOWN_MEDIA }
data class PlaybackStatus @JvmOverloads constructor(val request: MediaRequest, val songId: String?, val index: Int,
                          val total: Int, val repeat: RepeatMode, val waitingForPack: Boolean,
                          val elapsedSeconds: Double = 0.0, val broadcast: Boolean = false,
                          val waitingForSync: Boolean = false)

/** Obtain via Bukkit ServicesManager. All methods must be called on the server main thread.
 * Trusted server API: bypasses menu permissions and selectable flags.
 */
interface FairyMediaPlayerApi {
    fun playSong(player: Player, id: String, repeat: RepeatMode?): PlayResult
    fun playPlaylist(player: Player, id: String, repeat: RepeatMode?): PlayResult
    fun forcePlaySong(player: Player, id: String, repeat: RepeatMode?): PlayResult
    fun forcePlayPlaylist(player: Player, id: String, repeat: RepeatMode?): PlayResult
    fun playAll(type: MediaType, id: String, repeat: RepeatMode?): Map<UUID, PlayResult>
    fun stop(player: Player)
    fun stopAll()
    fun status(player: Player): PlaybackStatus?
    fun songIds(): List<String>
    fun playlistIds(): List<String>
}
