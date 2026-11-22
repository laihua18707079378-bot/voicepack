# 音乐盒模块（三平台搜索 / 登录 / 播放）

> 对应插件版本：**1.9.6**（语音盒内置）／**1.0.0**（独立音乐盒插件）
> 代码位置：[source/voicebox/main.java](../source/voicebox/main.java)
> 网页下载：<https://mcer.dpdns.org/plugin/musicbox.zip>（独立音乐盒插件）
> 语音盒内置版下载：<https://mcer.dpdns.org/plugin/voicebox.zip>

## 1. 功能范围

音乐盒是语音盒插件里的在线音乐模块，入口在首页「音乐盒」卡片，具备：

- 三个平台的搜索切换：**网易云音乐 / QQ音乐 / 酷狗音乐**
- 三个平台各自独立的账号登录（WebView 登录，Cookie 自动保存）
- 搜索结果列表：歌名 + 歌手 + 时长
- 点一首歌：先下载 mp3 到本地缓存，再用 MediaPlayer 播放（解决流式播放无声）
- 播放缓存统计与一键清除
- 播放条：当前歌名、进度、播放/暂停、上一首/下一首（按当前结果列表）

## 2. 关键状态（脚本全局变量）

| 变量 | 作用 |
| --- | --- |
| `gPlatform` | 当前平台：`"163"` / `"qq"` / `"kg"` |
| `gSongs` | 当前搜索结果，每项 `String[]{标题, 歌手, id, 已解析URL或空, 时长}` |
| `gMIndex` | 当前播放项下标（仅用于展示） |
| `gMPlayingId` | 当前播放歌曲 id（切歌判断以它为准） |
| `gMPlayer` / `gMPrepared` | MediaPlayer 实例与准备状态 |
| `gPlayerBar` / `gMNow` / `gMPlayBtn` / `gMProg` | 播放条与控件 |
| `gMTick` / `gMTickRun` | 进度刷新 Handler |
| `gNeQuery` / `gNeState` / `gNeSearching` | 搜索关键词、状态文案、是否搜索中 |
| `gMRowIcons` / `gMRowNames` | 列表行图标与歌名，用于刷新高亮 |

## 3. 页面与交互

```
renderMusicBoxPage()
├─ 返回 + 「音乐盒」标题 + 副标题（显示登录状态/当前平台）
├─ 平台切换行：网易云音乐 | QQ音乐 | 酷狗音乐
├─ 登录状态行：未登录 →「登录账号」；已登录 → 当前平台 + 退出
├─ 搜索行：EditText + 「搜索」按钮
├─ 缓存行：「音乐缓存 X MB · 点此一键清除」
└─ 结果列表 / 空状态 / 搜索中提示
```

- 切换平台会清空上一平台的结果与搜索词，并刷新登录状态与提示文案
- 点结果行：`playSongAt(index)`
- 点播放条播放键：同一首歌 → 暂停/继续；不同 id → 立即切歌
- 搜索完成后若正在播放的歌仍在结果里，按 **歌曲 id** 重新定位并保持高亮

## 4. 平台配置

| 平台 | key | 名称 | 登录 Cookie 判定 | 搜索接口 | 播放接口 |
| --- | --- | --- | --- | --- | --- |
| 网易云 | `163` | 网易云音乐 | 含 `MUSIC_U=` | `music.163.com/api/search/get/web` | `api/song/enhance/player/url/v1` → `song/media/outer/url` |
| QQ音乐 | `qq` | QQ音乐 | 含 `qqmusic_key=` 或 `qm_keyst=` | `c.y.qq.com/soso/fcgi-bin/search_for_qq_cp` | `u.y.qq.com/cgi-bin/musicu.fcg`（vkey） |
| 酷狗 | `kg` | 酷狗音乐 | 含 `userid=` 或 `KuGoo=` | `songsearch.kugou.com/song_search_v2` | `wwwapi.kugou.com/yy/index.php?r=play/getdata` → `m.kugou.com/app/i/getSongInfo.php` |

公共请求头：

- 桌面 UA（网易云/QQ）或移动 UA（下载）
- `Referer`：网易云 `https://music.163.com/`；QQ `https://y.qq.com/`；酷狗 `https://www.kugou.com/`
- 网易云额外 Cookie：`appver=2.0.2`

## 5. 登录实现

入口：

