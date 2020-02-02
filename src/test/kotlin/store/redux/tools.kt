package store.redux

fun <E> List<E>.update(index: Int, f: ((E) -> E)): List<E> = this.mapIndexed { i, e ->
    if (i == index) f(e) else e
}

