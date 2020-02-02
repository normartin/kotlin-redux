package store.redux

import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.UnicastProcessor
import reactor.core.scheduler.Schedulers
import java.util.concurrent.ConcurrentLinkedQueue

private val LOG = LoggerFactory.getLogger(Store::class.java)

typealias Reducer<ACTION, STATE> = (ACTION, STATE) -> STATE

typealias MiddleWare<ACTION, STATE> = (Reducer<ACTION, STATE>) -> (Reducer<ACTION, STATE>)

class Store<ACTION, STATE>(initial: STATE, private val reducer: Reducer<ACTION, STATE>) {
    private val emitter = UnicastProcessor.create(ConcurrentLinkedQueue<ACTION>())
    private val scheduler = Schedulers.single()

    val flux: Flux<STATE> = emitter
        .scan(initial, { oldState, event ->
            val newState = try {
                reducer(event, oldState)
            } catch (exception: RuntimeException) {
                LOG.error("Failed to handle event $event", exception.message)
                oldState
            }
            newState
        })
        .cache(1)
        .subscribeOn(scheduler)

    fun state(): STATE = flux.blockFirst()!!

    fun dispatch(e: ACTION) {
        emitter.onNext(e)
    }
}

fun <ACTION, STATE> createStore(
    initialState: STATE,
    reducer: Reducer<ACTION, STATE>,
    middleWare: MiddleWare<ACTION, STATE> = { it }
): Store<ACTION, STATE> {
    return Store(initialState, middleWare(reducer))
}
