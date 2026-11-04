# FairyMediaPlayer

面向 **Paper 1.21.11、Java 21+** 的音乐插件，使用原版客户端资源包播放，无需客户端模组。

支持进服自动播放、玩家菜单点播、指令/API 向指定玩家或当前全体在线玩家播放歌曲/歌单。歌曲本身不保存循环设置，是否重复由本次播放请求决定；歌单依次播放全部歌曲后停止或从头循环。每个玩家独立播放，新请求会替换旧请求。

## 安装

1. 将 `target/FairyMediaPlayer-1.0.jar` 放进服务器 `plugins/`，启动一次生成配置。
2. 默认无需手动安装 FFmpeg。首次有歌曲需要处理时，插件会在后台自动下载 `ffmpeg` 和 `ffprobe` 到自己的 `tools/ffmpeg/` 目录；以后离线复用。首次下载需能访问 GitHub，控制台会显示进度。
3. 将 MP3 放进 `plugins/FairyMediaPlayer/music/`，在 `config.yml` 中登记。
4. 设置 `resource-pack.public-url` 为**玩家能访问的下载地址**。例如 `http://服务器公网IP:8163/{sha1}.zip`，并放行 TCP 8163。如有 NAT，需映射到服务器的 HTTP 端口。默认 `127.0.0.1` 仅适合本机测试。
5. 执行 `/fmp reload`，等待控制台提示资源包就绪。客户端允许服务器资源包，加载成功后即可播放。音量由客户端的“唱片机/音符盒”控制。

`config.yml` 中保存的是 MP3 路径，音频文件放在 music 目录。插件在后台转换为立体声 OGG Vorbis、探测时长、生成 ZIP 和 SHA-1。转换结果按文件内容缓存；相同 MP3 不会重复转换。空歌曲库无需 FFmpeg 即可启动。

## 配置示例

FFmpeg 自动下载默认设置（旧配置没有这些选项时也默认启用）：

```yaml
conversion:
  auto-download: true
  download-timeout-seconds: 1800
  ffmpeg: 'ffmpeg'
  ffprobe: 'ffprobe'
  timeout-seconds: 300
```

