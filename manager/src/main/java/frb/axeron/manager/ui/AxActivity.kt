package frb.axeron.manager.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import frb.axeron.manager.ui.theme.LocalLiquidGlass
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import frb.axeron.manager.ui.component.AppBackgroundLayer
import frb.axeron.manager.ui.component.LiquidGlassBottomBar
import frb.axeron.manager.ui.component.LocalBottomBarInset
import frb.axeron.manager.ui.glass.Backdrop
import frb.axeron.manager.ui.glass.layerBackdrop
import frb.axeron.manager.ui.glass.rememberLayerBackdrop
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.animations.NavHostAnimatedDestinationStyle
import com.ramcosta.composedestinations.generated.NavGraphs
import com.ramcosta.composedestinations.generated.destinations.ActivateScreenDestination
import com.ramcosta.composedestinations.generated.destinations.EnablePluginScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ExecutePluginActionScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FlashScreenDestination
import com.ramcosta.composedestinations.generated.destinations.HomeScreenDestination
import com.ramcosta.composedestinations.generated.destinations.QuickShellScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.navigation.dependency
import com.ramcosta.composedestinations.utils.isRouteOnBackStackAsState
import com.ramcosta.composedestinations.utils.rememberDestinationsNavigator
import frb.axeron.api.AxeronInfo
import frb.axeron.api.core.AxeronSettings
import frb.axeron.manager.R
import frb.axeron.manager.features.keepalive.KeepAliveService
import frb.axeron.manager.ui.navigation.BottomBarDestination
import frb.axeron.manager.ui.screen.FlashIt
import frb.axeron.manager.ui.theme.AxManagerTheme
import frb.axeron.manager.ui.theme.LocalBackgroundImage
import frb.axeron.manager.ui.util.LocalSnackbarHost
import frb.axeron.manager.ui.theme.LiquidGlassParams
import frb.axeron.manager.ui.theme.LiquidGlassSettings
import frb.axeron.manager.ui.util.LocaleHelper
import frb.axeron.manager.ui.viewmodel.ActivateViewModel
import frb.axeron.manager.ui.viewmodel.AppsViewModel
import frb.axeron.manager.ui.viewmodel.PluginViewModel
import frb.axeron.manager.ui.viewmodel.PrivilegeViewModel
import frb.axeron.manager.ui.viewmodel.SettingsViewModel
import frb.axeron.manager.ui.viewmodel.ViewModelGlobal
import frb.axeron.server.PluginInstaller

class AxActivity : ComponentActivity() {

    companion object {
        const val OPEN_QUICK_SHELL = "AxManager.QUICK_SHELL"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }

    var zipUri by mutableStateOf<List<PluginInstaller>?>(emptyList())

