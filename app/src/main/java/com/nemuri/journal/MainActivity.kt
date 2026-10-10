package com.nemuri.journal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import com.nemuri.journal.data.repo.MomentRepository
import com.nemuri.journal.ui.screen.EditorScreen
import com.nemuri.journal.ui.screen.EditorViewModel
import com.nemuri.journal.ui.screen.HomeScreen
import com.nemuri.journal.ui.screen.HomeViewModel
import com.nemuri.journal.ui.theme.MomentJournalTheme

class MainActivity : ComponentActivity() {

    private val repository: MomentRepository by lazy {
        MomentRepository(MomentDatabase.get(applicationContext).dao())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 冷启动完成后触发备份。这是最可靠的触发点：
        // 用户每次打开应用都可能换机/丢机，此时备份最便宜也最有价值。
        BackupScheduler.onColdStart(applicationContext)

        setContent {
            MomentJournalTheme {
                JournalApp(repository)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 会话结束时收口。真正的写库由 WorkManager 在后台完成，不阻塞主线程。
        BackupScheduler.onBackground(applicationContext)
    }
}

private object Routes {
    const val HOME = "home"
    const val EDITOR = "editor/{entryId}"
    fun editor(id: Long) = "editor/$id"
}

@Composable
private fun JournalApp(repository: MomentRepository) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(
                key = "home",
                factory = HomeViewModel.factory(repository),
            )
            // 新建日记在进入编辑页之前就已经落库，
            // 因此编辑页永远处理一个真实存在的 entryId，不需要"草稿态"分支。
            HomeScreen(
                viewModel = vm,
                onOpenEntry = { id -> navController.navigate(Routes.editor(id)) },
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
            EditorViewModel(
                repo = repository,
                savedStateHandle = androidx.lifecycle.SavedStateHandle(
                    mapOf(EditorViewModel.ARG_ENTRY_ID to entryId)
                ),
            )
        }
    }
