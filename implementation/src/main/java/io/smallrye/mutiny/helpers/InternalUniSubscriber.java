package io.smallrye.mutiny.helpers;

import java.util.concurrent.Executor;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniSubscriber;
import io.smallrye.mutiny.subscription.UniSubscription;

public interface InternalUniSubscriber<T> extends UniSubscriber<T> {

    /**
     * Event handler called once the subscribed {@link Uni} has taken into account the subscription. The {@link Uni}
     * have triggered the computation of the item.
     *
     * <strong>IMPORTANT:</strong> {@link #onItem(Object)} and {@link #onFailure(Throwable)} would not be called
     * before the invocation of this method.
     *
     * <ul>
     * <li>Executor: Operate on no particular executor, except if {@link Uni#runSubscriptionOn(Executor)} has been
     * called</li>
     * <li>Exception: Throwing an exception cancels the subscription, {@link #onItem(Object)} and
     * {@link #onFailure(Throwable)} won't be called</li>
     * </ul>
     *
     * @param subscription the subscription allowing to cancel the computation.
     */
    void onSubscribe(UniSubscription subscription, ExecutionChain executionChain);

    /**
     * Event handler called once the item has been computed by the subscribed {@link Uni}.
     *
     * <strong>IMPORTANT:</strong> this method will be only called once per subscription. If
     * {@link #onFailure(Throwable)} is called, this method won't be called.
     *
     * <ul>
     * <li>Executor: Operate on no particular executor, except if {@link Uni#emitOn} has been called</li>
     * <li>Exception: Throwing an exception cancels the subscription.
     * </ul>
     *
     * @param item the item, may be {@code null}.
     */
    void onItem(T item, ExecutionChain executionChain);

    /**
     * Called if the computation of the item by the subscriber {@link Uni} failed.
     *
     * <strong>IMPORTANT:</strong> this method will be only called once per subscription. If
     * {@link #onItem(Object)} is called, this method won't be called.
     *
     * <ul>
     * <li>Executor: Operate on no particular executor, except if {@link Uni#emitOn} has been called</li>
     * <li>Exception: Throwing an exception cancels the subscription.
     * </ul>
     *
     * @param failure the failure, cannot be {@code null}.
     */
    void onFailure(Throwable failure, ExecutionChain executionChain);
}
