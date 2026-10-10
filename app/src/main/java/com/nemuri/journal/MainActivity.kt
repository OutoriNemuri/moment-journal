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
import com.nemuri.journal.data.db.MomentDatabase
import com.nemuri.journal.data.prefs.JournalPrefs
import com.nemuri.journal.data.repo.MomentRepository
import com.nemuri.journal.ui.screen.BackupOnboardingScreen
import com.nemuri.journal.ui.screen.BackupSettingsScreen
import com.nemuri.journal.ui.screen.BackupViewModel
import com.nemuri.journal.ui.screen.EditorScreen
import com.nemuri.journal.ui.screen.EditorViewModel
import com.nemuri.journal.ui.screen.HomeScreen
import com.nemuri.journal.ui.screen.HomeViewModel
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

private object Routes {
    const val HOME = "home"
    const val EDITOR = "editor/{entryId}"
    const val BACKUP = "backup"
    fun editor(id: Long) = "editor/$id"
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

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(
                key = "home",
                factory = HomeViewModel.factory(repository, prefs),
            )
            HomeScreen(
                viewModel = vm,
                backupViewModel = backupVm,
                onOpenEntry = { id -> navController.navigate(Routes.editor(id)) },
                onOpenBackupSettings = { navController.navigate(Routes.BACKUP) },
            )
        }

        composable(Routes.BACKUP) {
            BackupSettingsScreen(
                viewModel = backupVm,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("entryId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getLong("entryId") ?: return@composable
            val vm: EditorViewModel = viewModel(
                key = "editor-$entryId",
                factory = editorFactory(repository, entryId),
            )
            EditorScreen(viewModel = vm, onBack = { navController.popBackStack() })
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
