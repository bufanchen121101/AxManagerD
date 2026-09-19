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
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.BoxWithConstraints
import frb.axeron.manager.ui.theme.LocalLiquidGlass
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import frb.axeron.manager.ui.component.LiquidGlassState
import frb.axeron.manager.ui.component.liquidGlass
import frb.axeron.manager.ui.component.liquefiable
import frb.axeron.manager.ui.component.rememberLiquidGlassState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
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

        // 液态玻璃：内容作为 hazeSource，底栏对它做 hazeEffect（只模糊栏背后内容）
        val hazeState = rememberHazeState()
        // 真·液态玻璃（AGSL RuntimeShader）共享状态：内容区登记为采样源，底栏玻璃采样它做折射/色散
        val liquidGlassState = rememberLiquidGlassState()
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

        Box(modifier = Modifier.fillMaxSize()) {
            // ⚠️ 关键：liquefiable（采样源）只挂在「内容层」——
            // 它是底栏（liquidGlass 效果节点）的「兄弟节点」，而非祖先。
            // 若把 liquefiable 挂在外层 Box 上，底栏会成为它的后代，
            // 触发「祖先采样后代 / 后代采样祖先」的无限递归 → SIGSEGV 闪退。
            // 内容层用 fillMaxSize 铺满全屏（覆盖底栏区域），底栏玻璃才能采到像素。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .liquefiable(liquidGlassState)
            ) {
                Scaffold(
                    contentWindowInsets = WindowInsets()
                ) { innerPadding ->
                    CompositionLocalProvider(
                        LocalSnackbarHost provides snackBarHostState,
                        LocalLiquidGlass provides glassParams,
                    ) {
                        DestinationsNavHost(
                            modifier = Modifier
                                .padding(innerPadding)
                                .padding(bottom = if (showBottomBar) 96.dp else 0.dp)
                                .hazeSource(hazeState),
                        navGraph = NavGraphs.root,
                        navController = navController,
                        dependenciesContainerBuilder = {
                            dependency(viewModelGlobal)
                        },
                        defaultTransitions = object : NavHostAnimatedDestinationStyle() {
                            override val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
                                get() = {
                                    val initialState = initialState.destination.route
                                    val targetState = targetState.destination.route

                                    // Cari indeks destinasi di BottomBar
                                    val initialIndex = BottomBarDestination.entries.find { it.direction.route == initialState }?.ordinal ?: -1
                                    val targetIndex = BottomBarDestination.entries.find { it.direction.route == targetState }?.ordinal ?: -1

                                    if (initialIndex != -1 && targetIndex != -1) {
                                        // Jika pindah antar tab BottomBar
                                        if (targetIndex > initialIndex) {
                                            // Geser ke kiri (masuk dari kanan)
                                            slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn()
                                        } else {
                                            // Geser ke kanan (masuk dari kiri)
                                            slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(300)) + fadeIn()
                                        }
                                    } else {
                                        // Animasi default jika bukan antar tab (misal masuk ke detail)
                                        fadeIn(animationSpec = tween(300))
                                    }
                                }

                            override val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition
                                get() = {
                                    val initialState = initialState.destination.route
                                    val targetState = targetState.destination.route

                                    val initialIndex = BottomBarDestination.entries.find { it.direction.route == initialState }?.ordinal ?: -1
                                    val targetIndex = BottomBarDestination.entries.find { it.direction.route == targetState }?.ordinal ?: -1

                                    if (initialIndex != -1 && targetIndex != -1) {
                                        if (targetIndex > initialIndex) {
                                            // Keluar ke kiri
                                            slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(300)) + fadeOut()
                                        } else {
                                            // Keluar ke kanan
                                            slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut()
                                        }
                                    } else {
                                        fadeOut(animationSpec = tween(300))
                                    }
                                }
                        }
                    )
                }
            }
            } // 闭合：内容层 Box（liquefiable 采样源）

            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            ) {
                BottomBar(
                    hazeState,
                    liquidGlassState,
                    glassParams,
                    navController,
                    navigator,
                    activateViewModel.axeronInfo,
                    pluginViewModel.pluginUpdateCount
                )
            }
        }
    }

    @Composable
    fun BottomBar(
        hazeState: HazeState,
        liquidGlassState: LiquidGlassState,
        glass: LiquidGlassParams,
        navController: NavHostController,
        navigator: DestinationsNavigator,
        axeronServerInfo: AxeronInfo,
        moduleUpdateCount: Int
    ) {
        // 液态玻璃底栏：开启时用玻璃面板（模糊只作用于栏背后内容，前景图标/文字始终清晰）；
        // 关闭时完全回退到原来的 Material Card 样式。
        // 注意：glass 必须由调用方显式传入——BottomBar 位于 CompositionLocalProvider 作用域之外，
        // 若在此读 LocalLiquidGlass.current 会永远拿到默认值（enabled=false），导致开关无效。
        //
        // 视觉参考 Metric 1.5.0 的 Liquid Glass 底栏：
        //   - 整条栏 = 悬浮玻璃胶囊
        //   - 选中项 = 一块「独立的液态玻璃指示器」，切换页面时用 spring 动画滑动跟随
        val barShape = RoundedCornerShape(28.dp)
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

        // 关键：让 selectedIndex 跟随真实导航状态（含从别处返回、深链、回退栈变化）
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        LaunchedEffect(currentRoute, tabCount) {
            if (currentRoute != null && tabCount > 0) {
                val idx = visibleDestinations.indexOfFirst { dest ->
                    val r = dest.direction.route
                    currentRoute == r || currentRoute.startsWith("$r/") || currentRoute.startsWith("$r?")
                }
                if (idx >= 0) selectedIndex = idx
            }
        }

        if (glass.enabled && tabCount > 0) {
            // 用 BoxWithConstraints 拿到栏可用宽度，按 tab 数均分，算出指示器位置
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    // Metric 风格：悬浮胶囊 —— 左右留边距 + 底部留间距，不贴屏幕边缘
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // 每个 tab 的宽度（栏内按 tab 数均分）
                val tabWidth = maxWidth / tabCount
                // 指示器尺寸：Metric 的水滴光斑是「椭圆形」，比 tab 略窄，高度略小于栏高
                val indicatorWidth = tabWidth * 0.68f
                val indicatorHeight = 46.dp

                // 指示器水平偏移：随 selectedIndex 用 spring 平滑滑动 —— 这就是「框跟着移动」
                // dampingRatio 调低 + stiffness 适中 → 有「液体粘连拉伸」的弹性感
                val indicatorOffset by animateDpAsState(
                    targetValue = tabWidth * selectedIndex + (tabWidth - indicatorWidth) / 2,
                    animationSpec = spring(
                        dampingRatio = 0.62f,   // 明显回弹，模拟液体表面张力
                        stiffness = 320f
                    ),
                    label = "liquidGlassIndicatorOffset"
                )

                // 液体粘连：切换瞬间指示器先「横向拉伸」，落定后回弹成圆形。
                // 用「目标位置是否已到达」判断（spring 收敛后 offset == target）。
                val targetOffset = tabWidth * selectedIndex + (tabWidth - indicatorWidth) / 2
                val isMoving = kotlin.math.abs((indicatorOffset - targetOffset).value) > 1.5f
                val stretchWidth by animateDpAsState(
                    targetValue = if (isMoving) indicatorWidth * 1.18f else indicatorWidth,
                    animationSpec = spring(
                        dampingRatio = 0.55f,
                        stiffness = 300f
                    ),
                    label = "liquidGlassStretch"
                )

                // 外层：整条栏的液态玻璃胶囊
                // 用 AGSL RuntimeShader 采样内容区像素 → 真折射 + 边缘光 + 色散（Android 13+）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(barShape)
                        // 兜底底色：极淡白（低版本 Android / 采样失败时仍有可见轮廓）。
                        // 注意：不要再叠加白色描边——shader 本身已绘制边缘光，
                        // 叠加描边会在 shader 失效时显示成「一个白色的圈」。
                        .background(Color.White.copy(alpha = 0.03f))
                        .liquidGlass(liquidGlassState) {
                            // —— 官方推荐参数（FletchMcKee/liquid 的 LiquidBottomNavigationBar）——
                            frost = 48.dp                        // 磨砂：玻璃背后内容虚化（官方默认值）
                            shape = barShape                     // 胶囊形
                            refraction = 0.25f                   // 折射扭曲
                            curve = 0.5f                         // 曲率
                            dispersion = 0.05f                   // 边缘轻微色散
                            edge = 0.10f                         // 边缘白色发光亮线
                            saturation = 1.2f                    // 略提饱和，玻璃更「透亮」
                            contrast = 1.15f                     // 略提对比，轮廓更清晰
                            // 官方用半透明白：玻璃本体是「白玻璃」而非染色玻璃，
                            // 中性色不会把背景染蓝。
                            tint = Color.White.copy(alpha = 0.75f)
                        }
                ) {
                    // ---- 第 1 层：滑动跟随的液态玻璃水滴光斑 ----
                    // 水滴状椭圆 + 独立玻璃层：更强的折射/色散，让它像一颗悬浮的水珠
                    Box(
                        modifier = Modifier
                            .offset(
                                x = indicatorOffset - (stretchWidth - indicatorWidth) / 2,
                                y = (64.dp - indicatorHeight) / 2
                            )
                            .width(stretchWidth)
                            .height(indicatorHeight)
                            // 兜底底色：低版本 / 采样失败时仍可见。
                            // 用极淡的白色而非主题色 —— 避免「偏蓝」
                            .background(
                                color = Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(percent = 50)
                            )
                            .clip(RoundedCornerShape(percent = 50))
                            .liquidGlass(liquidGlassState) {
                                // —— 官方推荐（LiquidBottomNavigationBar 的选中项）——
                                frost = 12.dp                             // 磨砂
                                shape = RoundedCornerShape(percent = 50)  // 水滴/椭圆
                                refraction = 0.22f                        // 透镜放大
                                curve = 0.8f                              // 曲率（水滴感）
                                dispersion = 0f                           // 选中项不做色散
                                edge = 0f                                 // 不加边缘光
                                saturation = 1.0f
                                contrast = 1.0f
                                // 官方注释明确写：保持透镜中性，避免借到图标色调（防「偏蓝」）。
                                tint = Color(0xFFE5E6EA).copy(alpha = 0.7f)
                            }
                    )

                    // ---- 第 2 层：图标 + 文字（始终清晰，绘制在玻璃之上）----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        visibleDestinations.forEachIndexed { index, destination ->
                            val isSelected = index == selectedIndex
                            val label = stringResource(id = destination.labelId)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        // 只切换导航，selectedIndex 由上面的 LaunchedEffect 跟随真实路由更新，
                                        // 保证「指示器位置」永远等于「当前真实页面」，不会错位。
                                        navigator.navigate(destination.direction) {
                                            popUpTo(NavGraphs.root) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    // 选中态：深色实心；未选中态：中灰（保证在毛玻璃+光斑上的对比度）
                                    val iconTint = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    if (destination == BottomBarDestination.Plugin && moduleUpdateCount > 0) {
                                        BadgedBox(badge = { Badge { Text(moduleUpdateCount.toString()) } }) {
                                            Icon(
                                                imageVector = if (isSelected) destination.iconSelected
                                                else destination.iconNotSelected,
                                                contentDescription = label,
                                                tint = iconTint
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = if (isSelected) destination.iconSelected
                                            else destination.iconNotSelected,
                                            contentDescription = label,
                                            tint = iconTint
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
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