    val shortcut by lazy {
        ShortcutInfoCompat.Builder(this, "quick-shell")
            .setShortLabel(getString(R.string.quick_shell_short))
            .setLongLabel(getString(R.string.quick_shell))
            .setDisabledMessage(getString(R.string.quick_shell_not_supported))
            .setIcon(IconCompat.createWithResource(this, R.drawable.terminal))
            .setIntent(
                Intent(this, AxActivity::class.java).apply {
                    action = OPEN_QUICK_SHELL
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
            .build()
    }

    private var intentState: Intent? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ShortcutManagerCompat.pushDynamicShortcut(this@AxActivity, shortcut)

        intentState = intent

        setContent {
            AxManagerTheme {
                MainScreen(it)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 需求②：App 被系统从后台杀掉后重新进入，若保活开关仍开启但前台服务已不在运行，
        // 则自动重启 KeepAliveService，保证「后台保活」在回到应用时自恢复。
        if (AxeronSettings.getEnableKeepAlive() && !KeepAliveService.isRunning(this)) {
            runCatching { KeepAliveService.start(this) }
                .onFailure { Log.w("AxActivity", "KeepAlive auto-restart failed", it) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentState = intent
        Log.i("AxActivity", "onNewIntent")
    }

    @Composable
    fun MainScreen(settingsViewModel: SettingsViewModel) {
        val context = LocalContext.current
        val snackBarHostState = remember { SnackbarHostState() }
        val navController = rememberNavController()
        val navigator = navController.rememberDestinationsNavigator()
        val currentDestination = navController.currentBackStackEntryAsState().value?.destination

        // 液态毛玻璃：内容层作为背板（backdrop），底栏对它做「采样 + 折射 + 磨砂」
        // （这是参考项目 Kyant0/AndroidLiquidGlass 的做法：图层录制 GraphicsLayer → drawBackdrop 采样）
        val contentBackdrop = rememberLayerBackdrop()
        // 液态玻璃开关（响应式）：监听偏好变化，设置页一拨开关这里立刻重组
        var glassEnabled by remember { mutableStateOf(LiquidGlassSettings.isEnabled(context)) }
        val isDark = isSystemInDarkTheme()
        DisposableEffect(context) {
            val listener = LiquidGlassSettings.registerListener(context) { enabled ->
                glassEnabled = enabled
            }
            onDispose { LiquidGlassSettings.unregisterListener(context, listener) }
        }
        val glassParams = remember(glassEnabled, isDark) {
            LiquidGlassParams(
                enabled = glassEnabled,
                blurRadiusDp = 24f,
                tint = if (isDark) {
                    Color(0xFF1C1B1F).copy(alpha = 0.55f)
                } else {
                    Color(0xFFFFFFFF).copy(alpha = 0.55f)
                }
            )
        }

        val activateViewModel: ActivateViewModel = viewModel<ActivateViewModel>()

        val appsViewModel: AppsViewModel = viewModel<AppsViewModel>()
        val privilegeViewModel: PrivilegeViewModel = viewModel<PrivilegeViewModel>()
        val pluginViewModel: PluginViewModel = viewModel<PluginViewModel>()

        val axeronInfo = activateViewModel.axeronInfo

        LaunchedEffect(intentState) {
            if (intentState == null) return@LaunchedEffect
            Log.i("AxActivity", "intent: $intentState")
            when (intentState!!.action) {
                OPEN_QUICK_SHELL -> {
                    if (axeronInfo.isRunning()) {
                        navigator.navigate(HomeScreenDestination()) {
                            popUpTo(NavGraphs.root) {
                                saveState = true
                            }
                        }
                        if (navController.currentDestination?.route == HomeScreenDestination.route) {
                            navigator.navigate(QuickShellScreenDestination())
                        }
                    }
                }

                Intent.ACTION_VIEW -> {
                    if (intentState!!.data?.scheme == "content") {
                        zipUri =
                            intent.data?.let { uri ->
                                arrayListOf(PluginInstaller(uri))
                            } ?: run {
                                val uris: ArrayList<Uri>? =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        intent.getParcelableArrayListExtra("uris", Uri::class.java)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableArrayListExtra("uris")
                                    }

                                uris
                                    ?.map { PluginInstaller(it) }
                                    ?.toCollection(arrayListOf())
                                    ?: arrayListOf()
                            }
                        if (!zipUri.isNullOrEmpty()) {
                            navigator.navigate(
                                FlashScreenDestination(
                                    flashIt = FlashIt.FlashPlugins(zipUri!!)
                                )
                            )
                            zipUri = null
                        }
                    }
                }
            }

            intentState = null
        }

        LaunchedEffect(axeronInfo) {
            if (axeronInfo.isRunning()) {
                pluginViewModel.fetchModuleList()
                appsViewModel.loadInstalledApps()
                privilegeViewModel.loadInstalledApps()
            }
        }

        val viewModelGlobal = remember {
            ViewModelGlobal(
                settingsViewModel = settingsViewModel,
                appsViewModel = appsViewModel,
                activateViewModel = activateViewModel,
                pluginViewModel = pluginViewModel,
                privilegeViewModel = privilegeViewModel,
            )
        }

        val showBottomBar = when (currentDestination?.route) {
            ActivateScreenDestination.route -> false // Hide for Activate
            FlashScreenDestination.route -> false // Hide for Flash
            ExecutePluginActionScreenDestination.route -> false // Hide for ExecutePluginAction
            EnablePluginScreenDestination.route -> false // Hide for EnablePlugin
            else -> true
        }

        // 【悬浮底栏的占用高度】底栏是内容层的「兄弟节点」，浮在内容之上，
        // 各页面自己那层 Scaffold 完全感知不到它（M3 里 FAB 只按 FabSpacing + contentWindowInsets 定位），
        // 于是页内悬浮入口（主页「运行指令」、模块页「加模块」等 FAB）会正好落进底栏区域：
        // 美化开启时被玻璃盖住、看着重叠却点不到；美化关闭时被不透明栏整个挡住、像消失了一样。
        // 这里实测底栏覆盖层高度，再减去页面 Scaffold 已经吃掉的系统栏底部内边距，
        // 得到「底栏在系统栏之上占的高度」并通过 LocalBottomBarInset 下发给页面里的悬浮入口。
        val density = LocalDensity.current
        val pageBottomInset = ScaffoldDefaults.contentWindowInsets
            .asPaddingValues()
            .calculateBottomPadding()
        var bottomBarOverlayHeight by remember { mutableStateOf(0.dp) }
        val bottomBarInset = (bottomBarOverlayHeight - pageBottomInset).coerceAtLeast(0.dp)
        LaunchedEffect(showBottomBar) {
            // 底栏隐藏的页面（激活页、刷入页等）不留空位，页面保持原样
            if (!showBottomBar) bottomBarOverlayHeight = 0.dp
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // ⚠️ 关键：背板（内容层）必须是底栏的「兄弟节点」，不能是祖先节点。
            // 底栏从这张录制图层里采样用户看到的内容（含自定义背景图），再按形状折射/虚化。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(contentBackdrop)
            ) {
                // 自定义背景：画在内容层最底部（默认关闭时本调用直接返回，不做任何事）。
                // 放在背板录制范围内 → 底栏玻璃能对背景图做真实的折射与磨砂采样。
                AppBackgroundLayer(LocalBackgroundImage.current)

                Scaffold(
                    contentWindowInsets = WindowInsets()
                ) { innerPadding ->
                    CompositionLocalProvider(
                        LocalSnackbarHost provides snackBarHostState,
                        LocalLiquidGlass provides glassParams,
                        // 悬浮底栏在系统栏之上占的高度：页内悬浮入口（FAB）靠它抬到底栏上方
                        LocalBottomBarInset provides bottomBarInset,
                    ) {
                        DestinationsNavHost(
                            // 悬浮底栏：内容不再被底部内边距截断，而是铺满全屏；
                            // 底部安全距离交给各页面在「滚动内容内部」自行留出（120dp），
                            // 这样底栏才是真的浮在内容之上，而不是把内容整块顶上去。
                            modifier = Modifier
                                .padding(innerPadding),
                        navGraph = NavGraphs.root,
                        navController = navController,
                        dependenciesContainerBuilder = {
                            dependency(viewModelGlobal)
                        },
                        defaultTransitions = object : NavHostAnimatedDestinationStyle() {
                            // M3 官方动效规格（StandardMotionTokens 真值）：
                            // spatial → 位移/尺寸（0.9 / 700）；effects → 透明度（1.0 / 1600）
                            private val spatial = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 700f)
                            private val effects = spring<Float>(dampingRatio = 1.0f, stiffness = 1600f)

                            private fun bottomBarIndex(route: String?): Int =
                                BottomBarDestination.entries.find { it.direction.route == route }?.ordinal ?: -1

                            // 进入：新页整幅滑入 + 淡入；旧页小幅位移 + 淡出（M3 共享轴）
                            override val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
                                get() = {
                                    val initialIndex = bottomBarIndex(initialState.destination.route)
                                    val targetIndex = bottomBarIndex(targetState.destination.route)

                                    if (initialIndex != -1 && targetIndex != -1) {
                                        // 底栏 Tab 之间：按方向横滑
                                        if (targetIndex > initialIndex) {
                                            slideInHorizontally(animationSpec = spatial) { it } +
                                                    fadeIn(animationSpec = effects)
                                        } else {
                                            slideInHorizontally(animationSpec = spatial) { -it } +
                                                    fadeIn(animationSpec = effects)
                                        }
                                    } else {
                                        // 进入子页面（详情等）：新页从右滑入
                                        slideInHorizontally(animationSpec = spatial) { it } +
                                                fadeIn(animationSpec = effects)
                                    }
                                }

                            override val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition
                                get() = {
                                    val initialIndex = bottomBarIndex(initialState.destination.route)
                                    val targetIndex = bottomBarIndex(targetState.destination.route)

                                    if (initialIndex != -1 && targetIndex != -1) {
                                        if (targetIndex > initialIndex) {
                                            slideOutHorizontally(animationSpec = spatial) { -it / 4 } +
                                                    fadeOut(animationSpec = effects)
                                        } else {
                                            slideOutHorizontally(animationSpec = spatial) { it / 4 } +
                                                    fadeOut(animationSpec = effects)
                                        }
                                    } else {
                                        slideOutHorizontally(animationSpec = spatial) { -it / 4 } +
                                                fadeOut(animationSpec = effects)
                                    }
                                }

                            // 返回：上一层页面从左侧小幅滑回 + 淡入
                            override val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
                                get() = {
                                    slideInHorizontally(animationSpec = spatial) { -it / 4 } +
                                            fadeIn(animationSpec = effects)
                                }

                            // 返回：当前页面向右滑出 + 淡出
                            override val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition
                                get() = {
                                    slideOutHorizontally(animationSpec = spatial) { it } +
                                            fadeOut(animationSpec = effects)
                                }
                        }
                    )
                }
            }
            } // 闭合：内容层 Box（背板录制范围）

            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    // 实测底栏覆盖层高度（含系统栏内边距与栏自身留白），下发给页内悬浮入口。
                    // 必须放在链首：放在 navigationBarsPadding 之后量到的是不含系统栏内边距的高度。
                    .onGloballyPositioned { coordinates ->
                        bottomBarOverlayHeight = with(density) { coordinates.size.height.toDp() }
                    }
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            ) {
                BottomBar(
                    contentBackdrop,
                    glassParams,
                    navController,
                    navigator,
                    activateViewModel.axeronInfo,
                    pluginViewModel.pluginUpdateCount
                )
            }

            // 模块授权申请 —— 全局监听（Bug 修复）：
            // 不依赖用户停留在授权页，只要 App 在前台就持续轮询 pending 目录并弹窗。
            frb.axeron.manager.ui.component.GlobalOverlayRequestHost()
        }
    }

    @Composable
    fun BottomBar(
        backdrop: Backdrop,
        glass: LiquidGlassParams,
        navController: NavHostController,
        navigator: DestinationsNavigator,
        axeronServerInfo: AxeronInfo,
        moduleUpdateCount: Int
    ) {
        // 液态毛玻璃底栏：开启时走 ui/component/LiquidGlassBottomBar.kt（真·背板采样 + 折射 + 磨砂），
        // 关闭时完全回退到原来的 Material Card 样式。
        // 注意：backdrop / glass 必须由调用方显式传入——BottomBar 位于 CompositionLocalProvider 作用域之外，
        // 若在此读 LocalLiquidGlass.current 会永远拿到默认值（enabled=false），导致开关无效。
        val barShapeFallback = RoundedCornerShape(topStart = 15.dp, topEnd = 15.dp)

        // 当前可见的 tab 列表（未运行 Axeron 时隐藏 needAxeron 项）
        val visibleDestinations = remember(axeronServerInfo.isRunning()) {
            BottomBarDestination.entries.filter {
                axeronServerInfo.isRunning() || !it.needAxeron
            }
        }
        val tabCount = visibleDestinations.size

        // 当前选中索引（动画指示器跟随的目标）
        var selectedIndex by remember { mutableStateOf(0) }

        // 【Bug 修复】选中态改用「tab 根目的地是否仍在返回栈上」判定 —— 与下方
        // Material 底栏（isRouteOnBackStackAsState）同一口径。
        //
        // 旧实现只看**栈顶路由**：进入某个 tab 的子界面后栈顶是子路由（与 tab 路由之间
        // 没有前缀关系），匹配不到任何 tab，索引于是保持旧值不动；此后用底栏切到主页、
        // 再切回该 tab 时（navigate 带 restoreState 会恢复该 tab 之前保存的子栈），
        // 栈顶依旧是那个子路由 → 索引仍停在主页，出现「页面已回到设置子界面、
        // 底栏图标却还在主页」的错位。
        //
        // 子界面压在 tab 之上时，tab 根 entry 依然留在返回栈里，因此本判定天然覆盖
        // 子界面场景，无需为每个子页面维护「归属哪个 tab」的映射表。
        val tabOnBackStack = ArrayList<Boolean>(tabCount)
        for (dest in visibleDestinations) {
            tabOnBackStack += navController.isRouteOnBackStackAsState(dest.direction).value
        }
        val backStackTabIndex = tabOnBackStack.indexOfFirst { it }

        // 关键：让 selectedIndex 跟随真实导航状态（含从别处返回、深链、回退栈变化）
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        LaunchedEffect(currentRoute, tabCount, backStackTabIndex) {
            if (backStackTabIndex >= 0) {
                // 返回栈里能找到所属 tab（含停留在该 tab 子界面的情形）
                selectedIndex = backStackTabIndex
            } else if (currentRoute != null && tabCount > 0) {
                // 兜底：按栈顶路由前缀匹配（正常 tab 页与深链场景）
                val idx = visibleDestinations.indexOfFirst { dest ->
                    val r = dest.direction.route
                    currentRoute == r || currentRoute.startsWith("$r/") || currentRoute.startsWith("$r?")
                }
                if (idx >= 0) selectedIndex = idx
            }
        }

        if (glass.enabled && tabCount > 0) {
            // 液态毛玻璃底栏（真·背板采样 + 折射 + 磨砂，实现见 ui/component/LiquidGlassBottomBar.kt）
            LiquidGlassBottomBar(
                backdrop = backdrop,
                destinations = visibleDestinations,
                selectedIndex = selectedIndex,
                moduleUpdateCount = moduleUpdateCount,
                onSelect = { index ->
                    val destination = visibleDestinations.getOrNull(index)
                    if (destination != null) {
                        // 只切换导航，selectedIndex 由上面的 LaunchedEffect 跟随真实路由更新，
                        // 保证「胶囊位置」永远等于「当前真实页面」，不会错位。
                        navigator.navigate(destination.direction) {
                            popUpTo(NavGraphs.root) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // Metric 风格：悬浮胶囊 —— 左右留边距 + 底部留间距，不贴屏幕边缘
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else if (glass.enabled) {
            // 无可见 tab（Axeron 未运行等）时回退到普通样式
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                elevation = CardDefaults.cardElevation(0.dp),
                shape = barShapeFallback
            ) {
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp
            ) {
                BottomBarDestination.entries
                    .forEach { destination ->
                        if (!axeronServerInfo.isRunning() && destination.needAxeron) return@forEach

                        val isCurrentDestOnBackStack by navController.isRouteOnBackStackAsState(
                            destination.direction
                        )
                        val label = stringResource(id = destination.labelId)
                        NavigationBarItem(
                            selected = isCurrentDestOnBackStack,
                            onClick = {
                                navigator.navigate(destination.direction) {
                                    popUpTo(NavGraphs.root) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.iconNotSelected, label) },
                            label = { Text(label) },
                            alwaysShowLabel = false
                        )
                    }
            }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                elevation = CardDefaults.cardElevation(0.dp),
                shape = barShapeFallback
            ) {
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp
            ) {
                BottomBarDestination.entries
                    .forEach { destination ->
                        if (!axeronServerInfo.isRunning() && destination.needAxeron) return@forEach

                        val isCurrentDestOnBackStack by navController.isRouteOnBackStackAsState(
                            destination.direction
                        )
                        val label = stringResource(id = destination.labelId)
                        NavigationBarItem(
                            selected = isCurrentDestOnBackStack,
                            onClick = {
                                if (isCurrentDestOnBackStack) {
                                    navigator.popBackStack(destination.direction, false)
                                }
                                navigator.navigate(destination.direction) {
                                    popUpTo(NavGraphs.root) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (destination == BottomBarDestination.Plugin && moduleUpdateCount > 0) {
                                    BadgedBox(badge = { Badge { Text(moduleUpdateCount.toString()) } }) {
                                        if (isCurrentDestOnBackStack) {
                                            Icon(
                                                destination.iconSelected,
                                                label
                                            )
                                        } else {
                                            Icon(
                                                destination.iconNotSelected,
                                                label
                                            )
                                        }
                                    }
                                } else {
                                    if (isCurrentDestOnBackStack) Icon(
                                        imageVector = destination.iconSelected,
                                        contentDescription = label
                                    ) else Icon(
                                        imageVector = destination.iconNotSelected,
                                        contentDescription = label
                                    )
                                }
                            },
                            label = {
                                Text(label)
                            },
                            alwaysShowLabel = false
                        )
                    }
            }
            }
        }
    }
}