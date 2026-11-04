package cn.fairymc.fairyMediaPlayer

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

class MusicCommand(private val plugin: Main, private val menu: MusicMenu) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        try {
            val action = args.firstOrNull()?.lowercase() ?: "menu"
            if (action in setOf("reload", "build")) {
                admin(sender); require(args.size == 1) { "用法：/fmp $action" }
                plugin.rebuild(sender); return true
            }
            require(sender.hasPermission("fairymediaplayer.use") || sender.hasPermission("fairymediaplayer.admin")) { "你没有使用音乐播放器的权限。" }
            when (action) {
                "menu" -> menu.open(sender as? Player ?: error("控制台请使用 /fmp help 查看指令。"))
                "help" -> tell(sender, "§bFairyMediaPlayer §f指令帮助\n \n§b/fmp menu §7打开音乐菜单\n§b/fmp list §7查看可点播编号\n§b/fmp play <song|playlist> <ID> [玩家|all] [once|loop]\n§b/fmp forceplay <song|playlist> <ID> [玩家|all] [once|loop] §8管理员强制播放\n§b/fmp stop [玩家|all] §7停止播放及等待请求\n§b/fmp status [玩家|all] §7查看播放状态\n§b/fmp pack [玩家|all] §7重新加载音乐资源包\n§b/fmp reload §7重新读取配置并构建资源包\n§b/fmp build §7同 reload\n \n§7song 歌曲 / playlist 歌单 / once 播放一次 / loop 循环播放")
                "list" -> {
                    val library = library()
                    val songs = library.songs.keys.filter { sender !is Player || maySelect(sender, library, MediaRequest(MediaType.SONG, it)) }
                    val lists = library.playlists.keys.filter { sender !is Player || maySelect(sender, library, MediaRequest(MediaType.PLAYLIST, it)) }
                    tell(sender, "§f歌曲 §b${songs.joinToString().ifEmpty { "无" }}\n§f歌单 §b${lists.joinToString().ifEmpty { "无" }}")
                }
                "play", "forceplay" -> {
                    if (action == "forceplay") admin(sender)
                    require(args.size in 3..5) { "用法：/fmp $action <song|playlist> <ID> [玩家|all] [once|loop]" }
                    val type = when (args[1].lowercase()) {
                        "song" -> MediaType.SONG
                        "playlist" -> MediaType.PLAYLIST
                        else -> error("类型必须为 song 或 playlist")
                    }
                    val shortRepeat = args.size == 4 && args[3].lowercase() in setOf("once", "loop")
                    val target = if (shortRepeat) null else args.getOrNull(3)
                    val mode = if (shortRepeat) args[3] else args.getOrNull(4)
                    val repeat = when (mode?.lowercase()) {
                        null -> null
                        "once" -> RepeatMode.ONCE
                        "loop" -> RepeatMode.LOOP
                        else -> error("播放模式必须为 once 或 loop")
                    }
                    val managed = action == "forceplay" || target != null &&
                        (target.equals("all", true) || target == "*" || sender !is Player || !target.equals(sender.name, true))
                    val request = MediaRequest(type, args[2], repeat, locked = managed)
                    val library = library()
                    library.resolve(request)
                    val players = targets(sender, target)
                    if (sender is Player && !sender.hasPermission("fairymediaplayer.admin"))
                        require(maySelect(sender, library, request)) { "这首歌曲或歌单不可点播。" }
                    val results = (if (target.equals("all", true) || target == "*")
                        plugin.playback.playAll(type, args[2], repeat).values
                    else players.map {
                        if (managed) plugin.playback.play(it, request) else plugin.playback.playPersonal(it, request)
                    }).groupingBy { it }.eachCount()
                    tell(sender, if (results.isEmpty()) {
                        if (target.equals("all", true) || target == "*") "§f集体播放已开始。§7当前没有在线玩家，新进玩家会加入当前进度。"
                        else "§c没有在线玩家。"
                    } else results.entries.joinToString("\n") { "${resultMessage(it.key)} §7（§a${it.value} §7人）" })
                }
                "stop", "pack", "status" -> {
                    require(args.size <= 2) { "用法：/fmp $action [玩家|all]" }
                    val players = targets(sender, args.getOrNull(1))
                    when (action) {
                        "stop" -> {
                            if (args.getOrNull(1).equals("all", true) || args.getOrNull(1) == "*") plugin.playback.stopAll()
                            else players.forEach {
                                if (sender.hasPermission("fairymediaplayer.admin")) plugin.playback.stop(it) else plugin.playback.stopPersonal(it)
                            }
                            tell(sender, "已停止 §b${players.size} §f名玩家的音乐及等待请求。")
                        }
                        "pack" -> {
                            val count = players.count {
                                if (sender.hasPermission("fairymediaplayer.admin")) plugin.playback.sendPack(it) else plugin.playback.sendPackPersonal(it)
                            }
                            tell(sender, "已向 §b$count §f名玩家请求加载音乐资源包。§7加载完成后可点播。")
                        }
                        else -> players.forEach { player ->
                            val state = plugin.playback.status(player)
                            tell(sender, "§b${player.name} §f" + when {
                                state == null -> "§7未播放"
                                state.waitingForPack -> "§7等待资源包加载 §b${state.request.id}"
                                state.waitingForSync -> "§7等待同步加入 §b${state.songId}"
                                else -> "§a正在播放 §b${state.songId} §7（§a${state.index + 1}§f/§a${state.total}§7，§b${if (state.repeat == RepeatMode.LOOP) "循环播放" else "播放一次"}§7）"
                            } + (if (state != null && !state.waitingForPack) " §7进度 §f${"%.1f".format(state.elapsedSeconds)} 秒" else "") +
                                (if (state?.broadcast == true) " §7集体播放" else "") +
                                (if (state?.request?.locked == true) " §c个人控制已锁定" else ""))
                        }
                    }
                }
                else -> error("未知指令，使用 /fmp help 查看帮助。")
            }
        } catch (e: IllegalArgumentException) { tell(sender, "§c${e.message ?: "参数无效。"}") }
          catch (e: IllegalStateException) { tell(sender, "§c${e.message ?: "音乐库尚未就绪。"}") }
        return true
    }
    private fun targets(sender: CommandSender, value: String?): List<Player> {
        if (value == null) return listOf(sender as? Player ?: error("控制台必须指定玩家或 all。"))
        if (sender is Player && value.equals(sender.name, true)) return listOf(sender)
        admin(sender)
        if (value.equals("all", true) || value == "*") return Bukkit.getOnlinePlayers().toList()
        return listOf(Bukkit.getPlayerExact(value) ?: error("玩家不在线：$value"))
    }
    private fun admin(sender: CommandSender) { require(sender.hasPermission("fairymediaplayer.admin")) { "你没有管理员权限（fairymediaplayer.admin）。" } }
    private fun library() = plugin.playback.pack?.library ?: error("音乐库尚未就绪，请检查控制台或执行 /fmp reload。")
    private fun tell(sender: CommandSender, text: String) = sender.sendMessage(ui("§f$text"))
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val admin = sender.hasPermission("fairymediaplayer.admin")
        if (!admin && !sender.hasPermission("fairymediaplayer.use")) return emptyList()
        val action = args.firstOrNull()?.lowercase()
        val suggestions = when {
            args.size == 1 -> listOf("menu", "help", "list", "play", "stop", "status", "pack") + if (admin) listOf("forceplay", "reload", "build") else emptyList()
            action in setOf("play", "forceplay") && args.size == 2 -> listOf("song", "playlist")
            action in setOf("play", "forceplay") && args.size == 3 -> {
                val library = plugin.playback.pack?.library ?: return emptyList()
                val type = if (args[1].equals("playlist", true)) MediaType.PLAYLIST else MediaType.SONG
                val ids = if (type == MediaType.SONG) library.songs.keys else library.playlists.keys
                ids.filter { sender !is Player || maySelect(sender, library, MediaRequest(type, it)) }
            }
            action in setOf("play", "forceplay") && args.size == 5 -> listOf("once", "loop")
            action in setOf("play", "forceplay") && args.size == 4 -> listOf("once", "loop") + if (admin) listOf("all") + Bukkit.getOnlinePlayers().map { it.name } else emptyList()
            action in setOf("stop", "status", "pack") && args.size == 2 && admin -> listOf("all") + Bukkit.getOnlinePlayers().map { it.name }
            else -> emptyList()
        }
        return suggestions.filter { it.startsWith(args.lastOrNull().orEmpty(), true) }
    }
}

