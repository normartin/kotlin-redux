package store.redux

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.UnicastProcessor
import reactor.core.scheduler.Schedulers
import store.redux.Action.*
import java.util.concurrent.ConcurrentLinkedQueue



data class Todo(val text: String, val done: Boolean = false)

enum class VisibilityFilter { ALL, DONE, TODO }

data class State(val todos: List<Todo> = emptyList(), val filter: VisibilityFilter = VisibilityFilter.ALL)

sealed class Action {
    data class AddTodo(val text: String) : Action()
    data class ToggleTodo(val index: Int) : Action()
    data class ChangeVisibility(val filter: VisibilityFilter) : Action()
}

class ReduxTest {

    @Test
    fun test1() {

        val reducer: Reducer<Action, State> = { action, state ->
            when (action) {
                is ChangeVisibility -> state.copy(filter = action.filter)
                is AddTodo -> state.copy(todos = state.todos + Todo(text = action.text))
                is ToggleTodo -> state.copy(todos = state.todos.update(action.index) { t: Todo ->
                    t.copy(done = !t.done)
                })
            }
        }

        val store = createStore(State(), reducer)

        store.flux.subscribe { s: State -> println("State changed $s") }

        store.dispatch(AddTodo("1"))
        await().untilAsserted {
            assertThat(store.state().todos.map { it.text }).containsExactly("1")
        }

        store.dispatch(ToggleTodo(0))
        await().untilAsserted {
            assertThat(store.state().todos.map { it.done }).containsExactly(true)
        }

        store.dispatch(ChangeVisibility(VisibilityFilter.DONE))
        await().untilAsserted {
            assertThat(store.state().filter).isEqualTo(VisibilityFilter.DONE)
        }
    }

}

typealias Reducer<ACTION, STATE> = (ACTION, STATE) -> STATE

class Store<EVENT, STATE> constructor(initial: STATE, private val f: Reducer<EVENT, STATE>) {
    private val emitter = UnicastProcessor.create(ConcurrentLinkedQueue<EVENT>())
    private val scheduler = Schedulers.single()
    val flux: Flux<STATE> = emitter.scan(initial, { oldState, event ->
        try {
            f(event, oldState)
        } catch (exception: RuntimeException) {
            System.err.println("Failed to handle event: " + exception.message)
            oldState
        }
    }).cache(1).subscribeOn(scheduler)

    fun state(): STATE = flux.blockFirst()!!

    fun dispatch(e: EVENT) {
        emitter.onNext(e)
    }
}

fun <EVENT, STATE> createStore(initial: STATE, reducer: Reducer<EVENT, STATE>): Store<EVENT, STATE> {
    return Store(initial, reducer)
}
