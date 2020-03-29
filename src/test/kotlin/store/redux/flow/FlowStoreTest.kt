package store.redux.flow

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import store.redux.flow.Action.ChangeVisibility
import store.redux.flow.Action.TodoAction.AddTodo
import store.redux.flow.Action.TodoAction.ToggleTodo
import store.redux.update
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration


// state
data class Todo(
    val text: String,
    val done: Boolean = false
)

enum class VisibilityFilter { ALL, DONE, TODO }

data class TodoAppState(
    val todos: List<Todo> = emptyList(),
    val filter: VisibilityFilter = VisibilityFilter.ALL
)

// actions
sealed class Action {
    sealed class TodoAction : Action() {
        data class AddTodo(val text: String) : TodoAction()
        data class ToggleTodo(val index: Int) : TodoAction()
    }

    data class ChangeVisibility(val filter: VisibilityFilter) : Action()
}

// reducers
val todoReducer: Reducer<Action.TodoAction, List<Todo>> = { action, todos ->
    when (action) {
        is AddTodo -> todos + Todo(action.text)
        is ToggleTodo -> todos.update(action.index) { it.copy(done = !it.done) }
    }
}
val appReducer: Reducer<Action, TodoAppState> = { action, state ->
    when (action) {
        is Action.TodoAction -> state.copy(todos = todoReducer(action, state.todos))
        is ChangeVisibility -> state.copy(filter = action.filter)
    }
}

@ExperimentalTime
class RxStoreTest {
    val LOG = LoggerFactory.getLogger(RxStoreTest::class.java)

    @Test
    fun demo() {
        runBlocking {
            val store = createStore(initialState = TodoAppState(), reducer = appReducer)

            store.dispatch(AddTodo("1"))
            assertThat(store.state().todos).containsExactly(Todo(text = "1", done = false))

            store.dispatch(ToggleTodo(0))
            assertThat(store.state().todos).containsExactly(Todo(text = "1", done = true))

            store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
            assertThat(store.state().filter).isEqualTo(VisibilityFilter.DONE)
        }
    }

    @Test
    fun demoWithSubscribe() {
        runBlocking {
            val store = createStore(
                TodoAppState(),
                appReducer
            )

            val subscription: Flow<TodoAppState> = store.updates()

            assertThat(subscription.first()).isEqualTo(TodoAppState())

            store.dispatch(AddTodo("1"))
            assertThat(subscription.first().todos).containsExactly(Todo(text = "1", done = false))

            store.dispatch(ToggleTodo(0))
            assertThat(subscription.first().todos).containsExactly(Todo(text = "1", done = true))

            store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
            assertThat(subscription.first().filter).isEqualTo(VisibilityFilter.DONE)
        }

    }

    @Test
    fun demoFilteredSubscribe() {
        runBlocking {

            val store = createStore(
                TodoAppState(),
                appReducer
            )

            val updates: MutableList<VisibilityFilter> = mutableListOf()

            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                store.updates().map { s -> s.filter }.distinctUntilChanged().collect {
                    updates.add(it)
                }
            }

            store.dispatch(AddTodo("1")) // should not trigger update
            store.dispatch(ChangeVisibility(VisibilityFilter.DONE))

            retry {
                assertThat(updates).containsExactly(VisibilityFilter.ALL, VisibilityFilter.DONE)
            }

            job.cancel()
        }
    }

    @Test
    fun throwingReducerDoesNotBreakStoreAndDoesNotEmitUpdate() {
        runBlocking {
            val store = createStore(10, { a: Int, s: Int -> s / a })

            val updates = store.updates()

            assertThat(updates.first()).isEqualTo(10)

            store.dispatch(0)  // division by zero
            store.dispatch(10)
            assertThat(updates.first()).isEqualTo(1)
        }
    }

    fun Flow<TodoAppState>.filterOnTodoIndex(index: Int): Flow<Todo> = this
        .filter { it.todos.elementAtOrNull(index) != null }
        .map { it.todos[index] }
        .distinctUntilChanged()

    @Test
    fun canFilter() {
        runBlocking {
            val store = createStore(
                TodoAppState(),
                appReducer
            )

            val firstTodoUpdates = store.updates().filterOnTodoIndex(0)

            store.dispatch(AddTodo("1"))
            assertThat(firstTodoUpdates.first().text).isEqualTo("1")

            store.dispatch(AddTodo("other"))
            store.dispatch(ToggleTodo(0))

            assertThat(firstTodoUpdates.first().text).isEqualTo("1")
            assertThat(firstTodoUpdates.first().done).isTrue()
        }
    }
}

@ExperimentalTime
tailrec suspend fun retry(times: Int = 3, wait: Duration = 1.toDuration(TimeUnit.SECONDS), f: suspend () -> Unit) {
    if (times < 1) {
        throw RuntimeException("retry failed")
    }
    try {
        return f()
    } catch (e: AssertionError) {

    }
    delay(wait)
    return retry(times - 1, wait, f)
}
