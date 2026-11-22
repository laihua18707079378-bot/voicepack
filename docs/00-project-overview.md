# 语音盒（voicebox）项目总览

> 更新时间：2026-11-22　当前插件版本：**1.9.6**
> 本文档面向接手开发的人或 AI，说明项目构成、功能、接口与发布流程。

## 1. 项目是什么

一个 QFun（QQ/TIM 模块）插件「语音盒」，配合自建静态站点分发：

- 站点：<https://mcer.dpdns.org>
- 仓库：<https://github.com/laihua18707079378-bot/voicepack>
- 插件 ID：`voicebox`　名称：语音盒　作者：`nl`
- 插件类型：BeanShell 脚本（`main.java`），由 QFun 宿主注入 API 运行

站点提供云端语音包（192 个包 / 9911 个音频文件）和插件安装包；
插件负责浏览云端语音包、发送语音、在线音乐搜索与播放、账号登录、自动更新。

## 2. 仓库结构（voicepack）

```
index.html           站点首页（语音包列表 + 下载入口）
portable.html        备用页面
data.json            语音包清单（站点运行时数据）
version.json         {"packs":192,"files":9911,"songs":17,"updated":"2026-11-20"}
audio/<包名>/*.mp3    云端语音包文件（约 9911 个）
music/*.mp3          早期 CC 曲库（音乐盒已改为在线网易云，保留未用）
plugin/voicebox.zip  语音盒插件安装包（1.9.6）
plugin/latest.json   语音盒最新版本 { "version": "1.9.6", "zip": "..." }
plugin/musicbox.zip  独立音乐盒插件安装包（1.0.0）
plugin/musicbox_latest.json  音乐盒最新版本 { "version": "1.0.0", "zip": "..." }
source/voicebox/     语音盒插件源码（main.java / info.prop）
source/musicbox/     独立音乐盒插件源码
source/apk-patch/    APK 侧关键 smali（商店注入、内置插件安装）
docs/                项目文档（本目录，含 03-musicbox.md 音乐盒专题）
```

## 3. 插件功能

### 首页
两列卡片网格：「语音包」「音乐」「音乐盒」「会员账号」；
底部显示当前版本，点「插件 vX · 点此检查更新」手动检查更新。

### 语音包（云端）
- 读取 `data.json` 分类清单，本地缓存 `cloud_packs.json` 秒开
- 用站点 `version.json` 的 `packs/files/songs` 数字与本地缓存比对，一致则不重新拉取
- 分类列表两列卡片，显示「N 条语音」
- 语音按需下载到 `voice/<包名>/<文件名>`，播放/发送复用缓存
- 云端失败自动回退本地 `voice/` 目录

### 音乐（本地）
- 扫描/导入手机音频，支持多选批量发送
- 播放使用本地预览播放器，发送走宿主 `sendPtt`

### 音乐盒（三平台在线）
- 平台切换：网易云音乐 / QQ音乐 / 酷狗音乐（`gPlatform` = `163` / `qq` / `kg`）
- 搜索：网易云 `api/search/get/web`、QQ `search_for_qq_cp`、酷狗 `song_search_v2`，最多 30 首
- 已登录时请求带对应平台 Cookie；QQ/酷狗未登录通常拿不到播放地址
- 点歌 → 解析播放地址 → 下载到 `plugin/musicbox/<平台>_<id>.mp3` → 本地 MediaPlayer 播放
- 同一首歌点击是暂停/继续；不同歌曲按 **歌曲 id** 判断并强制切歌
- 搜索刷新列表后按正在播放歌曲 id 重新定位高亮与播放条
- 「音乐缓存 X MB · 点此一键清除」删除全部已下载 mp3（1.9.4 新增）

### 会员登录管理（QQ音乐 / 网易云 / 酷狗）
- 菜单「会员登录管理」与首页「会员账号」进入同一界面
- 三个平台卡片：QQ音乐、网易云音乐、酷狗音乐；状态徽章 FREE / 已登录
- 一键登录打开全屏 WebView 官方登录页，登录完成自动抓取 Cookie 保存
- 保留「我已登录」手动保存按钮
- Cookie 存储：优先宿主 `putString/getString`，配置名 `music_login`，键 `平台_QQ号`；
  失败时回退 `plugin/login_<平台>_<uin>.txt`
- 打开界面**不做联网校验、不自动清除**，只有用户点「退出登录」才删除
- 仅接受真实登录 Cookie：网易云含 `MUSIC_U=`，QQ 音乐含 `qqmusic_key=`/`qm_keyst=`，
  酷狗含 `userid=` 或 `KuGoo=`

## 4. 关键接口

网易云（请求头：桌面 UA、`Referer: https://music.163.com/`、Cookie 含 `appver=2.0.2; <用户cookie>`）：

- 搜索：`GET https://music.163.com/api/search/get/web?s=<kw>&type=1&limit=30&offset=0`
- 账号播放：`GET https://music.163.com/api/song/enhance/player/url/v1?ids=[id]&level=exhigh&encodeType=mp3&csrf_token=<...>`
- 兜底播放：`GET https://music.163.com/song/media/outer/url?id=<id>.mp3`
- 账号校验（已取消自动调用）：`GET https://music.163.com/api/nuser/account/get`

QQ音乐（请求头：桌面 UA、`Referer: https://y.qq.com/`、用户 Cookie）：

- 搜索：`GET https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?w=<kw>&format=json&n=30&p=1`
- 播放：`GET https://u.y.qq.com/cgi-bin/musicu.fcg`（vkey / purl，需登录 Cookie）

酷狗（请求头：`Referer: https://www.kugou.com/`、用户 Cookie）：

