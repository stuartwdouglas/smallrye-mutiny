package io.smallrye.mutiny.operators.uni.builders;

import java.util.function.Supplier;

import io.smallrye.mutiny.helpers.EmptyUniSubscription;
import io.smallrye.mutiny.operators.AbstractUni;
import io.smallrye.mutiny.subscription.UniSubscriber;

/**
 * Specialized {@link io.smallrye.mutiny.Uni} implementation for the case where items are from a supplier.
 * The supplied item can be {@code null}.
 *
 * @param <T> the type of the item
 */
public class SuppliedtemUni<T> extends AbstractUni<T> {

    private final Supplier<? extends T> supplier;

    public SuppliedtemUni(Supplier<? extends T> supplier) {
        super(null);
        this.supplier = supplier;
    }

    @Override
    protected void subscribing(UniSubscriber<? super T> subscriber) {
        // No need to track cancellation, it's done by the serialized subscriber downstream.
        subscriber.onSubscribe(EmptyUniSubscription.CANCELLED);
        try {
            T item = supplier.get();
            subscriber.onItem(item);
        } catch (RuntimeException err) {
            subscriber.onFailure(err);
        }
    }
}
