# 语音盒（voicebox）项目总览

> 更新时间：2026-11-21　当前插件版本：**1.9.4**
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
plugin/voicebox.zip  当前插件安装包（1.9.4）
plugin/latest.json   插件最新版本 { "version": "1.9.4", "zip": "..." }
source/voicebox/     插件源码（main.java / info.prop）
source/apk-patch/    APK 侧关键 smali（商店注入、内置插件安装）
docs/                项目文档（本目录）
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

### 音乐盒（网易云在线）
- 搜索框（`EditText`）请求 `music.163.com/api/search/get/web`，最多 30 首
- 已登录时请求带 Cookie，播放走账号接口（高音质）；未登录回退 `song/media/outer/url`
- 点歌 → 下载到 `plugin/musicbox/*.mp3` → 本地 MediaPlayer 播放（纯流式播放会无声，必须落盘）
- 同一首歌点击是暂停/继续；不同歌曲按 **歌曲 id** 判断并强制切歌
- 搜索刷新列表后按正在播放歌曲 id 重新定位高亮与播放条
- 「音乐缓存 X MB · 点此一键清除」删除全部已下载 mp3（1.9.4 新增）

### 会员登录管理（QQ音乐 / 网易云）
- 菜单「会员登录管理」与首页「会员账号」进入同一界面
- 两个平台卡片：QQ音乐、网易云音乐；状态徽章 FREE / 已登录
- 一键登录打开全屏 WebView 官方登录页，登录完成自动抓取 Cookie 保存
- 保留「我已登录」手动保存按钮
- Cookie 存储：优先宿主 `putString/getString`，配置名 `music_login`，键 `平台_QQ号`；
  失败时回退 `plugin/login_<平台>_<uin>.txt`
- 打开界面**不做联网校验、不自动清除**，只有用户点「退出登录」才删除
- 仅接受真实登录 Cookie：网易云必须含 `MUSIC_U=`，QQ 音乐必须含 `qqmusic_key=` 或 `qm_keyst=`

## 4. 关键接口

网易云（请求头：桌面 UA、`Referer: https://music.163.com/`、Cookie 含 `appver=2.0.2; <用户cookie>`）：

- 搜索：`GET https://music.163.com/api/search/get/web?s=<kw>&type=1&limit=30&offset=0`
- 账号播放：`GET https://music.163.com/api/song/enhance/player/url/v1?ids=[id]&level=exhigh&encodeType=mp3&csrf_token=<...>`
- 兜底播放：`GET https://music.163.com/song/media/outer/url?id=<id>.mp3`
- 账号校验（已取消自动调用）：`GET https://music.163.com/api/nuser/account/get`

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

## 8. 已知问题 / 注意事项

- 音乐盒搜索目前只接网易云；QQ 音乐仅用于登录，未接搜索
- 网易云部分 VIP/版权歌曲即使登录也可能拿不到地址（接口返回空），会提示「暂无版权或需要会员」
- 登录 Cookie 按 QQ 号分别保存；换账号登录后需要重新登录
- 插件商店条目版本依赖 APK 内的 `readVersion()`；旧 APK 不会动态同步
- 站点为静态托管 + 未知路径回退 index.html，部署有 1-3 分钟延迟
- `data.json` 与 `*.zip` 被 `.gitignore` 忽略（推送插件包需 `git add -f`）
- APK 使用 debug 证书签名；内置包只在插件缺失时解压
