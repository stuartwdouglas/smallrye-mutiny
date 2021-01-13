package io.smallrye.mutiny.operators;

import static io.smallrye.mutiny.helpers.EmptyUniSubscription.CANCELLED;

import io.smallrye.mutiny.subscription.UniSubscriber;

public class UniNever<T> extends AbstractUni<T> {
    public static final UniNever<Object> INSTANCE = new UniNever<>();

    private UniNever() {
        // avoid direct instantiation.
        super(null);
    }

    @Override
    protected void subscribing(UniSubscriber<? super T> subscriber) {
        subscriber.onSubscribe(CANCELLED);
    }
}
