package store.redux

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import store.redux.Action.ChangeVisibility
import store.redux.Action.TodoAction
import store.redux.Action.TodoAction.AddTodo
import store.redux.Action.TodoAction.ToggleTodo
import store.redux.rx.MiddleWare
import store.redux.rx.Reducer
import store.redux.rx.createStore

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

class RxStoreTest {
    val LOG = LoggerFactory.getLogger(RxStoreTest::class.java)

    @Test
    fun demo() {

        val store = createStore(initialState = TodoAppState(), reducer = appReducer)

        store.updates.subscribe { s: TodoAppState -> println("State changed $s") }

        store.dispatch(AddTodo("1"))

        await().untilAsserted {
            assertThat(store.state().todos).containsExactly(Todo(text = "1", done = false))
        }

        store.dispatch(ToggleTodo(0))

        await().untilAsserted {
            assertThat(store.state().todos).containsExactly(Todo(text = "1", done = true))
        }

        store.dispatch(ChangeVisibility(VisibilityFilter.DONE))

        await().untilAsserted {
            assertThat(store.state().filter).isEqualTo(VisibilityFilter.DONE)
        }
    }

    @Test
    fun demoWithSubscribe() {

        val store = createStore(TodoAppState(), appReducer)

        StepVerifier.create(store.updates)
            .assertNext {
                // initial state
                assertThat(it).isEqualTo(TodoAppState())
            }.then {
                store.dispatch(AddTodo("1"))
            }.assertNext {
                assertThat(it.todos).containsExactly(Todo(text = "1", done = false))
            }
            .then {
                store.dispatch(ToggleTodo(0))
            }.assertNext {
                assertThat(it.todos).containsExactly(Todo(text = "1", done = true))
            }
            .then {
                store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
            }.assertNext {
                assertThat(it.filter).isEqualTo(VisibilityFilter.DONE)
            }
            .thenCancel().verify()
    }


    @Test
    fun demoFilteredSubscribe() {

        val store = createStore(TodoAppState(), appReducer)

        val filterUpdated = store.updates.map { s -> s.filter }.distinctUntilChanged()

        StepVerifier.create(filterUpdated)
            .assertNext {
                // initial state
                assertThat(it).isEqualTo(VisibilityFilter.ALL)
            }
            .then {
                store.dispatch(AddTodo("1")) // should not trigger update
                store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
            }
            .assertNext {
                assertThat(it).isEqualTo(VisibilityFilter.DONE)
            }
            .thenCancel().verify()
    }

    fun <ACTION, STATE> errorHandlerMiddleware(): MiddleWare<ACTION, STATE> = { reducer ->
        { action, state ->
            try {
                reducer(action, state)
            } catch (e: RuntimeException) {
                LOG.error("Failed to apply reducer for action $action on state $state", e)
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

    @Test
    fun middlewareTest() {

        val store = createStore(
            initialState = TodoAppState(),
            reducer = appReducer,
            middleWares = listOf(errorHandlerMiddleware(), loggingMiddleware())
        )

        store.dispatch(AddTodo("make a middleware"))
        store.dispatch(ToggleTodo(0))

        await().untilAsserted {
            assertThat(store.state().todos).containsExactly(Todo("make a middleware", done = true))
        }
    }

    @Test
    fun throwingReducerDoesNotBreakStoreAndDoesNotEmitUpdate() {

        val store = createStore(10, { a: Int, s: Int -> s / a })

        StepVerifier.create(store.updates)
            .expectNext(10)
            .then {
                store.dispatch(0)  // division by zero
                store.dispatch(10)
            }
            .expectNext(1)
            .thenCancel()
            .verify()
    }

    fun Flux<TodoAppState>.filterOnTodoIndex(index: Int): Flux<Todo> = this
        .filter { it.todos.elementAtOrNull(index) != null }
        .map { it.todos[index] }
        .distinctUntilChanged()

    @Test
    fun canFilter() {

        val store = createStore(TodoAppState(), appReducer)

        val firstTodoUpdates = store.updates.filterOnTodoIndex(0)

        StepVerifier.create(firstTodoUpdates)
            .then { store.dispatch(AddTodo("1")) }
            .expectNextMatches { it.text == "1" }
            .then {
                store.dispatch(AddTodo("other"))
                store.dispatch(ToggleTodo(0))
            }
            .assertNext {
                assertThat(it.text).isEqualTo("1")
                assertThat(it.done).isTrue()
            }
            .thenCancel()
            .verify()
    }
}


