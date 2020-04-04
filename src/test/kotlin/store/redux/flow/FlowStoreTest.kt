package store.redux.flow

import com.squareup.sqldelight.runtime.coroutines.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import store.redux.flow.Action.ChangeVisibility
import store.redux.flow.Action.TodoAction
import store.redux.flow.Action.TodoAction.AddTodo
import store.redux.flow.Action.TodoAction.ToggleTodo
import store.redux.update


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

@ExperimentalCoroutinesApi
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

            subscription.test {
                assertThat(expectItem()).isEqualTo(TodoAppState())

                store.dispatch(AddTodo("1"))
                assertThat(expectItem().todos).containsExactly(Todo(text = "1", done = false))

                store.dispatch(ToggleTodo(0))
                assertThat(expectItem().todos).containsExactly(Todo(text = "1", done = true))

                store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
                assertThat(expectItem().filter).isEqualTo(VisibilityFilter.DONE)

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun demoFilteredSubscribe() {
        runBlocking {

            val store = createStore(
                TodoAppState(),
                appReducer
            )

            val filterUpdates = store.updates().map { s -> s.filter }.distinctUntilChanged()

            filterUpdates.test {
                store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
                assertThat(expectItem()).isEqualTo(VisibilityFilter.DONE)

                store.dispatch(AddTodo("1"))
                store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
                expectNoEvents()

                store.dispatch(ChangeVisibility(VisibilityFilter.ALL))
                assertThat(expectItem()).isEqualTo(VisibilityFilter.ALL)

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun throwingReducerDoesNotBreakStoreAndDoesNotEmitUpdate() {
        runBlocking {
            val store = createStore(10, { a: Int, s: Int -> s / a })

            val updates = store.updates()

            updates.test {
                assertThat(expectItem()).isEqualTo(10)

                store.dispatch(0)  // division by zero
                expectNoEvents()

                store.dispatch(10)
                assertThat(expectItem()).isEqualTo(1)

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

            firstTodoUpdates.test {


                store.dispatch(AddTodo("1"))
                assertThat(expectItem().text).isEqualTo("1")

                store.dispatch(AddTodo("other"))
                expectNoEvents()

                store.dispatch(ToggleTodo(0))
                assertThat(expectItem().done).isTrue()

                cancelAndIgnoreRemainingEvents()
            }
        }
    }
}
