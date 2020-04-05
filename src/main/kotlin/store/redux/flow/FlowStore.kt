package store.redux.flow

import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.slf4j.LoggerFactory


private val LOG = LoggerFactory.getLogger(Store::class.java)

typealias Reducer<ACTION, STATE> = (ACTION, STATE) -> STATE

typealias MiddleWare<ACTION, STATE> = (Reducer<ACTION, STATE>) -> (Reducer<ACTION, STATE>)

class Store<ACTION, STATE>(initial: STATE, private val reducer: Reducer<ACTION, STATE>) {

    private val updates: ConflatedBroadcastChannel<STATE> = ConflatedBroadcastChannel(initial)

    fun updates(): Flow<STATE> = updates.asFlow()

    fun state(): STATE = updates.value

    fun dispatch(e: ACTION) = try {
        // no need to check for result as it is always true
        updates.offer(reducer(e, updates.value))
    } catch (e: RuntimeException) {
        LOG.error("Failed to process $e on ${updates.value}", e)
    }
}

fun <ACTION, STATE> createStore(
    initialState: STATE,
    reducer: Reducer<ACTION, STATE>,
    middleWares: List<MiddleWare<ACTION, STATE>> = emptyList()
): Store<ACTION, STATE> {
    val combinedMiddleware = middleWares.fold({ it: Reducer<ACTION, STATE> -> it }, { acc, m -> acc.combine(m) })
    return Store(initialState, combinedMiddleware(reducer))
}

fun <ACTION, STATE> MiddleWare<ACTION, STATE>.combine(other: MiddleWare<ACTION, STATE>): MiddleWare<ACTION, STATE> =
    { reducer -> other(this(reducer)) }