- 菜单「会员登录管理」→ `showLoginDialog()`
- 首页「会员账号」→ `showNeLogin()`（重新指向同一登录界面）
- 音乐盒登录状态行 → 同一界面

流程：

1. `showLoginUI()` 渲染三个平台卡片与状态徽章
2. 点「一键登录」→ `startWebLogin(platform)`：全屏 WebView 打开官方站点
   - 网易云：`https://music.163.com`
   - QQ音乐：`https://y.qq.com`
   - 酷狗：`https://www.kugou.com`
3. `onPageFinished` 在非登录/授权页检查 Cookie，命中平台特征即 `saveLogin(platform, uin, cookie)`
4. 同时保留「我已登录」按钮，手动读取当前页 Cookie 并保存
5. 「退出登录 / 重新登录」：`clearLogin()` 后重新打开登录页

存储：

- 优先宿主 `putString/getString`，配置名 `music_login`，键 `平台_QQ号`
- 宿主不可用时回退文件：`plugin/login_<平台>_<uin>.txt`
- **不做自动联网校验、不自动清除**，避免「登录 → 校验失败 → 清除」死循环

## 6. 搜索与解析

- 搜索失败/无结果都有明确状态文案
- 标题会去掉接口返回的 `<em>` 等高亮标签
- 时长统一格式化为 `m:ss`（`fmtMs` / `fmtSec`）
- 搜索在 IO 线程执行，结果回主线程刷新 UI

## 7. 播放链路

```
点击歌曲
→ 命中缓存 musicbox/<平台>_<id>.mp3？ 是 → playLocalFile()
→ 否 → 解析播放地址（带登录 Cookie）
→ httpDownloadEx(url, cache, cookie) 下载（跟随 302）
→ 文件 < 50KB 视为失败（无版权/需会员/HTML 错误页）
→ playLocalFile() 本地播放
```

- 缓存目录：`pluginPath + "/musicbox/"`
- 文件名：`<平台>_<歌曲id>.mp3`，三平台互不覆盖
- 切歌前先 `stopMusic()` 释放旧播放器，再加载新文件
- 播放进度由 `gMTick` 定时刷新到 `gMProg`
- 下载/播放失败 toast：「暂无版权或需要会员」「未登录」「取播放地址失败」

## 8. 缓存清理

- `musicCacheSize()` 统计 `musicbox/` 下 mp3 总大小
- `clearMusicCache()` 停止播放 → 删除目录下所有文件 → 刷新页面
- 只删音乐缓存，不影响登录、语音包、插件配置

## 9. 代码索引（main.java）

| 功能 | 函数 |
| --- | --- |
| 页面 | `renderMusicBoxPage()` |
| 平台切换 | `addPlatformSwitch()` / `setPlatform()` |
| 搜索 | `startNeSearch()` / `startQqSearch()` / `startKugouSearch()` |
| 播放地址 | `nePlayUrl()` / `qqPlayUrl()` / `kgPlayUrl()` |
| 播放 | `playSongAt()` / `playLocalFile()` / `stopMusic()` |
| 缓存 | `neCacheFile()` / `musicCacheSize()` / `clearMusicCache()` |
| 登录 UI | `showLoginUI()` / `showNeLogin()` / `startWebLogin()` |
| 登录存储 | `saveLogin()` / `getLogin()` / `clearLogin()` / `hasLogin()` |
| 网络 | `httpGetNe()` / `httpGetWithReferer()` / `httpDownloadEx()` |

## 10. 已知限制

- 网易云部分 VIP/独家歌曲即使登录也可能返回空地址
- QQ音乐与酷狗的播放地址依赖登录 Cookie，未登录通常取不到
- 三家接口均为非公开接口，可能随时变更；变更后先检查搜索/播放的 URL 与字段名
- 音乐盒依赖宿主 WebView 组件；若宿主移除 WebView，登录需改为外部浏览器 + 粘贴 Cookie

## 11. 新增第四个平台的步骤

1. 在平台配置与切换行里加 key/名称
2. 实现 `startXxxSearch(keyword)`，把结果按 `{标题, 歌手, id, "", 时长}` 写入 `gSongs`
3. 实现 `xxxPlayUrl(id)`，返回可直接下载的 mp3 地址
4. 登录状态判定加 Cookie 特征；`startWebLogin()` 加登录站点
5. 下载时补对应 `Referer`
6. 缓存文件名用平台前缀，避免串歌