支持 Windows x64、Linux x64 和 Linux ARM64。Windows 使用 [Gyan 的版本化构建](https://github.com/GyanD/codexffmpeg/releases/tag/9.0.2)，Linux 使用 [BtbN 构建](https://github.com/BtbN/FFmpeg-Builds/releases)。下载后校验 SHA-256，再解压安装，并保留许可证和来源信息；不会自动升级已安装的工具。Linux 构建还需要系统满足上游运行库要求，Alpine 等环境建议手动提供适配的二进制。

空歌曲库不会下载。网络失败会重试，当前下载中的断线支持续传；本次构建最终失败后可 `/fmp reload` 重新开始。失败或关闭插件不会把未完成的工具标记为可用。两次构建之间不保留未完成下载。

离线或使用自备工具时，将 `auto-download` 设为 `false`，并填写两个可执行文件路径；例如 Windows 使用单引号 `'D:\ffmpeg\bin\ffmpeg.exe'` 和 `'D:\ffmpeg\bin\ffprobe.exe'`。自动下载开启时，显式填写的自定义路径仍优先使用；默认名称或 `auto` 使用插件下载版本。

Windows 下载、解压后的二进制已运行验证；Linux 架构选择与 tar.xz 解压已自动测试，尚未在实际 Linux 服务器上运行验证。

保留默认配置中的 conversion 和 resource-pack 部分，用下面内容**替换** `songs: {}`、`playlists: {}` 和 auto-play 部分，不要重复写顶层键。

```yaml
songs:
  welcome:
    name: '欢迎曲'
    file: 'welcome.mp3'
    selectable: true
    permission: ''
    volume: 1.0
  night:
    name: '夜晚'
    file: 'night.mp3'
    selectable: true
    permission: 'fairymediaplayer.song.night'

playlists:
  lobby:
    name: '大厅歌单'
    songs: [welcome, night]
    selectable: true
    permission: ''
    repeat: loop

auto-play:
  enabled: true
  type: playlist  # song 或 playlist
  id: lobby
  repeat: loop   # once 或 loop
  join-delay-ticks: 20
```

- ID 使用小写字母、数字、下划线或横线；显示名称可以使用中文。
- 歌单至少一首，允许同一歌曲出现多次；歌单可用 repeat 配置默认循环模式。歌曲没有 repeat 属性，旧 songs.<ID>.repeat 会被忽略，可直接删除。
- `selectable: false` 隐藏普通玩家的菜单项并禁止自助指令点播。管理员、API 和自动播放仍可播放。
- 歌单权限独立于歌曲权限：允许播放歌单即允许收听其中全部歌曲，不会再次检查单曲权限。
- `volume` 范围大于 0 且不超过 1，默认 1。
- 自动播放在资源包成功加载后开始。拒绝、失败或超时不启动播放，可用 `/fmp pack` 重试后重新点播。
- 同一玩家在下载时多次点播，最终只播放最后一次请求；停止也会取消等待播放。

## 指令与菜单

### 集体播放与强制播放

`/fmp play ... all`（或 `*`）及 API `playAll()` 自动锁定听众的个人播放控制。管理员向其他玩家指定播放也会锁定；使用 `/fmp forceplay <song|playlist> <ID> [玩家|all] [once|loop]` 可显式强制播放，需要管理员权限。

锁定期间，玩家不能通过菜单或个人指令切歌、停止、修改循环方式或重载资源包；已打开的旧菜单也不能绕过限制。查看状态和翻页仍可使用。锁从等待资源包加载时生效，在歌单切歌、循环时保持；播放自然结束、管理员停止、资源包失败/超时、退出或插件重载后解除。普通个人点播不加锁。进服自动播放从进服延迟期间开始锁定个人控制，等待资源包及播放期间均不能自行切歌、停止或重载资源包；播放一次自然结束后解除，循环进服音乐需要管理员停止。

管理员可用 `/fmp stop <玩家|all>` 结束强制播放。循环播放需要管理员结束后才恢复自由点播。锁限制插件的个人控制，不限制客户端音量设置。集体播放使用独立的服务器时间线。新进和重连玩家优先加入正在进行的集体播放，不另播进服音乐；资源包加载期间时间线继续推进，加载完成后加入最新曲目和分段边界。即使没有在线听众，时间线也继续运行；播放一次结束后不再接收新听众，循环播放持续到管理员停止或资源包重载。`/fmp stop all` 会停止并清除集体时间线，避免新进玩家再次加入。管理员只停止指定玩家时，该玩家退出当前集体播放，其他听众不受影响。

API 新增 `forcePlaySong(player, id, repeat)` 和 `forcePlayPlaylist(player, id, repeat)`。`status(player).getRequest().getLocked()` 可查询锁状态（先检查 status 非 null）。API 是可信的服务器管理接口，原有 `playSong`/`playPlaylist`/`stop` 仍可覆盖或停止强制播放；第三方插件若将 API 接到玩家自助菜单，应先检查锁状态。

| 指令 | 功能 |
| --- | --- |
| `/fmp` 或 `/fmp menu` | 打开分页菜单 |
| `/fmp list` | 列出可点播 ID |
| `/fmp play song welcome` | 为自己播放歌曲一次 |
| `/fmp play song welcome loop` | 本次重复播放该歌曲 |
| `/fmp play playlist lobby all loop` | 为当前全体在线玩家循环歌单 |
| `/fmp play song welcome Steve once` | 为 Steve 播放一次歌曲 |
| `/fmp stop [玩家或all]` | 停止播放及等待中的请求，省略目标为自己 |
| `/fmp status [玩家或all]` | 查看播放状态 |
| `/fmp pack [玩家或all]` | 请求重新加载资源包；当前播放停止 |
| `/fmp reload` 或 `/fmp build` | 重新读取配置、转换、生成并发送资源包 |

普通玩家默认有 `fairymediaplayer.use`。向其他人/全体操作和 reload/build 需要 `fairymediaplayer.admin`，默认 OP 拥有。控制台必须填写播放目标。`all` 可用 `*` 替代。

歌曲菜单左键播放一次，右键仅对本次播放开启重复；歌单菜单左键使用歌单默认模式，右键循环整个歌单。Shift+左键播放一次；底部提供翻页、停止、刷新状态、重载资源包。单曲和歌单共用菜单，每页 28 项，四行七列，边缘与组间留空。界面采用 FairyBridge 风格：青色物品名称、深灰菜单标题，灰色说明与白色普通数值，lore 内的青色集中强调当前状态；使用原版物品图标与两空格缩进，物品名称和说明关闭默认斜体。当前点播项通过发光与文字标签区分，受限操作仅将锁定状态标红，常规操作与翻页使用白灰文字；空音乐库或没有可点播权限时显示说明。底部状态显示歌曲/歌单名称、播放模式、曲目进度、当前曲目时间、播放来源与页码。集体播放的后加入玩家会显示“等待同步加入”。

配置或转换失败时继续使用上一份成功的音乐库。重载成功后停止原有播放并重新发送资源包；在线玩家需重新点播，后续新进玩家按自动播放配置处理。修改 HTTP 启用状态/监听地址/端口需要重启。客户端资源包格式默认 75，对应 1.21.11。

## 资源包外部托管

关闭 `resource-pack.http.enabled`，将 `public-url` 配置成 HTTPS 下载直链（可含 `{sha1}`）。构建后把 `plugins/FairyMediaPlayer/packs/<sha1>.zip` 上传到对应地址，再执行 `/fmp pack all` 并重新点播。必须上传本次生成的 ZIP，客户端会校验 SHA-1。插件不会替你上传到外部服务。

内置 HTTP 仅提供生成的哈希命名 ZIP，支持 GET/HEAD，不提供配置或目录列表。旧 ZIP 保留，避免中断正在下载的客户端；停服后可清理不再使用的旧 ZIP。`cache/` 是可再生成的转换缓存。

## API（Java 示例）

调用方以 `provided`/`compileOnly` 依赖本插件 JAR，并在自己的 `plugin.yml` 添加 `depend: [FairyMediaPlayer]`。所有调用在 Bukkit 主线程执行；异步插件须先调度到主线程。API 作为可信服务不检查玩家点播权限。

```java
import cn.fairymc.fairyMediaPlayer.*;
import org.bukkit.Bukkit;

FairyMediaPlayerApi api = Bukkit.getServicesManager().load(FairyMediaPlayerApi.class);
if (api != null) {
    PlayResult result = api.playSong(player, "welcome", RepeatMode.ONCE);
    api.playPlaylist(player, "lobby", RepeatMode.LOOP);
    api.playAll(MediaType.PLAYLIST, "lobby", RepeatMode.LOOP);
    PlaybackStatus status = api.status(player);
    api.stop(player);
    // api.stopAll();
    // api.songIds(); api.playlistIds();
}
```

`repeat` 是本次播放的设置：歌曲传 `null` 默认播放一次；歌单传 `null` 使用歌单配置的默认值。传 `RepeatMode.LOOP` 可对本次播放启用重复，不修改歌曲或歌单配置。`auto-play.repeat` 同样属于进服播放请求的设置。`PlayResult` 为 PLAYING、WAITING_FOR_PACK、NOT_READY、PLAYER_OFFLINE 或 UNKNOWN_MEDIA。插件启动时后台构建尚未完成会返回 NOT_READY；WAITING_FOR_PACK 表示已排队，非已经出声。`status()` 无播放时返回 null，`index` 从 0 开始。

个人播放仍使用完整音频。集体播放使用构建时额外生成的约 2 秒立体声 OGG 分段，并按统一的单调时钟时间线播放；资源包加载完成的后加入玩家从下一个分段边界加入，不从歌曲开头重新播放，通常等待不超过约 2 秒。下载跨越切歌或循环时，按加载完成时的最新位置加入。

分段文件与完整音频一起打包并缓存，资源包大小和首次转换时间会增加，存储量随歌曲长度线性增长。服务器按 tick 发送分段声音，原版客户端没有时间偏移接口或播放回执，因此同步以服务器分段边界为准，不能保证客户端采样级同步；网络延迟、客户端加载及分段切换可能产生可听接缝，需实际客户端试听。服务器长时间卡顿后跳过过期片段，在下一边界恢复，避免播放旧进度。没有个人暂停或任意定位功能。

## 构建与验证

建议将 `JAVA_HOME` 指向 JDK 21 后构建，本项目的自动测试使用 JDK 21 验证。

```text
mvn clean package
```

构建目标字节码为 Java 21，保留项目原有 Kotlin 2.5.0-Beta1 和 Paper 1.21.11 依赖。交付 JAR 内含 Kotlin 运行库。

测试覆盖歌曲/歌单循环、重复曲目、配置校验、资源包等待/停止/重载隔离、ZIP 与 HTTP 服务。真实 MP3 转换集成测试需要环境变量 `FMP_FFMPEG` 和 `FMP_FFPROBE` 指向可执行文件；未配置时跳过该项。

仍需在实际客户端验证下载提示、可听音量、菜单交互和公网下载。测试不替代真人客户端试听。



