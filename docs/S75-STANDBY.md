# S75 待机唤醒：证据与候选配置

**完整重启已在隔离包验证一次，遥控器息屏/待机唤醒仍未解决。** 本机为 `MiTV-MFTR0 / Android 14 / OS3.0.106.0.UFTMATV`。目前电视离线；以下工具与候选配置尚未在本机执行验证，不能把设置读回成功写成唤醒修复成功。完整重启记录见 [S75 开机说明](S75-BOOT-START.md)。

## 本机已确认的问题

- 遥控器息屏时，`PowerManagerService.gotoSleepStayAliveProcess` 判定验证包不在白名单，约 2 秒后由系统 `force-stop`。捕获的是这条非 STR 清理路径；不能据此声称所有待机模式都走相同路径。
- `SettingsProvider` 将 `start_3rd_app` 空值的最后写入者记录为 `com.xiaomi.mitv.tvmanager`。电视管家 APK 的本地检查还找到了设备定制同步跳过键 `Settings.System mitv_device_customize_skip`；这与小米桌面应用列表的合规过滤不是同一机制。
- 当前小米桌面的官方启动调用没有向目标传递可区分完整开机和唤醒的参数。内部 `isSTR`、`isScreen` 不转发；`BootActivity.finish()` 还会先清除 STR 标志，并可能在关屏或广告失败时调用目标。仅放开 INFO 入口的同次开机去重，会有重复抢占风险。

进程被强停后，原来的亮屏线程和动态广播接收器均不能继续运行。[Android 官方说明](https://developer.android.com/reference/android/content/Intent#ACTION_SCREEN_ON)明确 `SCREEN_ON` 只能动态注册，且表示交互状态变化；实际显示状态还需另查。广播是否匹配 stopped 包取决于发送方的 [stopped 标志](https://developer.android.com/reference/android/content/Intent#FLAG_INCLUDE_STOPPED_PACKAGES)，不能靠在接收器清单中添加动作保证复活。

## 相似问题的证据边界

| 一手来源 | 已确认的范围 | 对本机的意义 |
| --- | --- | --- |
| [Aerial 的 MFTR0 设备笔记](https://github.com/pengchujin/aerial-tv/blob/main/docs/devices/xiaomi-mitv-mftr0.md) | 同型 Android 14，固件 **OS3.0.101**：待机强停第三方进程；作者通过通知监听被系统重绑，再恢复无障碍守护处理 | 比较接近，但不是本机 **.106**。笔记中的云端电源白名单、仅 STR 生效且开机读取的 `str_killbackgroud_pkg_white_list`，不能当成通用可写白名单 |
| [Aerial #6 中的 MFTP0 用户反馈](https://github.com/pengchujin/aerial-tv/issues/6) | **MiTV-MFTP0 / Android 9 / build 6589**：`Settings.Global disable_back_home_whitelist=disable` 在反编译入口处提前返回，真实遥控器测试有效 | 日志方法与本机相似，只能列为候选；该开关影响这条路径上的所有第三方应用，待机功耗和内存影响未测 |
| [cleanYourTV #1434](https://github.com/Gathin23/cleanyourtv/issues/1434) | **MFTP0 / Android 9**：STR 是独立清理路径；上述全局开关不覆盖它；带冒号的进程名在该固件 STR 逻辑中有效 | 本机已捕获的是按包强停的非 STR 路径，不能照搬 `android:process=":home"` 来宣称修复 |

Aerial 的 [RevivalListener](https://github.com/pengchujin/aerial-tv/blob/main/app/src/main/java/app/orchard/tv/guard/RevivalListener.kt) 加上 `WRITE_SECURE_SETTINGS` 和无障碍接管，是另一种实现，**本次未引入**。当前归藏无障碍服务只是完整开机后备，不具备这套复活及 HOME 守护逻辑。通知监听需用户授权，且 [Android 文档](https://developer.android.com/reference/android/service/notification/NotificationListenerService)不保证每个厂商固件在强停后都自动重绑。

## 配置工具

`scripts/s75_startup.py` 默认只读 `inspect`；不安装、启动或卸载应用，不授予通知/无障碍权限，不禁用系统组件，也不重启电视。把示例地址和包名替换为实际值，隔离包使用 `io.github.op7418.guizangtv.s75test`。

```sh
python3 scripts/s75_startup.py inspect --serial TV_IP:5555 --package APP_PACKAGE
```

`apply` 写入 `Settings.System start_3rd_app=APP_PACKAGE`。另两个参数需要显式选择：

- `--skip-device-customization`：写入 **Settings.System `mitv_device_customize_skip=true`**，尝试跳过电视管家设备定制同步。它影响整套设备定制同步，不是只保护归藏的单应用开关。该键仅在开机安排同步时检查，写入后不会取消已经排定的约 4 分钟闹钟；不能立即据此判断目标清空问题已解决。
- `--experimental-standby`：写入 **Settings.Global `disable_back_home_whitelist=disable`**。本机是否读取和执行此键尚未证实；如果生效，才可能避免已捕获的电源强停。这不是复活已强停进程的能力。

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

先读回配置，再从普通入口打开对应归藏一次，解除此前强停状态。随后用真实遥控器息屏、等待、唤醒，期间不手动打开归藏。核对同一启动编号、是否仍有强停日志、进程与实际前台，并测试 Home、其他应用和 HDMI/CEC 不被抢占。还需分别验证较长待机、完整重启及配置恢复；电视离线时这些结果均保持未验证。
