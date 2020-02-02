package store.redux

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import store.redux.Action.*

// state
data class Todo(val text: String, val done: Boolean = false)
enum class VisibilityFilter { ALL, DONE, TODO }
data class TodoAppState(val todos: List<Todo> = emptyList(), val filter: VisibilityFilter = VisibilityFilter.ALL)

// actions
sealed class Action {
    data class AddTodo(val text: String) : Action()
    data class ToggleTodo(val index: Int) : Action()
    data class ChangeVisibility(val filter: VisibilityFilter) : Action()
}

// reducer
val todoAppReducer: Reducer<Action, TodoAppState> = { action, state ->
    when (action) {
        is ChangeVisibility -> state.copy(filter = action.filter)
        is AddTodo -> state.copy(todos = state.todos + Todo(text = action.text))
        is ToggleTodo -> state.copy(todos = state.todos.update(action.index) { t: Todo ->
            t.copy(done = !t.done)
        })
    }
}

class ReduxTest {
    val LOG = LoggerFactory.getLogger(ReduxTest::class.java)

    @Test
    fun demo() {

        val store = createStore(initialState = TodoAppState(), reducer = todoAppReducer)

        store.flux.subscribe { s: TodoAppState -> println("State changed $s") }

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

        val store = createStore(TodoAppState(), todoAppReducer)

        StepVerifier.create(store.flux)
            .assertNext {
                // initial state
                assertThat(it).isEqualTo(TodoAppState())
            }
            .then {
                store.dispatch(AddTodo("1"))
            }
            .assertNext {
                assertThat(it.todos).containsExactly(Todo(text = "1", done = false))
            }.then {
                store.dispatch(ToggleTodo(0))
            }.assertNext {
                assertThat(it.todos).containsExactly(Todo(text = "1", done = true))
            }.then {
                store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
            }.assertNext {
                assertThat(it.filter).isEqualTo(VisibilityFilter.DONE)
            }
            .thenCancel().verify()
    }

    @Test
    fun middlewareTest() {
        fun <ACTION, STATE> errorHandlerAndLogger(): MiddleWare<ACTION, STATE> = { reducer ->
            { action, state ->
                try {
                    val result = reducer(action, state)
                    LOG.info("$action -> $result")
                    result
                } catch (e: RuntimeException) {
                    LOG.error("Failed to apply reducer for action $action on state $state", e)
                    state
                }
            }
        }

        val store = createStore(TodoAppState(), todoAppReducer, errorHandlerAndLogger())

        store.dispatch(AddTodo("make a middleware"))
        store.dispatch(ToggleTodo(0))

        await().untilAsserted {
            assertThat(store.state().todos).containsExactly(Todo("make a middleware", done = true))
        }
    }

    @Test
    fun throwingReducerDoesNotBreakStore() {

        val store = createStore(10, { a: Int, s: Int -> s / a })

        StepVerifier.create(store.flux)
            .expectNext(10)
            .then { store.dispatch(0) }
            .expectNext(10)
            .then { store.dispatch(10) }
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
        LOG.info("start")

        val store = createStore(TodoAppState(), todoAppReducer)

        val todoOne = store.flux.filterOnTodoIndex(0)

        StepVerifier.create(todoOne)
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


