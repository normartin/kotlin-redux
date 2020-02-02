
# Redux with Kotlin

Implementation of Redux [basic tutorial](https://redux.js.org/basics/basic-tutorial) in Kotlin (without UI).

````kotlin
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
}
````

[source](src/test/kotlin/store/redux/ReduxTest.kt)

[Store implementation](src/main/kotlin/store/redux/Store.kt)

