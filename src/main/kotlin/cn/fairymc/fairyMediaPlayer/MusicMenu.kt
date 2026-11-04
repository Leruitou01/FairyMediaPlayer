package cn.fairymc.fairyMediaPlayer

import org.bukkit.inventory.ItemFlag
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

internal fun maySelect(player: Player, library: Library, request: MediaRequest): Boolean {
    if (player.hasPermission("fairymediaplayer.admin")) return true
    if (!player.hasPermission("fairymediaplayer.use")) return false
    val (selectable, permission) = when (request.type) {
        MediaType.SONG -> library.songs[request.id]?.let { it.selectable to it.permission } ?: return false
        MediaType.PLAYLIST -> library.playlists[request.id]?.let { it.selectable to it.permission } ?: return false
    }
    return selectable && (permission.isBlank() || player.hasPermission(permission))
}

class MusicMenu(private val plugin: Main) : Listener {
    private class Holder(val page: Int, val pack: BuiltPack, val entries: Map<Int, MediaRequest>) : InventoryHolder {
        lateinit var menu: Inventory
        override fun getInventory(): Inventory = menu
    }
    private val contentSlots = (1..4).flatMap { row -> (1..7).map { column -> row * 9 + column } }

    fun open(player: Player, requestedPage: Int = 0) {
        val pack = plugin.playback.pack ?: run { player.sendMessage(ui("§c音乐库尚未就绪，请联系管理员。")); return }
        if (!player.hasPermission("fairymediaplayer.use") && !player.hasPermission("fairymediaplayer.admin")) {
            player.sendMessage(ui("§c你没有使用音乐菜单的权限。")); return
        }
        val library = pack.library
        val entries = (library.songs.keys.map { MediaRequest(MediaType.SONG, it) } +
            library.playlists.keys.map { MediaRequest(MediaType.PLAYLIST, it) }).filter { maySelect(player, library, it) }
        val maxPage = ((entries.size - 1).coerceAtLeast(0)) / contentSlots.size
        val page = requestedPage.coerceIn(0, maxPage)
        val visible = entries.drop(page * contentSlots.size).take(contentSlots.size)
            .mapIndexed { i, entry -> contentSlots[i] to entry }.toMap()
        val holder = Holder(page, pack, visible)
        holder.menu = Bukkit.createInventory(holder, 54, ui("§8音乐播放器"))
        val status = plugin.playback.status(player)
        val locked = status?.request?.locked == true
        visible.forEach { (slot, request) ->
            val isSong = request.type == MediaType.SONG
            val name = if (isSong) library.songs.getValue(request.id).name else library.playlists.getValue(request.id).name
            val resolved = library.resolve(request)
            val selected = status?.request?.let { it.type == request.type && it.id == request.id } == true
            val detail = if (isSong) "§7时长 §f%.1f §7秒".format(resolved.first.single().duration)
                else "§7歌曲 §f${resolved.first.size} §7首"
            val actions = if (locked) listOf("§7个人控制 §c已锁定§7", "§7请等待播放结束或联系管理员") else listOf(
                if (isSong) "§f左键播放一次" else "§f左键按歌单设置播放",
                if (isSong) "§f右键重复播放" else "§f右键循环歌单", "§7Shift + 左键播放一次")
            holder.menu.setItem(slot, item(if (isSong) Material.MUSIC_DISC_CAT else Material.JUKEBOX,
                "§b$name",
                listOf("§8${if (isSong) "歌曲" else "歌单"}", " ", detail,
                    "§7编号 §f${request.id}§7", "§7默认 §f${if (resolved.second == RepeatMode.LOOP) "循环播放" else "播放一次"}§7") +
                    (if (selected) listOf(when {
                        status.waitingForPack -> "§7当前 §b等待资源包加载§7"
                        status.waitingForSync -> "§7当前 §b等待同步加入§7"
                        else -> "§7当前 §b正在播放§7"
                    }) else emptyList()) +
                    listOf(" ") + actions + listOf(" "), selected))
        }
        if (entries.isEmpty()) holder.menu.setItem(22, item(Material.PAPER, "§b暂无可点播音乐", listOf(
            " ", "§7音乐库为空或你没有点播权限", "§7请联系管理员添加歌曲或授权", " ")))
        if (page > 0) holder.menu.setItem(45, item(Material.ARROW, "§b上一页", listOf(" ", "§f§n点击查看", " ")))
        holder.menu.setItem(48, item(Material.BARRIER, "§b停止播放", listOf(" ",
            if (locked) "§7个人控制 §c已锁定§7" else "§7停止当前音乐及等待中的点播",
            if (locked) "§7请等待播放结束或联系管理员" else "§f§n点击停止", " ")))
        val currentName = status?.songId?.let { library.songs[it]?.name ?: it }
        holder.menu.setItem(49, item(Material.NOTE_BLOCK, "§b播放状态", listOf(
            " ", when {
                status == null -> "§7当前 §f未播放§7"
                status.waitingForPack -> "§7当前 §b等待资源包加载§7"
                status.waitingForSync -> "§7当前 §b等待同步加入§7"
                else -> "§7当前 §b正在播放§7"
            }) + (if (status == null) emptyList() else listOf(
                "§7${if (status.request.type == MediaType.SONG) "歌曲" else "歌单"} §f${if (status.request.type == MediaType.SONG) library.songs[status.request.id]?.name ?: status.request.id else library.playlists[status.request.id]?.name ?: status.request.id}§7",
                "§7曲目 §f${currentName ?: "等待加载"}§7", "§7进度 §f${status.index + 1}§7/§f${status.total}§7",
                "§7时间 §f${"%.1f".format(status.elapsedSeconds)} §7秒",
                "§7来源 §f${if (status.broadcast) "集体播放" else if (status.request.locked) "进服或强制播放" else "个人点播"}§7",
                "§7模式 §f${if (status.repeat == RepeatMode.LOOP) "循环播放" else "播放一次"}§7")) + listOf(
            if (locked) "§7个人控制 §c已锁定§7" else "§7你可以自由点播", "§7页码 §f${page + 1}§7/§f${maxPage + 1}§7",
            " ", "§f§n点击刷新", " ")))
        holder.menu.setItem(50, item(Material.BOOK, "§b重新加载资源包", listOf(" ", "§7重新加载收听音乐所需的资源包",
            "§7当前音乐会停止，需要重新点播", " ",
            if (locked) "§7个人控制 §c已锁定§7" else "§f§n点击加载", " ")))
        if (page < maxPage) holder.menu.setItem(53, item(Material.ARROW, "§b下一页", listOf(" ", "§f§n点击查看", " ")))
        player.openInventory(holder.menu)
    }
    private fun item(material: Material, name: String, lore: List<String> = emptyList(), selected: Boolean = false): ItemStack = ItemStack(material).apply {
        itemMeta = itemMeta.apply {
            displayName(ui("  $name"))
            lore(lore.map { ui(if (it.isBlank()) " " else "  $it") })
            addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
            if (selected) setEnchantmentGlintOverride(true)
        }
    }
    @EventHandler fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlot !in 0..53) return
        val slot = event.rawSlot
        val right = event.isRightClick
        val shift = event.isShiftClick
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!player.isOnline || player.openInventory.topInventory.holder !== holder) return@Runnable
            if (plugin.playback.pack !== holder.pack) { open(player); return@Runnable }
            if (!player.hasPermission("fairymediaplayer.use") && !player.hasPermission("fairymediaplayer.admin")) {
                player.closeInventory(); return@Runnable
            }
            val entry = holder.entries[slot]
            if ((entry != null || slot == 48 || slot == 50) && plugin.playback.isLocked(player)) {
                player.sendMessage(ui("§c当前为进服、集体或强制播放，个人控制已锁定。§7请等待播放结束或联系管理员。"))
                return@Runnable
            }
            if (entry != null) {
                if (!maySelect(player, holder.pack.library, entry)) {
                    player.sendMessage(ui("§c你无法点播这首歌曲或歌单。")); return@Runnable
                }
                val request = entry.copy(repeat = if (right) RepeatMode.LOOP else if (shift) RepeatMode.ONCE else null)
                player.sendMessage(ui(resultMessage(plugin.playback.playPersonal(player, request))))
                player.closeInventory()
            } else when (slot) {
                45 -> open(player, holder.page - 1)
                53 -> open(player, holder.page + 1)
                48 -> { plugin.playback.stopPersonal(player); open(player, holder.page) }
                49 -> open(player, holder.page)
                50 -> { plugin.playback.sendPackPersonal(player); player.closeInventory() }
            }
        })
    }
    @EventHandler fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }
}

internal fun resultMessage(result: PlayResult): String = when (result) {
    PlayResult.PLAYING -> "§f已开始播放你选择的音乐。"
    PlayResult.WAITING_FOR_PACK -> "§f正在等待§b音乐资源包§f加载，完成后开始播放。"
    PlayResult.NOT_READY -> "§c音乐库或资源包尚未就绪，请联系管理员。"
    PlayResult.PLAYER_OFFLINE -> "§c玩家不在线。"
    PlayResult.UNKNOWN_MEDIA -> "§c歌曲或歌单不存在。§7使用 §b/fmp list §7查看可用编号。"
}