- 搜索：`GET https://songsearch.kugou.com/song_search_v2?keyword=<kw>&page=1&pagesize=30&platform=WebFilter`
- 播放：`GET https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=<hash>`（需补 `kg_mid`/`kg_dfid`）
- 兜底：`GET https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=<hash>`
- 未登录直接搜/播会返回 `err_code 30020`

站点：

- 清单：`https://mcer.dpdns.org/data.json`
- 版本：`https://mcer.dpdns.org/version.json`
- 插件版本：`https://mcer.dpdns.org/plugin/latest.json`
- 插件包：`https://mcer.dpdns.org/plugin/voicebox.zip`

## 5. 自更新机制（插件内）

1. 打开插件菜单时请求 `plugin/latest.json?t=<时间戳>`
2. 用 `cmpVersion` 比较本地 `info.prop` 的 `versionCode` 与远端 `version`
3. 远端更高 → 下载到 `getCacheDir()/voicebox_<v>.zip`
4. 先反射宿主 `PluginManager.installPluginFromZip`，失败则自行用 ZipInputStream 解压 `voicebox/` 覆盖插件目录
5. 结果写入 `plugin/update.log`，并用 toast 提示

APK 侧：

- `LocalPluginInjector.smali`：向插件商店列表注入 voicebox 条目（id/name/desc/author/下载地址），
  版本号动态读取：**先请求 latest.json → 再读本地 info.prop → 最后回退 1.9.4**，保证商店版本与服务器同步
- `BuiltinPluginInstaller.smali`：仅当 `plugin/voicebox/main.java` 不存在或小于 1000 字节时，
  解压 APK 内置 `assets/builtin/voicebox.zip`，不会覆盖已安装/已更新的插件
- 聊天设置里的下载开关：打开后自动下载并启用插件

## 6. 构建与发布流程

```bash
# 1) 打包插件（源码目录 .qedge/src/assets/voicebox）
cd /workspace/.qedge/src/assets && rm -f /workspace/voicebox.zip && zip -qr /workspace/voicebox.zip voicebox

# 2) 同步到 APK 内置包与站点目录
cp /workspace/voicebox.zip /workspace/.qfun_fix/src/assets/builtin/voicebox.zip
cp /workspace/voicebox.zip /workspace/site/plugin/voicebox.zip

# 3) 改三处版本号
#    voicebox/info.prop  versionCode
#    site/plugin/latest.json  version
#    LocalPluginInjector.smali 中的回退版本字符串

# 4) 构建并签名 APK
cd /workspace && java -jar apktool.jar b .qfun_fix/src -o .qfun_fix/out.apk
java -jar signer.jar -a .qfun_fix/out.apk -o .qfun_fix/signed
# 产物：.qfun_fix/signed/out-aligned-debugSigned.apk（debug 证书，可覆盖安装）

# 5) 推送到仓库（plugin/*.zip 被 .gitignore 忽略，需要 -f）
cd /workspace/site && git add -f plugin/voicebox.zip plugin/latest.json
git commit -m "voicebox x.y.z: ..." && git push origin main

# 6) 部署约 1-3 分钟后校验
curl -s "https://mcer.dpdns.org/plugin/latest.json?x=$RANDOM"
```

## 7. 版本轨迹

| 版本 | 内容 |
| --- | --- |
| 1.4 | 音乐先下载到本地再播放，修复流式播放无声 |
| 1.5 | 商店置顶、作者 nl、聊天开关自动下载并启动 |
| 1.6 | 酷狗改为网易云搜索/播放、Cookie 登录（粘贴式） |
| 1.7 | 自更新双保险（宿主安装 + 自行解压）、update.log、手动检查更新 |
| 1.8 | 移植参考插件「会员登录管理」（QQ音乐/网易云 WebView 登录） |
| 1.9 | 登录不再自动校验/清除，修复反复掉登录 |
| 1.9.1 | 登录后音乐搜索播放走账号接口（高音质） |
| 1.9.2 | 补 `import android.widget.EditText`，修复音乐搜索框不显示 |
| 1.9.3 | 切歌按歌曲 id 判断，修复新歌不顶掉旧歌 |
| 1.9.4 | 音乐盒新增一键清除音乐缓存 |
| 1.9.5 | 音乐盒支持网易云 / QQ音乐切换搜索 |
| 1.9.6 | 新增酷狗音乐：Cookie 登录、搜索、播放 |

## 8. 已知问题 / 注意事项

- 三个平台都是非公开接口，随时可能变更；QQ音乐/酷狗的播放地址依赖登录 Cookie
- 网易云部分 VIP/版权歌曲即使登录也可能拿不到地址（接口返回空），会提示「暂无版权或需要会员」
- 登录 Cookie 按 QQ 号分别保存；换账号登录后需要重新登录
- 插件商店条目版本依赖 APK 内的 `readVersion()`；旧 APK 不会动态同步
- 站点为静态托管 + 未知路径回退 index.html，部署有 1-3 分钟延迟
- `data.json` 与 `*.zip` 被 `.gitignore` 忽略（推送插件包需 `git add -f`）
- APK 使用 debug 证书签名；内置包只在插件缺失时解压

## 9. 独立语音盒 App（com.nl.voicebox）

除 QFun 插件外，另有一个同源的原生 App，功能是「从站点下载语音 mp3 + 悬浮窗选择播放」：

- 安装包：`语音盒_v1.3.apk`（早期产物，未入库）
- 语音库：读取站点首页内嵌数据，缓存 `index_cache.html`，二次打开秒开
- 播放：点任意语音 → 下载 mp3 → 本地播放；不是自动连播
- 悬浮窗：前台服务 + 可拖动面板，内含「语音包 → 语音」两级列表，可最小化成一个可拖动小球
- 与插件相互独立：不共享登录、不共享缓存
