# S75 待机唤醒：证据与候选配置

**2026-11-01：三项组合配置重启后保留超过五分钟，两轮电源键注入短待机通过；长期与 STR 深度待机仍待验证。** 设备为 `MiTV-MFTR0 / Android 14 / OS3.0.106.0.UFTMATV`。当日还取得完整重启和物理断电冷启动自动进入的日志证据，见 [S75 开机说明](S75-BOOT-START.md)；本轮的轮次、包名与证据边界见 [实机记录](changes/2026-11-01-s75-startup-evidence.md)。

## 本机已确认的问题

- 普通息屏的 reason 8 路径中，`MitvPowerManagerService.gotoSleepStayAliveProcess` 判定归藏不在白名单，先返回 HOME，再约 2 秒后由系统 `force-stop`。本轮日志和本机 `services.jar` 中的延迟线程、`killAllThirdApp()` 调用吻合；reason 9 的 STR 路径独立，不能据此概括所有待机模式。
- 本机 `AccessibilityManagerService` 的强停处理会从启用列表移除对应服务并持久化 `enabled_accessibility_services`。本轮强停后无障碍关闭的时间线与此机制吻合，无需将配置丢失归因于 cloudcontrol；本轮未禁用该组件。
- `SettingsProvider` 将 `start_3rd_app` 空值的关联写入包记录为 `com.xiaomi.mitv.tvmanager`；这条记录本身没有给出写入时间。当前更新版电视管家 `3.04.016.051815` 的定制流程，在服务器返回 `status=13` 或 APK 配置无效等分支会写空该键。这与小米桌面应用列表的合规过滤不是同一机制。
- 当前小米桌面的官方启动调用没有向目标传递可区分完整开机和唤醒的参数。内部 `isSTR`、`isScreen` 不转发；`BootActivity.finish()` 还会先清除 STR 标志，并可能在关屏或广告失败时调用目标。仅放开 INFO 入口的同次开机去重，会有重复抢占风险。

