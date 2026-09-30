# io.github.vstory.hook.idm.plus · IDMPlusSnifferBypass

LSPosed (libxposed api102) 模块：**绕过 1DM+ 嗅探/下载的站点名单限制**（YouTube + Facebook/Instagram/fb.watch 全放行）。

- 目标 APP：1DM+ (`idm.internet.download.manager.plus`) 18.2 (30249)
- 作用域：仅 1DM+
- 模块名：IDMPlusSnifferBypass

## 原理（一句话）

1DM+ 内置 Lightning 浏览器嗅探流程统一经 `Li/UZ;->ۥۚۧۗ(String, AtomicBoolean)Z` 判定受限站点
（YT → 置标记 return true；FB/IG/fb.watch → return true；其它 → false；调用点均把 true 当"受限/跳过/拒绝"）。
本模块 hook 该方法直接 `return false`，即解除全部名单限制。

## 构建

```bash
./gradlew :app:assembleDebug          # Debug（默认自测版）
./gradlew :app:assembleRelease        # 正式版
```

产物：`app/build/outputs/apk/{debug,release}/`

## 验证（实测 2026-09-07）

装模块 → LSPosed 启用并勾选作用域 1DM+ → 1DM+ 内置浏览器打开视频页：

- ✅ **A（已确认）**：YouTube / Facebook / Instagram 不再弹 "Youtube request!" 拒绝框——hook 拦截生效
  （`[DBG] ۥۚۧۗ intercepted | url=... | return FALSE` 在 LSPosed 日志 D 级可见）
- ✅ B站正常：`m.bilibili.com/video/...` 可嗅探出视频
- ⏳ **B（开发中）**：YouTube 视频本身仍嗅探不出——1DM+ 产品层拒绝 YT，**没有内置 YT 解析器**；
  嗅探器只按 `.mp4/.m3u8` 等扩展名抓直链，YT 的 DASH 分段流（googlevideo 无扩展名）进不了候选。
  B 目标 = 注入 YT 页面流解析（解析 `ytInitialPlayerResponse` 取流 URL 塞入嗅探候选）

详细逆向分析见 `dev-project/分析_1DMplus18.2_嗅探与YouTube.md`，hook 清单见 `dev-project/hook信息记录.md`。
