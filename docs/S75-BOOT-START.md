# 小米 S75 开机自动进入

适配对象为 `MiTV-MFTR0`、HyperOS TV `OS3.0.106.0.UFTMATV`、Android 14。上游 MiTV-MTSU0 的结果不能替代这台电视的验证。请使用本 fork 对应修复分支的 APK，保留安装包签名；上游 Releases 不包含这些修复。

## 故障与修复范围

固件可能在用户存储解锁前调用 `start_3rd_app`。原 `BootGateActivity` 未声明 Direct Boot 支持，包启动接口此时可能找不到入口。修复只让启动判断入口在解锁前可用，待存储解锁后再打开主界面；主界面及个人配置仍使用原来的存储保护。

历史版本还把开关和启动记录分放在 CE、DE 两份偏好中，导致关闭开关失效和多通道重复启动。修复统一启动配置，迁移旧用户选择；调用启动 API 只记为请求，桌面恢复显示后才确认成功。

本次以完整重启为目标。待机亮屏和拔电冷启动需要分别验证，不能用重启结果推断。旧版亮屏线程可能在用户已经切换应用或关闭屏幕后抢占画面，因此不作为可靠自启方案继续启用。

## 配置与核对

将以下 `TV_IP:PORT` 替换为电视已授权的 ADB 地址，`APP_PACKAGE` 替换为所装 APK 的实际包名。正式包为 `io.github.op7418.guizangtv`；本次独立验证包使用 `io.github.op7418.guizangtv.s75test`，保留原版安装。系统目标、手动入口和诊断必须使用同一包名。先记录原值：

```sh
adb -s TV_IP:PORT shell getprop ro.product.model
adb -s TV_IP:PORT shell getprop ro.build.version.incremental
adb -s TV_IP:PORT shell settings get system start_3rd_app
adb -s TV_IP:PORT shell settings get system boot_auto_play
adb -s TV_IP:PORT shell settings get secure enabled_accessibility_services
adb -s TV_IP:PORT shell pm list packages -d
```

1. 使用同签名 APK 覆盖安装，或使用独立包名验证；打开对应版本一次，在“设置 → 电视与启动”开启自动进入。签名不同会拒绝覆盖；不要直接卸载而丢失用户设置，也不要把签名校验失败当成安装成功。如果 `boot_auto_play` 为 `1`，先通过电视系统设置关闭“开机直接播放信号源”，否则该路径可能优先。CEC OneTouchPlay 也可能影响启动，需要单独核对。
2. 原自启目标为空或确认替换后，配置系统入口：

```sh
adb -s TV_IP:PORT shell settings put system start_3rd_app APP_PACKAGE
adb -s TV_IP:PORT shell settings get system start_3rd_app
```

3. 保持小米原桌面、小爱、投屏、直播、HDMI 及云控组件正常启用。先验证官方入口；不要因旧报告声称云控会还原设置就自动禁用系统包。
4. 完整重启前确认没有正在进行的播放或操作，然后执行 `adb -s TV_IP:PORT reboot`。从开机开始观察，期间不要手动打开归藏。
5. 如果重启关闭了 ADB，在电视设置中重新开启后取证。重新开启 ADB 不代表自启已经成功。

```sh
adb -s TV_IP:PORT shell settings get global boot_count
adb -s TV_IP:PORT shell dumpsys activity APP_PACKAGE
adb -s TV_IP:PORT shell logcat -d -s GuiZangBoot
```

成功判据是：用户观察到自动显示归藏、日志记录对应启动来源与本次启动编号，且主界面恢复显示的确认与之对应。仅有 `startActivity` 返回、构建成功、手动 `am start` 成功或上次启动留下的 status 均不充分。

## 可选无障碍后备路径

本 fork 清单保留无障碍后备服务及开机广播。无障碍必须在系统中实际启用且绑定才可起作用；固件清除该设置时，应用自身不能保证恢复。服务仅观察窗口变化，不读取窗口内容。配置已有小爱等无障碍服务时必须保留其条目。

不要用后台启动被阻止的请求消耗官方前台入口的机会，不要把 `SYSTEM_ALERT_WINDOW` 的 appops 写入视为跨重启授权保证。后台启动限制见 [Android 官方说明](https://developer.android.com/guide/components/activities/secure-bal)，存储解锁与组件要求见 [Direct Boot 文档](https://developer.android.com/privacy-and-security/direct-boot)。

## 恢复

先关闭归藏的自动进入开关，再将 `start_3rd_app` 恢复为记录的原值。原来没有该键用 `settings delete system start_3rd_app`；原来为空字符串则恢复空字符串。若试验启用了归藏无障碍服务，只移除归藏条目，保留其他服务。小米桌面可能缓存原自启包名，恢复系统键后仍应保持应用内开关关闭、保留启动判断入口，并完整重启确认恢复；无需清除小米桌面数据。

## 证据边界

2026-10-25 初次检查时，电视内归藏开关为开启，但归藏无障碍未启用，`start_3rd_app` 为空，系统没有禁用包。历史报告中的“稳定成功”不能证明当前配置。当前电视的小米桌面版本为 `100413`，APK SHA-256 为 `f0a5b149db71ba5660a69bccdd89859668fee13f3a03065ded64fb8d3af04986`。对该 APK 的静态检查确认，“删除不合规应用”对应桌面列表的 `Iterator.remove()`，该日志本身不能证明应用被卸载或自启键被清除。

最终构建与本次实机结果见 [修复记录](changes/2026-10-25-s75-direct-boot.md)。尚未补入的新重启记录不得视为验证通过。
