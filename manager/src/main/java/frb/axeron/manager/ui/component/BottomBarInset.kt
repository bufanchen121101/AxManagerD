/*
 * 悬浮底栏的「占用高度」下发通道（UI 层）。
 *
 * 背景（为什么必须有这个东西）：
 *   底栏（BottomBar）不是 Scaffold 的 bottomBar 槽，而是浮在内容之上的「兄弟节点」覆盖层；
 *   而每个页面自己也有一层 Scaffold，页内悬浮入口（floatingActionButton 槽）在 Material3 里的
 *   底边距只等于 FabSpacing(16dp) + contentWindowInsets.bottom（见 Scaffold.kt 的
 *   「无 bottomBar」分支），也就是说页面完全「看不见」底下还有一条悬浮底栏。
 *   结果就是：FAB 会正好落进底栏区域 —— 美化开启时被玻璃盖住但透出来（点不到），
 *   美化关闭时被不透明栏整个挡住（看不见）。
 *
 * 用法：
 *   AxActivity 实测底栏覆盖层高度后，减去页面 Scaffold 已经吃掉的系统栏底部内边距，
 *   得到「底栏在系统栏之上占的高度」，通过 LocalBottomBarInset 下发；
 *   页面里的悬浮入口直接 Modifier.padding(bottom = LocalBottomBarInset.current) 上抬即可。
 *   这样「玻璃底栏开 / 关」两种形态都能得到同样的 16dp 间距，也不受手势导航 / 三键导航差异影响。
 *
 * 底栏不可见（如激活页、刷入页等路由）时该值为 0.dp，页面保持原样。
 */
package frb.axeron.manager.ui.component

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * 悬浮底栏在「系统栏之上」占用的高度；底栏不可见时为 0.dp。
 *
 * 仅用于把页内悬浮元素（FAB 等）抬到底栏上方，不要拿它去给页面内容加底部内边距。
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }
