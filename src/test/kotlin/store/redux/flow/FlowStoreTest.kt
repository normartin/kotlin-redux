package store.redux.flow

import com.squareup.sqldelight.runtime.coroutines.expectNextItemEquals
import com.squareup.sqldelight.runtime.coroutines.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import store.redux.flow.Action.ChangeVisibility
import store.redux.flow.Action.TodoAction
import store.redux.flow.Action.TodoAction.AddTodo
import store.redux.flow.Action.TodoAction.ToggleTodo
import store.redux.update

val LOG = LoggerFactory.getLogger(RxStoreTest::class.java)

// state
data class Todo(val text: String, val done: Boolean = false)

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
val todoReducer: Reducer<TodoAction, List<Todo>> = { action, todos ->
    when (action) {
        is AddTodo -> todos + Todo(action.text)
        is ToggleTodo -> todos.update(action.index) { it.copy(done = !it.done) }
    }
}
val appReducer: Reducer<Action, TodoAppState> = { action, state ->
    when (action) {
        is TodoAction -> state.copy(todos = todoReducer(action, state.todos))
        is ChangeVisibility -> state.copy(filter = action.filter)
    }
}

class RxStoreTest {

    @Test
    fun canGetCurrentStateOfStore() {
        val store = createStore(initialState = TodoAppState(), reducer = appReducer)

        store.dispatch(AddTodo("1"))
        assertThat(store.state().todos).containsExactly(Todo(text = "1", done = false))

        store.dispatch(ToggleTodo(0))
        assertThat(store.state().todos).containsExactly(Todo(text = "1", done = true))

        store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
        assertThat(store.state().filter).isEqualTo(VisibilityFilter.DONE)
    }

    @Test
    fun canSubscribeToStoreUpdates() {
        runBlocking {
            val store = createStore(initialState = TodoAppState(), reducer = appReducer)

            val subscription: Flow<TodoAppState> = store.updates()

            subscription.test {
                assertThat(nextItem()).isEqualTo(TodoAppState())

                store.dispatch(AddTodo("1"))
                assertThat(nextItem().todos).containsExactly(Todo(text = "1", done = false))

                store.dispatch(ToggleTodo(0))
                assertThat(nextItem().todos).containsExactly(Todo(text = "1", done = true))

                store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
                assertThat(nextItem().filter).isEqualTo(VisibilityFilter.DONE)

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun canFilterOnSubTree() {
        runBlocking {
            val store = createStore(initialState = TodoAppState(), reducer = appReducer)

            val filterUpdates = store.updates().map { s -> s.filter }.distinctUntilChanged()

            filterUpdates.test {
                store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
                expectNextItemEquals(VisibilityFilter.DONE)

                store.dispatch(AddTodo("1"))
                store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
                expectNoEvents()

                store.dispatch(ChangeVisibility(VisibilityFilter.ALL))
                expectNextItemEquals(VisibilityFilter.ALL)

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun throwingReducerDoesNotBreakStoreAndDoesNotEmitUpdate() {
        runBlocking {
            val store = createStore(10, { state: Int, action: Int -> action / state })

            val updates = store.updates()

            updates.test {
                expectNextItemEquals(10)

                store.dispatch(0)  // division by zero
                expectNoEvents()

                store.dispatch(10)
                assertThat(nextItem()).isEqualTo(1)

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun canFilterOnListIndex() {
        runBlocking {
            val store = createStore(initialState = TodoAppState(), reducer = appReducer)

            val firstTodoUpdates = store.updates().filterOnTodoIndex(0)

            firstTodoUpdates.test {


                store.dispatch(AddTodo("1"))
                assertThat(nextItem().text).isEqualTo("1")

                store.dispatch(AddTodo("other"))
                expectNoEvents()

                store.dispatch(ToggleTodo(0))
                assertThat(nextItem().done).isTrue()

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun middlewareTest() {

        fun <ACTION, STATE> errorHandlerMiddleware(): MiddleWare<ACTION, STATE> = { reducer ->
            { action, state ->
                try {
                    reducer(action, state)
                } catch (e: RuntimeException) {
                    LOG.warn("Failed to apply reducer for action $action on state $state", e)
                    state
                }
            }
        }

        fun <ACTION, STATE> loggingMiddleware(): MiddleWare<ACTION, STATE> = { reducer ->
            { action, state ->
                val result = reducer(action, state)
                LOG.info("$action ==>> $result")
                result
            }
        }

        val store = createStore(
            initialState = TodoAppState(),
            reducer = appReducer,
            middleWares = listOf(errorHandlerMiddleware(), loggingMiddleware())
        )

        store.dispatch(AddTodo("make a middleware"))
        store.dispatch(ToggleTodo(0))


        assertThat(store.state().todos).containsExactly(
            Todo(
                "make a middleware",
                done = true
            )
        )
    }

    @Test
    fun doesNotSufferFromLostUpdate() {
        runBlocking(context = newFixedThreadPoolContext(8, "pool")) {
            val store = createStore(0, { sum: Int, i: Int -> sum + i })
            val numberOfUpdates = 100000

            val jobs = (1..numberOfUpdates).map {
                launch {
                    store.dispatch(1)
                }
            }

            jobs.joinAll()

            assertThat(store.state()).isEqualTo(numberOfUpdates)
        }
    }

}

fun Flow<TodoAppState>.filterOnTodoIndex(index: Int): Flow<Todo> = this
    .filter { it.todos.elementAtOrNull(index) != null }
    .map { it.todos[index] }
    .distinctUntilChanged()