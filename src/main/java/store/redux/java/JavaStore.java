package store.redux.java;

import io.vavr.collection.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.UnicastProcessor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiFunction;

class JavaStore<EVENT, STATE> {

    private final UnicastProcessor<EVENT> emitter = UnicastProcessor.create(new ConcurrentLinkedQueue<>());
    public final Flux<STATE> updates;

    private JavaStore(STATE initial, Reducer<EVENT, STATE> f) {
        Scheduler scheduler = Schedulers.single();
        this.updates = emitter
                .scan(initial, (oldState, event) -> {
                    try {
                        return f.apply(event, oldState);
                    } catch (RuntimeException exception) {
                        System.err.println("Failed to handle event: " + exception.getMessage());
                        return oldState;
                    }
                })
                .cache(1)
                .subscribeOn(scheduler);
    }

    public static <EVENT, STATE> JavaStore<EVENT, STATE> createStore(STATE initial, Reducer<EVENT, STATE> reducer) {
        return new JavaStore<>(initial, reducer);
    }

    public STATE state() {
        return updates.blockFirst();
    }

    public void dispatch(EVENT e) {
        emitter.onNext(e);
    }

    @FunctionalInterface
    interface Reducer<EVENT, STATE> extends BiFunction<EVENT, STATE, STATE> {

        @Override
        STATE apply(EVENT event, STATE state);

        default Reducer<EVENT, STATE> combine(Reducer<EVENT, STATE> other) {
            return (e, s) -> apply(e, other.apply(e, s));
        }
    }

    @SuppressWarnings("unchecked")
    static <EVENT, STATE, E extends EVENT> Reducer<EVENT, STATE> reduceOn(Class<E> clazz, Reducer<E, STATE> f) {
        return (e, s) -> {
            if (clazz.isInstance(e)) {
                return f.apply((E) e, s);
            } else {
                return s;
            }
        };
    }

    @SafeVarargs
    public static <EVENT, STATE> Reducer<EVENT, STATE> combine(Reducer<EVENT, STATE>... reducers) {
        return List.of(reducers).fold((e, s) -> s, Reducer::combine);
    }
}


