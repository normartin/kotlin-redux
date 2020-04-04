
# Redux with Kotlin and Java

Implementation of Redux [basic tutorial](https://redux.js.org/basics/basic-tutorial) in Kotlin and Java (without UI).
Uses [Kotlin's Flow](https://kotlinlang.org/docs/reference/coroutines/flow.html) for publishing updates.

## Kotlin

````kotlin
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
````

[source](src/test/kotlin/store/redux/flow/FlowStoreTest.kt)

[Store implementation](src/main/kotlin/store/redux/flow/FlowStore.kt)

## Kotlin with RX (Reactor) 

[RxStore implementation](src/main/kotlin/store/redux/rx/RxStore.kt)

[Tests / usage](src/test/kotlin/store/redux/rx/RxStoreTest.kt)


## Java

Uses [Vavr](https://www.vavr.io/) for immutable collections.

````java
class JavaStoreTest {

    // state
    static class Todo {
        final String text;
        final boolean done;

        Todo(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }

    enum VisibilityFilter {ALL, DONE, TODO}

    static class Todos {
        final List<Todo> todos;
        final VisibilityFilter filter;

        Todos(List<Todo> todos, VisibilityFilter filter) {
            this.todos = todos;
            this.filter = filter;
        }

        public static Todos initial() {
            return new Todos(List.empty(), VisibilityFilter.ALL);
        }
    }

    // actions
    interface Action {
    }

    static class AddTodo implements Action {
        final String text;

        AddTodo(String text) {
            this.text = text;
        }
    }

    static class ToggleTodo implements Action {
        final int index;

        ToggleTodo(int index) {
            this.index = index;
        }
    }

    static class ChangeVisibility implements Action {
        final VisibilityFilter filter;

        ChangeVisibility(VisibilityFilter filter) {
            this.filter = filter;
        }
    }

    // reducers
    Reducer<Action, Todos> addTodoReducer = reduceOn(AddTodo.class,
            (action, state) -> new Todos(state.todos.append(new Todo(action.text, false)), state.filter)
    );

    Reducer<Action, Todos> toggleReducer = reduceOn(ToggleTodo.class,
            (action, state) -> new Todos(state.todos.update(action.index, t -> new Todo(t.text, !t.done)), state.filter)
    );

    Reducer<Action, Todos> changeVisibilityReducer = reduceOn(ChangeVisibility.class,
            (action, state) -> new Todos(state.todos, action.filter)
    );

    Reducer<Action, Todos> reducer = combine(addTodoReducer, toggleReducer, changeVisibilityReducer);

    @Test
    void demo() {
        JavaStore<Action, Todos> store = createStore(Todos.initial(), reducer);

        store.updates.subscribe(s -> System.out.println("State changed " + s));

        store.dispatch(new AddTodo("1"));

        await().untilAsserted(() -> assertThat(store.state().todos).extracting(t -> t.text).containsExactly("1"));

        store.dispatch(new ToggleTodo(0));

        await().untilAsserted(() -> assertThat(store.state().todos).extracting(t -> t.done).containsExactly(true));

        store.dispatch(new ChangeVisibility(VisibilityFilter.DONE));

        await().untilAsserted(() -> assertThat(store.state().filter).isEqualTo(VisibilityFilter.DONE));
    }
}
````

[source](src/test/java/store/redux/java/JavaStoreTest.java)

[Store implementation](src/main/java/store/redux/java/JavaStore.java)
