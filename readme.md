
# Redux with Kotlin

Implementation of Redux [basic tutorial](https://redux.js.org/basics/basic-tutorial) in Kotlin (without UI).
Uses [Reactor](https://projectreactor.io/) for publishing updates.

````kotlin
// state
data class Todo(val text: String, val done: Boolean = false)
enum class VisibilityFilter { ALL, DONE, TODO }
data class TodoAppState(val todos: List<Todo> = emptyList(), val filter: VisibilityFilter = VisibilityFilter.ALL)

// actions
sealed class Action {
    sealed class TodoAction : Action() {
        data class AddTodo(val text: String) : TodoAction()
        data class ToggleTodo(val index: Int) : TodoAction()
    }

    data class ChangeVisibility(val filter: VisibilityFilter) : Action()
}

// reducer
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

class ReduxTest {
    val LOG = LoggerFactory.getLogger(ReduxTest::class.java)

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
}
````

[source](src/test/kotlin/store/redux/ReduxTest.kt)

[Store implementation](src/main/kotlin/store/redux/Store.kt)

