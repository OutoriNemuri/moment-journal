package com.nemuri.journal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nemuri.journal.backup.BackupScheduler
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.nemuri.journal.R
import com.nemuri.journal.data.db.Book
import com.nemuri.journal.ui.screen.DeleteBookDialog
import com.nemuri.journal.ui.screen.NameInputDialog
import com.nemuri.journal.ui.screen.Sidebar
import com.nemuri.journal.ui.screen.EditorPlaceholderScreen
import kotlinx.coroutines.launch
import com.nemuri.journal.data.db.MomentDatabase
import com.nemuri.journal.data.prefs.JournalPrefs
import com.nemuri.journal.data.repo.MomentRepository
import com.nemuri.journal.ui.screen.BackupOnboardingScreen
import com.nemuri.journal.ui.screen.BackupSettingsScreen
import com.nemuri.journal.ui.screen.BackupViewModel
import com.nemuri.journal.ui.screen.EditorScreen
import com.nemuri.journal.ui.screen.EditorViewModel
import com.nemuri.journal.ui.screen.HomeViewModel
import com.nemuri.journal.ui.screen.SearchScreen
import com.nemuri.journal.ui.screen.SearchViewModel
import com.nemuri.journal.ui.theme.MomentJournalTheme

class MainActivity : ComponentActivity() {

