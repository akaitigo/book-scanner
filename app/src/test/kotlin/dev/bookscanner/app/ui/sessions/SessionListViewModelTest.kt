package dev.bookscanner.app.ui.sessions

import dev.bookscanner.app.FakeScanRepository
import dev.bookscanner.app.MainDispatcherRule
import dev.bookscanner.app.TestFailure
import dev.bookscanner.core.contracts.SessionId
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionListViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeScanRepository()

    private fun viewModel() = SessionListViewModel(repository)

    @Test
    fun `lists sessions most recently updated first`() =
        runTest {
            repository.seed(title = "First")
            repository.seed(title = "Second")

            val viewModel = viewModel()
            advanceUntilIdle()

            assertContentEquals(
                listOf("Second", "First"),
                viewModel.state.value.sessions
                    .map { it.title },
            )
            assertEquals(false, viewModel.state.value.loading)
        }

    @Test
    fun `empty storage yields an empty list rather than an error`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            assertTrue(
                viewModel.state.value.sessions
                    .isEmpty(),
            )
            assertNull(viewModel.state.value.loadError)
        }

    @Test
    fun `a load failure becomes a recoverable state, not a transient message`() =
        runTest {
            repository.failNext = TestFailure("disk unreadable")

            val viewModel = viewModel()
            advanceUntilIdle()

            // Rendered as a state with a retry button; a snackbar would leave
            // an empty list that reads as "you have no scans".
            val error = assertNotNull(viewModel.state.value.loadError)
            assertTrue(error.contains("disk unreadable"), "message should name the cause: $error")
            assertNull(viewModel.state.value.errorMessage)
        }

    @Test
    fun `retrying after a load failure clears the error`() =
        runTest {
            repository.failNext = TestFailure()
            val viewModel = viewModel()
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.loadError)

            repository.seed(title = "Recovered read")
            viewModel.refresh()
            advanceUntilIdle()

            assertNull(viewModel.state.value.loadError)
            assertEquals(1, viewModel.state.value.sessions.size)
        }

    @Test
    fun `creating a session reports the new id and refreshes the list`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            var created: SessionId? = null
            viewModel.createSession("Kotlin in Action") { created = it }
            advanceUntilIdle()

            assertNotNull(created)
            assertContentEquals(
                listOf("Kotlin in Action"),
                viewModel.state.value.sessions
                    .map { it.title },
            )
        }

    @Test
    fun `a blank title falls back to a default rather than creating an unnamed scan`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.createSession("   ") { }
            advanceUntilIdle()

            assertEquals(
                SessionListViewModel.DEFAULT_TITLE,
                viewModel.state.value.sessions
                    .single()
                    .title,
            )
        }

    @Test
    fun `deleting removes the session from the list`() =
        runTest {
            val session = repository.seed(title = "Doomed")
            repository.seed(title = "Kept")
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.deleteSession(session.id)
            advanceUntilIdle()

            assertContentEquals(
                listOf("Kept"),
                viewModel.state.value.sessions
                    .map { it.title },
            )
        }

    @Test
    fun `renaming updates the list`() =
        runTest {
            val session = repository.seed(title = "Old")
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.renameSession(session.id, "New")
            advanceUntilIdle()

            assertContentEquals(
                listOf("New"),
                viewModel.state.value.sessions
                    .map { it.title },
            )
        }

    @Test
    fun `a blank rename is ignored instead of erasing the title`() =
        runTest {
            val session = repository.seed(title = "Keep me")
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.renameSession(session.id, "  ")
            advanceUntilIdle()

            assertEquals("Keep me", repository.current(session.id).title)
        }

    @Test
    fun `a failed action surfaces as a transient message and keeps the content`() =
        runTest {
            repository.seed(title = "Still here")
            val viewModel = viewModel()
            advanceUntilIdle()

            repository.failNext = TestFailure("delete refused")
            viewModel.deleteSession(SessionId("s1"))
            advanceUntilIdle()

            assertEquals("delete refused", viewModel.state.value.errorMessage)
            // The screen still has content, so this is not a load error.
            assertNull(viewModel.state.value.loadError)

            viewModel.consumeError()
            assertNull(viewModel.state.value.errorMessage)
        }
}
