# 小米 S75 开机自动进入

> **后续长待机发现：** boot 26 中进程保活、配置保留后，系统启动页面仍会覆盖归藏，旧 INFO 去重阻止恢复。新的亮屏后入口恢复实现已通过本地测试和 ADB 受控入口对照；真实厂商自动调用、长待机及新包冷启动仍待验。见 [唤醒入口修复](changes/2026-11-01-s75-wake-entry.md)。下文短待机成功不能扩展为长期待机已修复。

适配对象为 `MiTV-MFTR0`、HyperOS TV `OS3.0.106.0.UFTMATV`、Android 14。上游 MiTV-MTSU0 的结果不能替代这台电视的验证。请使用本 fork 对应修复分支的 APK，保留安装包签名；上游 Releases 不包含这些修复。

## 故障与修复范围

固件可能在用户存储解锁前调用 `start_3rd_app`。原 `BootGateActivity` 未声明 Direct Boot 支持，包启动接口此时可能找不到入口。修复只让启动判断入口在解锁前可用，待存储解锁后再打开主界面；主界面及个人配置仍使用原来的存储保护。

历史版本还把开关和启动记录分放在 CE、DE 两份偏好中，导致关闭开关失效和多通道重复启动。修复统一启动配置，迁移旧用户选择；调用启动 API 只记为请求，桌面恢复显示后才确认成功。

2026-11-01 已取得隔离包在完整重启和真实物理断电冷启动后自动进入的日志证据。普通待机在临时固件开关下短测通过；三项组合配置重启后保留超过五分钟，两轮电源键注入短待机也通过。STR、长待机及长期配置保留仍未验证。各场景不能相互替代，详见 [本轮实机记录](changes/2026-11-01-s75-startup-evidence.md)。旧版亮屏线程可能在用户已经切换应用或关闭屏幕后抢占画面，因此不作为可靠自启方案继续启用。

## 配置与核对

将以下 `TV_IP:PORT` 替换为电视已授权的 ADB 地址，`APP_PACKAGE` 替换为所装 APK 的实际包名。正式包为 `io.github.op7418.guizangtv`；早期独立启动验证包为 `io.github.op7418.guizangtv.s75test`，新增亮屏恢复验证包为 `io.github.op7418.guizangtv.s75wake`。按实际 APK 选择包名，保留原版安装。系统目标、手动入口和诊断必须使用同一包名。先记录原值：

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

遥控器息屏/待机的固件证据、全局开关范围和可回滚工具见 [待机说明](S75-STANDBY.md)。普通开机目标可能被电视管家的定制流程写空；需要跳过该流程时必须另行明确选择，不能默认扩大到三项配置。

## 可选无障碍后备路径

本 fork 清单保留无障碍后备服务及开机广播。无障碍必须在系统中实际启用且绑定才可起作用；本机系统在强停对应包时会移除其无障碍启用项，应用自身不能保证恢复。2026-11-01 的隔离包官方入口冷启动、普通待机开关短测均不依赖无障碍。服务仅观察窗口变化，不读取窗口内容。配置已有小爱等无障碍服务时必须保留其条目。

不要用后台启动被阻止的请求消耗官方前台入口的机会，不要把 `SYSTEM_ALERT_WINDOW` 的 appops 写入视为跨重启授权保证。后台启动限制见 [Android 官方说明](https://developer.android.com/guide/components/activities/secure-bal)，存储解锁与组件要求见 [Direct Boot 文档](https://developer.android.com/privacy-and-security/direct-boot)。

## 恢复

先关闭归藏的自动进入开关，再将 `start_3rd_app` 恢复为记录的原值。原来没有该键用 `settings delete system start_3rd_app`；原来为空字符串则恢复空字符串。若试验启用了归藏无障碍服务，只移除归藏条目，保留其他服务。小米桌面可能缓存原自启包名，恢复系统键后仍应保持应用内开关关闭、保留启动判断入口，并完整重启确认恢复；无需清除小米桌面数据。

## 2026-11-01 实机结果

| 场景 | 已取得的证据 | 尚未证明的范围 |
| --- | --- | --- |
| 正常重启 | boot 23→24 两包进入；三项组合试用下 boot 25→26 隔离包官方入口 `confirmed=26`，用户确认自动进入，三项配置保留超过五分钟 | 长期稳定性、原包覆盖升级 |
| 断电冷启动 | 用户确认物理断电；boot 24→25 和组合配置下 boot 26→27，隔离包官方入口分别 `confirmed=25/27`，后者三项配置保留 | 新唤醒验证包的冷启动、长期保持前台 |
| 普通待机 | 无开关时回 HOME 并强停；临时开关下一轮短测通过；组合配置下两轮电源键注入短待机均保留隔离包 PID `1682` 和归藏前台，无障碍关闭 | 独立重复真实遥控器轮次、长待机、STR、功耗 |

冷启动后的晚些观察中，归藏先被系统屏保覆盖，之后被待机清理强停；数小时后看到小米桌面不能直接写成冷启动入口失败。开机日志存在系统校时，含 965 秒跳变，因此用 boot 编号、运行时长和事件先后核对，不能直接以跨校时的壁钟差计时。

本轮使用的是电视上已存在的隔离 APK，SHA-256 与 2026-10-25 产物一致；未重装、未改签名，原包和数据保留。临时待机开关及冷启动目标分别已恢复过原值。之后用户另行授权三项长期试用，工具已先备份再写入；boot 26 经官方入口自动进入，用户确认成功。运行到 305.88 秒时，目标、定制跳过和普通待机清理开关均保留，归藏仍为前台，PID `1682`、`stopped=false`、无障碍关闭。该轮越过五分钟窗口，仍不能据此认定长期稳定或完整验收。随后两轮注入短待机通过，Home 键返回小米原桌面通过；其后真实长待机仍回小米桌面，原因是系统启动页面覆盖归藏，旧入口按同次开机去重而退出，并非进程被清理；这项失败不能被短测成功掩盖。组合配置的再次物理冷启动 boot 27 则记录官方入口 `confirmed=27`，三项设置保留。新 `.s75wake` 实现的设备回归另见顶部链接。

## 2026-10-25 历史证据

2026-10-25 初次检查时，电视内归藏开关为开启，但归藏无障碍未启用，`start_3rd_app` 为空，系统没有禁用包。历史报告中的“稳定成功”不能证明当前配置。当前电视的小米桌面版本为 `100413`，APK SHA-256 为 `f0a5b149db71ba5660a69bccdd89859668fee13f3a03065ded64fb8d3af04986`。对该 APK 的静态检查确认，“删除不合规应用”对应桌面列表的 `Iterator.remove()`，该日志本身不能证明应用被卸载或自启键被清除。

2026-10-25，独立验证包 `io.github.op7418.guizangtv.s75test` 完成一次真实完整重启验证：系统 `boot_count` 从 `20` 增至 `21`；归藏 DE 启动记录为 `source=system-hook`、`attempted=21`、`confirmed=21`，用户确认开机后自动显示归藏。该结果针对同一 Java 实现的隔离包，不能代替原包覆盖升级后的验证，也不构成长期稳定性保证。

遥控器息屏/待机唤醒复测未通过：启动编号仍为 `21`，唤醒后显示小米桌面。后续还观察到系统自启目标被写为空；该现象需要继续排查，完整重启成功不代表此问题已经解决。

本地 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 通过，32 项 Java 测试无失败，Python 原有测试 4/4 通过。详细修复与实机边界见 [修复记录](changes/2026-10-25-s75-direct-boot.md)。
