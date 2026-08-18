package dev.bookscanner.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.bookscanner.app.AppContainer
import dev.bookscanner.app.ui.capture.CaptureScreen
import dev.bookscanner.app.ui.capture.CaptureViewModel
import dev.bookscanner.app.ui.editor.PageEditorScreen
import dev.bookscanner.app.ui.editor.PageEditorViewModel
import dev.bookscanner.app.ui.pages.PageListScreen
import dev.bookscanner.app.ui.pages.PageListViewModel
import dev.bookscanner.app.ui.sessions.SessionListScreen
import dev.bookscanner.app.ui.sessions.SessionListViewModel
import dev.bookscanner.core.contracts.PageId
import dev.bookscanner.core.contracts.SessionId

object Routes {
    const val SESSION_LIST = "sessions"
    const val CAPTURE = "sessions/{sessionId}/capture"
    const val PAGES = "sessions/{sessionId}/pages"
    const val EDITOR = "sessions/{sessionId}/pages/{pageId}/edit"

    fun capture(id: SessionId) = "sessions/${id.value}/capture"

    fun pages(id: SessionId) = "sessions/${id.value}/pages"

    fun editor(
        sessionId: SessionId,
        pageId: PageId,
    ) = "sessions/${sessionId.value}/pages/${pageId.value}/edit"

    const val ARG_SESSION_ID = "sessionId"
    const val ARG_PAGE_ID = "pageId"
}

@Composable
fun BookScannerNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.SESSION_LIST) {
        composable(Routes.SESSION_LIST) {
            val viewModel: SessionListViewModel =
                viewModel(factory = viewModelFactory { SessionListViewModel(container.repository) })
            SessionListScreen(
                viewModel = viewModel,
                onOpenSession = { id -> navController.navigate(Routes.pages(id)) },
                onStartCapture = { id -> navController.navigate(Routes.capture(id)) },
            )
        }

        composable(
            route = Routes.CAPTURE,
            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.StringType }),
        ) { entry ->
            val sessionId = SessionId(entry.requireArgument(Routes.ARG_SESSION_ID))
            val viewModel: CaptureViewModel =
                viewModel(
                    factory =
                        viewModelFactory {
                            CaptureViewModel(
                                sessionId = sessionId,
                                repository = container.repository,
                                ingestor = container.ingestor,
                                detector = container.detector,
                                signatureOf = container::pageSignatureOf,
                                signatureOfFile = container::pageSignatureOfFile,
                            )
                        },
                )
            CaptureScreen(
                viewModel = viewModel,
                onDone = {
                    navController.navigate(Routes.pages(sessionId)) {
                        popUpTo(Routes.SESSION_LIST)
                    }
                },
            )
        }

        composable(
            route = Routes.PAGES,
            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.StringType }),
        ) { entry ->
            val sessionId = SessionId(entry.requireArgument(Routes.ARG_SESSION_ID))
            val viewModel: PageListViewModel =
                viewModel(
                    factory =
                        viewModelFactory {
                            PageListViewModel(sessionId, container.repository, container.exporter)
                        },
                )
            PageListScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onAddPages = { navController.navigate(Routes.capture(sessionId)) },
                onEditPage = { pageId -> navController.navigate(Routes.editor(sessionId, pageId)) },
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments =
                listOf(
                    navArgument(Routes.ARG_SESSION_ID) { type = NavType.StringType },
                    navArgument(Routes.ARG_PAGE_ID) { type = NavType.StringType },
                ),
        ) { entry ->
            val sessionId = SessionId(entry.requireArgument(Routes.ARG_SESSION_ID))
            val pageId = PageId(entry.requireArgument(Routes.ARG_PAGE_ID))
            val viewModel: PageEditorViewModel =
                viewModel(
                    factory =
                        viewModelFactory {
                            PageEditorViewModel(
                                sessionId = sessionId,
                                pageId = pageId,
                                repository = container.repository,
                                detectPage = { file, geometry -> container.pageDetection.detect(file, geometry) },
                            )
                        },
                )
            PageEditorScreen(
                viewModel = viewModel,
                onClose = { navController.popBackStack() },
            )
        }
    }
}

private fun androidx.navigation.NavBackStackEntry.requireArgument(key: String): String =
    requireNotNull(arguments?.getString(key)) { "Missing navigation argument '$key'" }

/** Minimal factory so hand-wired ViewModels can take constructor arguments. */
private inline fun <reified T : ViewModel> viewModelFactory(crossinline create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
    }
