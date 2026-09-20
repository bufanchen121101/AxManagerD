package frb.axeron.manager.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import frb.axeron.manager.features.overlay.OverlayRequestWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 模块授权申请 —— **全局监听宿主**。
 *
 * 背景（Bug 修复）：
 *   早期实现把「轮询 pending 申请」挂在授权页（OverlayPermissionScreen）的
 *   LaunchedEffect 里 —— 只有当用户**恰好停留在授权页**时才会轮询。
 *   模块在后台申请权限（`axoverlay request`）时用户根本不在该页，
 *   于是 pending 文件一直躺在那，App 永远不弹窗 → 表现为「直接授权 / 无弹窗」。
 *
 * 修复：
 *   把监听提升到 Activity 顶层（[frb.axeron.manager.ui.AxActivity]），
 *   只要 App 进程存活且 Activity 在前台，就会持续轮询 pending 目录，
 *   一旦发现申请立即弹 [OverlayRequestDialog] —— 与用户当前停留在哪个页面无关。
 *
 * 约束：
 *   - 不依赖「免责已同意 / 全局开关已开启」才轮询：模块已经申请了，
 *     就应该让用户看到并自己决定（拒绝也是决定）。避免用户因没开开关而永远看不到弹窗。
 *   - 去重由 [OverlayRequestWatcher.handled] 保证：同一模块只弹一次。
 *   - 同一时刻只弹一条，处理完自动检查下一条，支持连续多个申请。
 *
 * 该组件只负责「发现 + 弹窗 + 写授权」，不修改任何既有页面逻辑。
 */
@Composable
fun GlobalOverlayRequestHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 当前待用户处理的申请（null = 无）。
    var current by remember { mutableStateOf<OverlayRequestWatcher.Request?>(null) }

    // 全局轮询：Activity 存活期间常驻。
    LaunchedEffect(Unit) {
        while (true) {
            // 已有弹窗时不打断（避免内容跳变）。
            if (current == null) {
                val req = runCatching { OverlayRequestWatcher.peek(context) }.getOrNull()
                if (req != null) current = req
            }
            delay(OverlayRequestWatcher.POLL_INTERVAL_MS)
        }
    }

    current?.let { req ->
        OverlayRequestDialog(
            moduleId = req.moduleId,
            reason = req.reason,
            onDecide = { allow, always ->
                current = null
                // 先标记已处理，防止轮询在写入完成前又扫到同一条。
                OverlayRequestWatcher.markHandled(req.moduleId)
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        OverlayRequestWatcher.resolve(context, req.moduleId, allow, always, req.reason)
                    }
                }
            },
            onDismiss = {
                current = null
                // 关闭 = 稍后处理：只标记已读，不写授权记录（下次申请仍会弹）。
                OverlayRequestWatcher.markHandled(req.moduleId)
            },
        )
    }
}