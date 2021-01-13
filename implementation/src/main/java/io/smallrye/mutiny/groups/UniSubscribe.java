package io.smallrye.mutiny.groups;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.ExecutionChain;
import io.smallrye.mutiny.helpers.ParameterValidation;
import io.smallrye.mutiny.helpers.UniCallbackSubscriber;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.operators.AbstractUni;
import io.smallrye.mutiny.operators.UniSerializedSubscriber;
import io.smallrye.mutiny.operators.UniSubscribeToCompletionStage;
import io.smallrye.mutiny.subscription.Cancellable;
import io.smallrye.mutiny.subscription.UniSubscriber;
import io.smallrye.mutiny.subscription.UniSubscription;

/**
 * Allow subscribing to a {@link Uni} to be notified of the different events coming from {@code upstream}.
 * Two kind of events can be received:
 * <ul>
 * <li>{@code item} - the item of the {@link Uni}, can be {@code null}</li>
 * <li>{@code failure} - the failure propagated by the {@link Uni}</li>
 * </ul>
 *
 * @param <T> the type of item
 */
public class UniSubscribe<T> {

    private final AbstractUni<T> upstream;
    private final ExecutionChain executionChain;

    public UniSubscribe(AbstractUni<T> upstream) {
        this.upstream = ParameterValidation.nonNull(upstream, "upstream");
        executionChain = upstream.executionChain;
    }

    /**
     * Requests the {@link Uni} to start computing the item.
     * <p>
     * This is a "factory method" and can be called multiple times, each time starting a new {@link UniSubscription}.
     * Each {@link UniSubscription} will work for only a single {@link UniSubscriber}. A {@link UniSubscriber} should
     * only subscribe once to a single {@link Uni}.
     * <p>
     * If the {@link Uni} rejects the subscription attempt or otherwise fails it will fire a {@code failure} event
     * receiving by {@link UniSubscriber#onFailure(Throwable)}.
     *
     * @param subscriber the subscriber, must not be {@code null}
     * @param <S> the type of subscriber returned
     * @return the passed subscriber
     */
    public <S extends UniSubscriber<? super T>> S withSubscriber(S subscriber) {
        UniSerializedSubscriber.subscribe(upstream, ParameterValidation.nonNull(subscriber, "subscriber"));
        return subscriber;
    }

    /**
     * Like {@link #withSubscriber(UniSubscriber)} with creating an artificial {@link UniSubscriber} calling the
     * {@code onItem} and {@code onFailure} callbacks when the events are received.
     * <p>
     * Unlike {@link #withSubscriber(UniSubscriber)}, this method returns the subscription that can be used to cancel
     * the subscription.
     *
     * @param onItemCallback callback invoked when the an item event is received, potentially called with {@code null}
     *        is received. The callback must not be {@code null}
     * @param onFailureCallback callback invoked when a failure event is received, must not be {@code null}
     * @return an object to cancel the computation
     */
    public Cancellable with(Consumer<? super T> onItemCallback, Consumer<? super Throwable> onFailureCallback) {
        UniCallbackSubscriber<T> subscriber = new UniCallbackSubscriber<>(
                ParameterValidation.nonNull(onItemCallback, "onItemCallback"),
                ParameterValidation.nonNull(onFailureCallback, "onFailureCallback"), executionChain);
        executionChain.executeAndRun(new ExecutionChain.ChainTask<Void>() {
            @Override
            public void runChainTask(Void current, ExecutionChain chain) {
                UniSerializedSubscriber.subscribe(upstream, ParameterValidation.nonNull(subscriber, "subscriber"), chain);
            }
        });
        return subscriber;

    }

    /**
     * Like {@link #withSubscriber(UniSubscriber)} with creating an artificial {@link UniSubscriber} calling the
     * {@code onItem} when the item is received.
     * <p>
     * Unlike {@link #withSubscriber(UniSubscriber)}, this method returns the subscription that can be used to cancel
     * the subscription.
     * <p>
     * Unlike {@link #with(Consumer, Consumer)}, this method does not handle failure, and the failure event is dropped.
     *
     * @param onItemCallback callback invoked when the an item event is received, potentially called with {@code null}
     *        is received. The callback must not be {@code null}
     * @return an object to cancel the computation
     */
    public Cancellable with(Consumer<? super T> onItemCallback) {

        UniCallbackSubscriber<T> subscriber = new UniCallbackSubscriber<>(
                ParameterValidation.nonNull(onItemCallback, "onItemCallback"), Infrastructure::handleDroppedException,
                executionChain);
        executionChain.executeAndRun(new ExecutionChain.ChainTask<Void>() {
            @Override
            public void runChainTask(Void current, ExecutionChain chain) {
                UniSerializedSubscriber.subscribe(upstream, ParameterValidation.nonNull(subscriber, "subscriber"), chain);
            }
        });
        return subscriber;
    }

    /**
     * Like {@link #withSubscriber(UniSubscriber)} but provides a {@link CompletableFuture} to retrieve the completed
     * item (potentially {@code null}) and allow chaining operations.
     *
     * @return a {@link CompletableFuture} to retrieve the item and chain operations on the resolved item or
     *         failure. The returned {@link CompletableFuture} can also be used to cancel the computation.
     */
    public CompletableFuture<T> asCompletionStage() {
        return UniSubscribeToCompletionStage.subscribe(upstream);
    }

}
