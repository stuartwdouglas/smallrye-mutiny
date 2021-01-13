package io.smallrye.mutiny.operators;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.smallrye.mutiny.CompositeException;
import io.smallrye.mutiny.helpers.EmptyUniSubscription;
import io.smallrye.mutiny.helpers.ExecutionChain;
import io.smallrye.mutiny.helpers.InternalUniSubscriber;
import io.smallrye.mutiny.helpers.ParameterValidation;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.UniSubscriber;
import io.smallrye.mutiny.subscription.UniSubscription;

/**
 * An implementation of {@link UniSubscriber} and {@link UniSubscription} making sure event handlers are only called once.
 */
public class UniSerializedSubscriber<T> implements InternalUniSubscriber<T>, UniSubscription {

    private static final int INIT = 0;
    /**
     * Got a downstream subscriber.
     */
    private static final int SUBSCRIBED = 1;

    /**
     * Got a subscription from upstream.
     */
    private static final int HAS_SUBSCRIPTION = 2;

    /**
     * Got a failure or item from upstream
     */
    private static final int DONE = 3;
    public static final ExecutionChain.ChainTask<UniSerializedSubscriber> SUBSCRIBE_TASK = new ExecutionChain.ChainTask<UniSerializedSubscriber>() {
        @Override
        public void runChainTask(UniSerializedSubscriber contextual, ExecutionChain chain) {
            contextual.subscribe();
        }
    };

    private final AtomicInteger state = new AtomicInteger(INIT);
    private final AbstractUni<T> upstream;
    private final UniSubscriber<? super T> downstream;

    private volatile UniSubscription subscription;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ExecutionChain chain;

    UniSerializedSubscriber(AbstractUni<T> upstream, UniSubscriber<? super T> subscriber, ExecutionChain chain) {
        this.upstream = ParameterValidation.nonNull(upstream, "source");
        this.downstream = ParameterValidation.nonNull(subscriber, "subscriber` must not be `null`");
        this.chain = chain;
    }

    public static <T> void subscribe(AbstractUni<T> source, UniSubscriber<? super T> subscriber) {
        UniSubscriber<? super T> actual = Infrastructure.onUniSubscription(source, subscriber, source.executionChain);
        UniSerializedSubscriber<T> wrapped = new UniSerializedSubscriber<>(source, actual, source.executionChain);
        wrapped.subscribe();
    }

    public static <T> void subscribe(AbstractUni<T> source, UniSubscriber<? super T> subscriber, ExecutionChain chain) {
        if (chain == null) {
            subscribe(source, subscriber);
            return;
        }
        UniSubscriber<? super T> actual = Infrastructure.onUniSubscription(source, subscriber, chain);
        UniSerializedSubscriber<T> wrapped = new UniSerializedSubscriber<>(source, actual, chain);
        chain.execute(SUBSCRIBE_TASK, wrapped);
    }

    private void subscribe() {
        if (state.compareAndSet(INIT, SUBSCRIBED)) {
            upstream.subscribing(this, chain);
        }
    }

    @Override
    public void onSubscribe(UniSubscription subscription) {
        ParameterValidation.nonNull(subscription, "subscription");

        if (state.compareAndSet(SUBSCRIBED, HAS_SUBSCRIPTION)) {
            this.subscription = subscription;
            this.downstream.onSubscribe(this);
        } else if (state.get() == DONE) {
            Throwable collected = failure.getAndSet(null);
            if (collected != null) {
                this.downstream.onSubscribe(this);
                this.downstream.onFailure(collected);
            }
        } else {
            EmptyUniSubscription.propagateFailureEvent(this.downstream,
                    new IllegalStateException(
                            "Invalid transition, expected to be in the SUBSCRIBED state but was in " + state));
        }
    }

    @Override
    public void onItem(T item) {
        if (state.compareAndSet(SUBSCRIBED, DONE)) {
            failure.set(new IllegalStateException(
                    "Invalid transition, expected to be in the HAS_SUBSCRIPTION states but was in SUBSCRIBED and received onItem("
                            + item + ")"));
        } else if (state.compareAndSet(HAS_SUBSCRIPTION, DONE)) {
            try {
                downstream.onItem(item);
            } catch (Throwable e) {
                Infrastructure.handleDroppedException(e);
                throw e; // Rethrow in case of synchronous emission
            }
        }
    }

    @Override
    public void onFailure(Throwable throwable) {
        if (state.compareAndSet(SUBSCRIBED, DONE)) {
            failure.set(throwable);
        } else if (state.compareAndSet(HAS_SUBSCRIPTION, DONE)) {
            try {
                downstream.onFailure(throwable);
            } catch (Throwable e) {
                Infrastructure.handleDroppedException(new CompositeException(throwable, e));
                throw e; // Rethrow in case of synchronous emission
            }
        } else {
            Infrastructure.handleDroppedException(throwable);
        }
    }

    @Override
    public void cancel() {
        if (state.compareAndSet(HAS_SUBSCRIPTION, DONE)) {
            while (subscription == null) {
                // We are in the middle of a race condition with onSubscribe()
            }
            if (subscription != null) { // May have been cancelled already by another thread.
                subscription.cancel();
            }
        } else {
            state.set(DONE);
        }
    }

    @Override
    public void onSubscribe(UniSubscription subscription, ExecutionChain executionChain) {
        if (executionChain != null && executionChain == chain) {
            executionChain.execute(new ExecutionChain.ChainTask<Void>() {
                @Override
                public void runChainTask(Void contextual, ExecutionChain chain) {
                    onSubscribe(subscription);
                }
            });
        } else {
            onSubscribe(subscription);
        }
    }

    @Override
    public void onItem(T item, ExecutionChain executionChain) {
        if (executionChain != null && executionChain == chain) {
            executionChain.execute(new ExecutionChain.ChainTask<Void>() {
                @Override
                public void runChainTask(Void contextual, ExecutionChain chain) {
                    onItem(item);
                }
            });
        } else {
            onItem(item);
        }
    }

    @Override
    public void onFailure(Throwable failure, ExecutionChain executionChain) {
        if (executionChain != null && executionChain == chain) {
            executionChain.execute(new ExecutionChain.ChainTask<Void>() {
                @Override
                public void runChainTask(Void contextual, ExecutionChain chain) {
                    onFailure(failure);
                }
            });
        } else {
            onFailure(failure);
        }
    }
}