    private val repository: MomentRepository by lazy {
        MomentRepository(MomentDatabase.get(applicationContext).dao())
    }
    private val prefs: JournalPrefs by lazy { JournalPrefs(applicationContext) }
    private val scheduler: BackupScheduler by lazy { BackupScheduler(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 冷启动完成后触发备份。未配置目录时本方法内部直接返回，
        // 不会产生任何副作用。
        scheduler.onColdStart()

        setContent {
            MomentJournalTheme {
                // applicationContext 只能在 Activity 作用域内取得，
                // 顶层 @Composable 拿不到它，所以在这里传进去。
                JournalApp(
                    appContext = applicationContext,
                    repository = repository,
                    prefs = prefs,
                    scheduler = scheduler,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 会话结束时收口。真正的写盘由 WorkManager 在后台完成，不阻塞主线程。
        scheduler.onBackground()
    }
}

/**
 * 路由。
 *
 * `EDITOR` 是**根目标**，`entryId` 允许缺省：缺省即占位状态
 * （尚未打开任何日记）。0.2.1 之前这里还有一个 `HOME` 列表页，
 * 它与侧栏目录展示同一批内容，因此取消。
 *
 * 切换日记**不新增路由**，而是改写当前路由的参数 —— 它是「换一篇」
 * 而不是「进一层」，否则用户按返回会一层层退回看过的日记。
 */
private object Routes {
    const val EDITOR = "editor?entryId={entryId}"
    const val BACKUP = "backup"
    const val SEARCH = "search/{entryId}/{bookId}/{scope}"

    const val ARG_ENTRY_ID = "entryId"

    /** [id] 为 null 时进入占位状态。 */
    fun editor(id: Long?): String = "editor?entryId=${id ?: NO_ENTRY}"

    fun search(entryId: Long, bookId: Long, scope: String) =
        "search/$entryId/$bookId/$scope"

    /** 占位状态的路由参数。用 -1 而非 0：Room 自增主键从 1 起，0 会与有效值混淆。 */
    const val NO_ENTRY = -1L
}

@Composable
private fun JournalApp(
    appContext: android.content.Context,
    repository: MomentRepository,
    prefs: JournalPrefs,
    scheduler: BackupScheduler,
) {
    val backupVm: BackupViewModel = viewModel(
        key = "backup",
        factory = BackupViewModel.factory(appContext, repository, prefs, scheduler),
    )
    val onboardingVisible by backupVm.onboardingVisible.collectAsStateWithLifecycle()

    // 首启引导盖在整个应用之上：这是唯一能保证用户看见备份入口的时机。
    if (onboardingVisible) {
        BackupOnboardingScreen(viewModel = backupVm, onFinished = {})
        return
    }

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.editor(null),
    ) {
        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument(Routes.ARG_ENTRY_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NO_ENTRY
                },
            ),
        ) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getLong(Routes.ARG_ENTRY_ID)
                ?: Routes.NO_ENTRY

            // 唯一的日记本状态源：顶栏标题、侧栏、新建日记都要用它。
            val homeVm: HomeViewModel = viewModel(
                key = "home",
                factory = HomeViewModel.factory(repository, prefs),
            )
            val currentBook by homeVm.currentBook.collectAsStateWithLifecycle()
            val canCreate by homeVm.canCreateEntry.collectAsStateWithLifecycle()
            val entries by homeVm.entries.collectAsStateWithLifecycle()
            val books by homeVm.books.collectAsStateWithLifecycle()

            // 侧栏里的两个对话框状态。放在这一层而不是侧栏内部：
            // 侧栏只是内容，弹窗归属调用方，关闭后侧栏才不会被一起销毁。
            var showNewBookDialog by remember { mutableStateOf(false) }
            var pendingDeleteBook by remember { mutableStateOf<Book?>(null) }

            /**
             * 切换当前日记。
             *
             * 改写当前路由而不是压栈：popUpTo 到自身并设 launchSingleTop，
             * 使返回栈里始终只有一个 EDITOR 条目。
             */
            val switchEntry: (Long?) -> Unit = { id ->
                navController.navigate(Routes.editor(id)) {
                    popUpTo(Routes.EDITOR) { inclusive = true }
                    launchSingleTop = true
                }
            }

            // 侧栏。
            //
            // 手机（含横屏）用 ModalNavigationDrawer：从左侧划出、覆盖在内容之上。
            // 这正是所需形态，不是权宜之计。平板常驻并排是后续分支，
            // 届时只是把外层容器换掉，侧栏内容函数不变。
            val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val openSidebar: () -> Unit = { scope.launch { drawerState.open() } }

            val screen: @Composable () -> Unit = {
            if (entryId == Routes.NO_ENTRY) {
                EditorPlaceholderScreen(
                    bookName = currentBook?.name,
                    canCreate = canCreate,
                    onCreateEntry = {
                        // 新建后立即打开它，而不是留在占位状态等用户再点一次
                        homeVm.createEntry { newId -> switchEntry(newId) }
                    },
                    onOpenSidebar = openSidebar,
                    onOpenBackupSettings = { navController.navigate(Routes.BACKUP) },
                )
            } else {
                val vm: EditorViewModel = viewModel(
                    key = "editor-$entryId",
                    factory = editorFactory(repository, entryId),
                )
                // 必须收集为状态：该值在日记加载完成后才就绪，
                // 直接读属性不会触发重组，查找范围就会一直拿到 -1。
                val entryBookId by vm.bookId.collectAsStateWithLifecycle()
                EditorScreen(
                    viewModel = vm,
                    // 关闭即回到占位状态：改写路由而不是 popBackStack，
                    // 因为编辑器就是根，返回栈里没有「上一层列表」可退。
                    onCloseEntry = { switchEntry(null) },
                    onOpenSidebar = openSidebar,
                    onOpenSearch = { picked ->
                        // 带上当前上下文：正在编辑哪一篇、当前在哪个本。
                        // 查找页据此决定「本篇」与「本日记本」的范围。
                        navController.navigate(
                            Routes.search(entryId, entryBookId, picked.name)
                        )
                    },
                    onOpenBackupSettings = { navController.navigate(Routes.BACKUP) },
                )
            }
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    Sidebar(
                        books = books,
                        currentBookId = currentBook?.id,
                        entries = entries,
                        onOpenEntry = { id -> switchEntry(id); scope.launch { drawerState.close() } },
                        onSelectBook = { homeVm.selectBook(it) },
                        onCreateBook = { showNewBookDialog = true },
                        onDeleteBookRequest = { pendingDeleteBook = it },
                    )
                },
                content = { screen() },
            )

            if (showNewBookDialog) {
                NameInputDialog(
                    title = stringResource(R.string.book_new_title),
                    label = stringResource(R.string.book_name_label),
                    onDismiss = { showNewBookDialog = false },
                    onConfirm = { name ->
                        homeVm.createBook(name)
                        showNewBookDialog = false
                    },
                )
            }

            pendingDeleteBook?.let { book ->
                DeleteBookDialog(
                    book = book,
                    viewModel = homeVm,
                    onDismiss = { pendingDeleteBook = null },
                )
            }
        }

        composable(Routes.BACKUP) {
            BackupSettingsScreen(
                viewModel = backupVm,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.SEARCH,
            arguments = listOf(
                navArgument("entryId") { type = NavType.LongType },
                navArgument("bookId") { type = NavType.LongType },
                navArgument("scope") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getLong("entryId") ?: -1L
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: -1L
            val scopeName = backStackEntry.arguments?.getString("scope").orEmpty()
            val vm: SearchViewModel = viewModel(
                key = "search-$entryId",
                factory = SearchViewModel.factory(repository),
            )
            // prepare 只在首次组合时执行，避免每次重组都重置查询状态
            androidx.compose.runtime.LaunchedEffect(entryId, bookId) {
                vm.prepare(entryId, bookId)
                val scope = SearchViewModel.Scope.entries.firstOrNull { it.name == scopeName }
                if (scope != null) vm.onScopeChange(scope)
            }
            SearchScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenEntry = { id -> navController.navigate(Routes.editor(id)) },
            )
        }
    }
}

private fun editorFactory(repository: MomentRepository, entryId: Long): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            // entryId 来自导航参数，这里是它进入 ViewModel 的唯一通道。
            EditorViewModel(repo = repository, entryId = entryId)
        }
    }
