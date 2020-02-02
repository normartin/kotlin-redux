package store.redux.java;

import io.vavr.collection.List;
import org.junit.jupiter.api.Test;
import store.redux.java.JavaStore.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static store.redux.java.JavaStore.*;

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
