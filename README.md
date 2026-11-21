# voicepack · 语音盒

QFun 插件「语音盒」的站点、插件包、源码与文档仓库。

- 站点：<https://mcer.dpdns.org>
- 插件最新版本：**1.9.4**（`plugin/latest.json`）
- 插件下载：<https://mcer.dpdns.org/plugin/voicebox.zip>

## 文档 / 交接

- [项目总览](docs/00-project-overview.md)：功能、接口、构建、版本轨迹
- [对话开发记录](docs/01-conversation-devlog.md)：每条需求与修复的时间线
- [仓库清单（给 AI 的单文件入口）](docs/02-repo-index-for-ai.md)

## 源码

- 插件主脚本：[source/voicebox/main.java](source/voicebox/main.java)
- 插件元信息：[source/voicebox/info.prop](source/voicebox/info.prop)
- APK 侧补丁：
  [LocalPluginInjector.smali](source/apk-patch/LocalPluginInjector.smali) ·
  [BuiltinPluginInstaller.smali](source/apk-patch/BuiltinPluginInstaller.smali)

## 目录

```
index.html          站点首页（语音包 + 插件下载）
version.json        数字版本（packs/files/songs），客户端缓存比对
audio/              云端语音包（192 包 / 9911 文件）
music/              早期 CC 曲库（音乐盒已改为网易云）
plugin/             voicebox.zip 与 latest.json
source/             插件源码与 APK 补丁 smali
docs/               项目文档
```

> `data.json` 与 `*.zip` 在 `.gitignore` 中，推送插件包需 `git add -f plugin/voicebox.zip`。
