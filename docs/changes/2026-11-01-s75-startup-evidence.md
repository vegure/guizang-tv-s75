# S75 重启、冷启动与待机实机证据

执行者：Codex；会话：01a194f6-425e-7c00-b019-0f9761f5bdcc。

本轮更新说明文档及配置工具的过期提示，不改变 Android 启动逻辑。设备为 `MiTV-MFTR0 / Android 14 / OS3.0.106.0.UFTMATV`。原包保留；隔离包为 `io.github.op7418.guizangtv.s75test`，电视中已有 APK 的 SHA-256 与此前产物一致：`52cf85509c05e5a8d45c369b62dfbc770fadf2eecce2f8e1da0690aa1b2777da`。本轮没有重装、改签名或禁用系统组件。

## 实际结果

| 场景 | 证据与结论 |
| --- | --- |
| 正常重启，boot 23→24 | 隔离包经官方 `system-hook` 进入，DE 为 `attempted=24`、`confirmed=24`；主包随后经开机广播进入桌面。用户确认完整重启能进入归藏。 |
| 物理断电，boot 24→25 | 用户确认实际拔电、稍后才回复。系统 UID 1000 调用隔离包的 BootGate，主界面取得 `onResume` 和 top-resumed，DE 为 `source=system-hook`、`attempted=25`、`confirmed=25`；当时无障碍关闭。冷启动入口日志成功一次。 |
| 普通待机，无开关 | reason 8 下先返回 HOME，约两秒后系统强停归藏；无障碍配置随后为空。唤醒显示小米桌面。 |
| 普通待机，临时开关 | `disable_back_home_whitelist=disable` 下日志记录 `feature disable`；一次电源键注入测试确实进入 Asleep 再唤醒，PID `5267` 不变，归藏仍为前台，`stopped=false`，无障碍列表为空。用户反馈唤醒后仍显示归藏；现有日志只区分出一轮，不能计为两轮独立的真实遥控器成功。 |

boot 25 开机后，系统屏保先覆盖归藏；之后普通待机清理强停隔离包。后续同一 boot 的唤醒再次调用官方入口，因“本次开机已尝试”而跳过。晚些看到小米桌面不能作为冷启动入口未执行的证据。

启动日志中发生系统校时，其中一次为 965 秒跳变。跨校时的壁钟差不能直接当作启动耗时；本轮用 boot 编号、运行时长和活动事件顺序核对。

## 固件机制

本机 `services.jar` SHA-256：`fa6cb988758968decfcf540e3a5874b443c0d6e1b5a85dfd3f2379c96a69c1c8`。

- `MitvPowerManagerService.gotoSleepStayAliveProcess()` 读取 Global `disable_back_home_whitelist`，等于 `disable` 时在白名单、回 HOME、延迟清理之前返回。否则非白名单分支返回 HOME，线程等待 2000ms 后调用 `killAllThirdApp()`，最终强停应用；本轮运行日志与之吻合。
- `AccessibilityManagerService` 的强停处理会移除对应包的启用服务，持久化 `enabled_accessibility_services` 并更新状态。强停本身能解释本轮无障碍丢失，不能沿用旧报告将清除者直接归因于 cloudcontrol。
- reason 9 的 STR 路径及 `killAllThirdAppForStr()` 独立；上述普通待机开关不覆盖全部 STR 清理。

当前 `/data/app` 更新版电视管家为 `3.04.016.051815`，APK SHA-256：`782cf92220b01ec0d4214d3592df2643f77c23fb1c3ca1dddac9e772bb8e382b`。

- SettingsProvider 的空 `start_3rd_app` 记录关联写入包为电视管家，但记录本身不含触发时间。
- 其定制流程在服务器 `status=13`、APK 配置无效或包名为空等分支写空 `start_3rd_app`。
- 开机接收器读取 System `mitv_device_customize_skip`；为 `true` 时跳过约四分钟后设备定制任务的调度。服务入口不重新检查，后来写入不取消已排定的任务。
- 跳过范围涵盖应用、媒体/视频及相关电源定制，不只是保护归藏目标；本轮检查未找到更窄的目标保留开关。

## 配置、回滚与待验项

临时待机实验结束后，Global 开关已删除，读回及列表确认恢复为原先不存在的状态。冷启动测试结束后，`start_3rd_app` 已恢复为原先存在的空字符串。无障碍恢复为关闭，未因本轮调查禁用系统组件。

用户随后明确授权长期试用以下三项，工具已以硬件及固件身份绑定的私有备份记录原值，再写入并读回：

| 设置 | 写入值 | 影响与边界 |
| --- | --- | --- |
| System `start_3rd_app` | `io.github.op7418.guizangtv.s75test` | 使用已安装的 Direct Boot 官方入口 |
| System `mitv_device_customize_skip` | `true` | 跳过整套设备定制任务调度；本轮重启后配置保留超过五分钟，长期效果未验证 |
| Global `disable_back_home_whitelist` | `disable` | 影响该普通待机路径中的所有第三方应用；功耗和其他后台行为未测 |

组合配置完成一次正常重启，boot 25→26。官方入口在开机经过约 16.377 秒、用户存储尚未解锁时进入；解锁后主界面确认 `source=system-hook`、`confirmed=26`，用户也确认自动进入。运行到 305.88 秒时，三项仍分别为隔离包目标、`true`、`disable`；归藏 PID `1682`、`stopped=false`、前台为 `FrameHomeActivity`，无障碍保持 `0/null`。这是一次组合配置重启及越过五分钟窗口的保留验证，不是长期稳定证明。

组合配置下又完成两轮电源键注入短待机：

| 轮次 | 日志睡眠至唤醒 | 结果 |
| --- | --- | --- |
| 1 | 约 47.6 秒 | `Asleep` 后唤醒，PID `1682` 不变，`stopped=false`，归藏仍在前台 |
| 2 | 约 82.6 秒 | 同一 PID 和前台保留，三项配置仍在，无障碍为空 |

两轮均有 `gotoSleepStayAliveProcess feature disable`，对应窗口没有目标包的 force-stop 日志。Home 键返回 `com.mitv.tvhome/.MainActivityUserMode` 已核对，随后手动回归藏作为待机测试起点。这不等于两轮独立真实遥控器验证，也不覆盖 STR、长待机或其他应用前台。随后真实长待机却在配置和进程均保留时失败：系统启动页面覆盖归藏，同次 boot 26 的 INFO 入口按既有去重退出。这个结果推翻了“保活足以完成唤醒恢复”的推断，新的代码修复见 [亮屏入口恢复](2026-11-01-s75-wake-entry.md)。

组合配置下的再次物理冷启动已完成：boot 26→27，官方入口在经过约 16.444 秒、尚未解锁时收到；主界面随后记录 `source=system-hook`、`confirmed=27`。重连时三项设置仍保留。该结果验证早期隔离包的冷启动入口，不是新的亮屏恢复包已通过设备验收。

恢复时使用同一备份的 `scripts/s75_startup.py restore`；原来缺失的键应删除，`start_3rd_app` 原来为空则恢复空字符串，不能互换。具体流程见 [待机说明](../S75-STANDBY.md)。

仍需新唤醒实现的设备对照、多轮配置保留、独立重复真实遥控器与较长待机、STR、功耗，以及其他应用和 HDMI/CEC 回归。尚不能宣称三种场景已稳定、长期无需干预。

私有原始日志、网络地址、硬件身份、应用数据及备份只保存在仓库外；本记录仅收录脱敏证据摘要。
