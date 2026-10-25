# S75 开机启动修复

执行者：Codex；会话：01a17379-b1e2-7600-93dd-e953fc95b37f。

## 问题与修改

MiTV-MFTR0 的系统启动入口可能早于用户存储解锁。旧 BootGate 不支持 Direct Boot；当前小米桌面 versionCode 100413 的 `openAutoApp()` 在包启动接口返回 null 后仍调用启动方法。修复只让 BootGate 支持 Direct Boot，在可见入口中最多等待解锁 30 秒，再打开原主界面。暂停、停止和超时均取消等待。

旧实现的 UI/广播使用 CE 偏好，无障碍使用另一份 DE 偏好；关闭开关可能失效，各通道可能重复打开。现在启动配置统一到 DE，解锁后一次性迁移旧 CE 用户选择并保留较新的启动编号。启动请求共用同步去重；仅主界面恢复显示时写入 `confirmed`，日志和诊断同时带包名、来源与启动编号。

删除不可靠的待机亮屏线程。广播接收器仅处理系统完整开机广播，不能由普通应用通过原来的厂商动作触发；没有后台启动授权时不占用官方入口的启动机会。无障碍保留为用户显式启用的后备路径，不自动开启服务，不修改系统组件。

## 验证

- 存储回归：修复前 4 项 AssertionError；修复后通过。
- 解锁前 CE-only 升级、暂停后回调：各有实际失败记录，修复后通过。
- 暂停超过截止时间再恢复：先复现失败，修复后通过。
- 最终 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 通过；32 项 Java 测试，0 failures、0 errors。Python 原有资源处理测试 4/4 通过。
- 独立代码复审通过；`git diff --check` 通过。
- 电视安装前对照：旧包在只匹配 Direct Boot 组件的 INFO 查询中返回 `No activities found`；新隔离包能解析到 BootGate。
- 隔离包普通打开正常；开关迁移及系统目标核对通过；Home 返回小米桌面，晚于开机窗口的入口调用没有抢占。

首次 CI 40203738552 使用了不存在的 Robolectric 版本，第二轮 40204156553 缺少 AndroidX 测试依赖开关，均未形成有效 RED。已修正构建配置；以上有效 RED/GREEN 和最终全套结果来自本地 JDK 17、Gradle 8.9、Android SDK 34 环境。

## 实机包与边界

为保留原应用及其签名和数据，验证包仅改变 applicationId 为 `io.github.op7418.guizangtv.s75test`、显示名为“归藏电视 S75验证”，namespace 和 Java 代码不变。APK SHA-256：`52cf85509c05e5a8d45c369b62dfbc770fadf2eecce2f8e1da0690aa1b2777da`。固定调试证书 SHA-256：`6ccdabdce54881412d6c425fd99051621cc5a236464a0b969fa96662dd78d2fa`。

完整重启验证进行中，尚不宣称自启成功。待机唤醒、拔电冷启动和长期运行尚未验证；构建及手动打开通过不能替代这些结果。未禁用任何系统组件，未伪装安装来源，未开启归藏无障碍或悬浮窗权限。