进程被强停后，原来的亮屏线程和动态广播接收器均不能继续运行。[Android 官方说明](https://developer.android.com/reference/android/content/Intent#ACTION_SCREEN_ON)明确 `SCREEN_ON` 只能动态注册，且表示交互状态变化；实际显示状态还需另查。广播是否匹配 stopped 包取决于发送方的 [stopped 标志](https://developer.android.com/reference/android/content/Intent#FLAG_INCLUDE_STOPPED_PACKAGES)，不能靠在接收器清单中添加动作保证复活。

## 本机清理开关的验证

本机 `services.jar` 的 `gotoSleepStayAliveProcess()` 确认会读取 `Settings.Global disable_back_home_whitelist`。值为 `disable` 时，方法在白名单判断、回 HOME 和延迟清理之前直接返回，并记录 `gotoSleepStayAliveProcess feature disable`。

用户授权后，临时写入此值并从归藏前台注入电源键：日志确认进入 `Asleep`，再由电源键唤醒；归藏 PID 保持为 `5267`、前台仍为 `FrameHomeActivity`，无障碍列表始终为空。用户也反馈唤醒后仍显示归藏，但现有日志只区分出一轮睡眠/唤醒，不能计为两轮独立的遥控器成功。该结果证明进程和前台被保留，不是强停后复活。临时键随后已删除，读回及列表均确认恢复原来的不存在状态。

三项组合配置重启到 boot 26 后，又完成两轮电源键注入短待机，睡眠约 47.6 秒和 82.6 秒。两轮均有 `Asleep`、`feature disable` 和唤醒日志，隔离包 PID `1682`、`stopped=false` 保持，唤醒后仍为归藏前台，无障碍关闭。Home 键返回小米原桌面也已核对；两轮待机前从归藏前台开始，不能由此推断其他应用或 HDMI 前台不会被影响。这两轮不是独立的真实遥控器测试。

此开关作用于经过该方法的所有第三方应用，待机功耗和其他后台行为尚未测量。STR 的 reason 9 分支和 `killAllThirdAppForStr()` 不经过上述早退，较长待机及 STR 仍需另测。

## 相似问题的证据边界

| 一手来源 | 已确认的范围 | 对本机的意义 |
| --- | --- | --- |
| [Aerial 的 MFTR0 设备笔记](https://github.com/pengchujin/aerial-tv/blob/main/docs/devices/xiaomi-mitv-mftr0.md) | 同型 Android 14，固件 **OS3.0.101**：待机强停第三方进程；作者通过通知监听被系统重绑，再恢复无障碍守护处理 | 比较接近，但不是本机 **.106**。笔记中的云端电源白名单、仅 STR 生效且开机读取的 `str_killbackgroud_pkg_white_list`，不能当成通用可写白名单 |
| [Aerial #6 中的 MFTP0 用户反馈](https://github.com/pengchujin/aerial-tv/issues/6) | **MiTV-MFTP0 / Android 9 / build 6589**：`Settings.Global disable_back_home_whitelist=disable` 在反编译入口处提前返回，真实遥控器测试有效 | 提供了候选方向；本轮已独立核对本机 `.106` 固件并短测，不能从 Android 9 的结果推导其他路径有效 |
| [cleanYourTV #1434](https://github.com/Gathin23/cleanyourtv/issues/1434) | **MFTP0 / Android 9**：STR 是独立清理路径；上述全局开关不覆盖它；带冒号的进程名在该固件 STR 逻辑中有效 | 本机已捕获的是按包强停的非 STR 路径，不能照搬 `android:process=":home"` 来宣称修复 |

Aerial 的 [RevivalListener](https://github.com/pengchujin/aerial-tv/blob/main/app/src/main/java/app/orchard/tv/guard/RevivalListener.kt) 加上 `WRITE_SECURE_SETTINGS` 和无障碍接管，是另一种实现，**本次未引入**。当前归藏无障碍服务只是完整开机后备，不具备这套复活及 HOME 守护逻辑。通知监听需用户授权，且 [Android 文档](https://developer.android.com/reference/android/service/notification/NotificationListenerService)不保证每个厂商固件在强停后都自动重绑。

## 配置工具

`scripts/s75_startup.py` 默认只读 `inspect`；不安装、启动或卸载应用，不授予通知/无障碍权限，不禁用系统组件，也不重启电视。把示例地址和包名替换为实际值，隔离包使用 `io.github.op7418.guizangtv.s75test`。

```sh
python3 scripts/s75_startup.py inspect --serial TV_IP:5555 --package APP_PACKAGE
```

`apply` 写入 `Settings.System start_3rd_app=APP_PACKAGE`。另两个参数需要显式选择：

- `--skip-device-customization`：写入 **Settings.System `mitv_device_customize_skip=true`**。当前电视管家的开机接收器在安排约 4 分钟后的设备定制任务之前读取此键，值为 `true` 时跳过调度。它影响整套定制流程，包括应用、媒体/视频和相关电源配置，不是只保护归藏的单应用开关；服务入口不复查，后来写入不会取消已排定的任务。重启后至少等待 5 分钟并检查目标，才可验证本轮配置保留效果。
- `--experimental-standby`：写入 **Settings.Global `disable_back_home_whitelist=disable`**。本机普通待机短测已通过，范围和限制见上文；它仍影响所有第三方应用，STR 与长期待机未验证。这不是复活已强停进程的能力。

先确认已安装对应应用、应用内开关已开启。备份路径必须是尚不存在的文件，并放在仓库外的私人目录；不使用下例占位路径作为真实路径。

```sh
python3 scripts/s75_startup.py apply --serial TV_IP:5555 --package APP_PACKAGE \
  --backup /PRIVATE_DIR/s75-before.json
```

需要试验时，在同一条 `apply` 命令中显式添加上述参数。备份以 `0600` 权限保存本次涉及键原来的存在性、值及设备身份，已有文件拒绝覆盖。写入和读回一致仅证明配置写入成功，不能证明电视采用了它，也不能证明应用能恢复前台。

```sh
python3 scripts/s75_startup.py restore --serial TV_IP:5555 \
  --backup /PRIVATE_DIR/s75-before.json
```

`restore` 核对设备身份和固件，并要求当前键仍与工具记录的写入结果一致；检测到其他写入者修改时拒绝覆盖。写命令超时或断链后，若还不能确认远端是否执行，即使暂时读到原值，工具也保留未确认状态、拒绝宣称恢复完成。此时先保留备份、重新检查差异，不删除系统数据或强行恢复。原来缺失的键删除，原来为空字符串或有值的键恢复原样。

## 恢复连接后的验证

先读回配置，再从普通入口打开对应归藏一次，解除此前强停状态。随后用真实遥控器息屏、等待、唤醒，期间不手动打开归藏。核对同一启动编号、是否仍有强停日志、进程与实际前台。还需分别验证较长待机、STR、重复重启后的配置保留，以及其他应用和 HDMI/CEC 不被抢占；已通过的 Home 返回不替代这些回归。2026-11-01 已验证临时开关回滚；用户另行授权三项组合长期试用后，boot 26 自动进入归藏，运行到 305.88 秒时三项仍保留、归藏仍在前台，无障碍关闭。这证明本轮越过五分钟的保留窗口，不是长期稳定性证明。
