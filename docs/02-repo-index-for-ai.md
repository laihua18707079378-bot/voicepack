# 仓库清单 / AI 交接页

> 这是给其他 AI 或新接手者看的单文件入口。看完这一页即可知道：
> 仓库在哪、有什么文件、项目怎么跑、版本怎么发、坑在哪。

## 1. 仓库列表

| 用途 | 仓库 | 分支 | 说明 |
| --- | --- | --- | --- |
| 站点 + 插件分发 + 源码 + 文档 | <https://github.com/laihua18707079378-bot/voicepack> | `main` | 唯一主仓库 |
| 站点访问地址 | <https://mcer.dpdns.org> | — | 静态托管，未知路径回退 `index.html` |

- 克隆：`git clone https://github.com/laihua18707079378-bot/voicepack.git`
- 注意：`.gitignore` 忽略 `data.json` 与 `*.zip`；更新插件包需 `git add -f plugin/voicebox.zip`
- 本地克隆路径（开发机）：`/workspace/site`
- 插件源码在仓库内：`source/voicebox/`
- APK 侧补丁源码在仓库内：`source/apk-patch/`

## 2. 关键地址（机器可读）

```json
{
  "site": "https://mcer.dpdns.org",
  "repo": "https://github.com/laihua18707079378-bot/voicepack",
  "plugin": {
    "id": "voicebox",
    "name": "语音盒",
    "author": "nl",
    "version": "1.9.4",
    "zip": "https://mcer.dpdns.org/plugin/voicebox.zip",
    "latest": "https://mcer.dpdns.org/plugin/latest.json"
  },
  "data": {
    "packs_manifest": "https://mcer.dpdns.org/data.json",
    "counts": "https://mcer.dpdns.org/version.json",
    "packs": 192,
    "files": 9911,
    "songs": 17
  }
}
```

## 3. 文件作用表

| 路径 | 作用 |
| --- | --- |
| `index.html` | 站点首页：语音包浏览、搜索、插件下载入口（置顶） |
| `portable.html` | 备用页面 |
| `data.json` | 语音包清单（分类 → 文件列表），不入库 |
| `version.json` | 数字版本：`packs/files/songs/updated`，客户端缓存比对用 |
| `audio/<包名>/*.mp3` | 云端语音包文件，约 9911 个 |
| `music/*.mp3` + `music/music.json` | 早期 CC 曲库，音乐盒已改用网易云，保留未用 |
| `plugin/voicebox.zip` | 当前插件安装包（1.9.4，需 `git add -f`） |
| `plugin/latest.json` | `{"version":"1.9.4","zip":"..."}` 自更新入口 |
| `source/voicebox/main.java` | 插件主脚本（BeanShell，全部功能） |
| `source/voicebox/info.prop` | 插件元信息：id/名称/versionCode/author/type |
| `source/apk-patch/LocalPluginInjector.smali` | 商店注入 voicebox 条目 + 动态版本读取 |
| `source/apk-patch/BuiltinPluginInstaller.smali` | 首次安装时解压 APK 内置插件、自动启用 |
| `docs/00-project-overview.md` | 项目总览：功能/接口/构建/版本轨迹 |
| `docs/01-conversation-devlog.md` | 全部需求与修复的时间线 |
| `docs/02-repo-index-for-ai.md` | 本文件 |

## 4. 插件代码入口（source/voicebox/main.java）

- 菜单注册：`addItem("语音盒", "showVoiceMenu")`、`addItem("会员登录管理", "showLoginDialog")`
- 首页：`renderHomePage()`
- 云端语音包：`renderRootPage()` / `startCloudRefresh()` / `cloud_packs.json` 缓存
- 音乐盒：`renderMusicBoxPage()` / `startNeSearch()` / `playSongAt()` / `playLocalFile()`
- 清缓存：`musicCacheSize()` / `clearMusicCache()` / `addNeCacheRow()`
- 登录：`showLoginUI()` / `addLoginSection()`（QQ音乐）/ `addNetEaseLoginSection()` / `startWebLogin()`
- 登录存储：`saveLogin/getLogin/clearLogin/hasLogin`（宿主 `putString/getString`，配置 `music_login`）
- 播放地址：`nePlayUrl()`（账号接口 → outer url 兜底）
- 自更新：`forceCheckUpdate()` / `installViaHost()` / `installPluginZip()` / `localPluginVersion()`
- 网易云请求：`httpGetNe()`，下载：`httpDownloadEx(url, dest, cookie)`

## 5. 宿主注入 API（QFun / BeanShell）

`pluginPath`、`context`、`getNowActivity()`、`addItem()`、`qqToast()`（脚本内有 `toast()` 封装）、`sendPtt`、
`putString(config, key, value)`、`getString(config, key, default)`、`log(file, msg)`、`myUin`。
宿主为 Android QFun 模块，插件为脚本，不用编译；改完直接打包 zip。

## 6. 发布清单（每次改插件必做）

1. 改 `voicebox/info.prop` 的 `versionCode`
2. 改 `site/plugin/latest.json` 的 `version`
3. 改 `source/apk-patch/LocalPluginInjector.smali` 里的回退版本字符串
4. 打包 `voicebox.zip`，同步 APK 内置包与 `site/plugin/`
5. 构建 + 签名 APK（apktool + uber-apk-signer，debug 证书）
6. `git add -f plugin/voicebox.zip plugin/latest.json` 后推送
7. 等 1-3 分钟，用 `curl latest.json` 与 zip 内 `info.prop` 校验

## 7. 已知问题

- 音乐盒搜索只接网易云；QQ 音乐只用于登录
- 部分 VIP/版权歌曲登录后仍拿不到播放地址，会提示「暂无版权或需要会员」
- 登录 Cookie 按 QQ 号分开存，换号需重新登录
- 旧 APK 的商店版本不会动态同步（需含 `readVersion()` 的新 APK）
- 站点部署有 1-3 分钟延迟
- APK 为 debug 签名；内置插件只在插件缺失/过小时解压
- GitHub 推送令牌不要写入任何文档或仓库
