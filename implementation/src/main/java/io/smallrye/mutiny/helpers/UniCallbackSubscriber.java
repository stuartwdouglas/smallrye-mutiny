package io.smallrye.mutiny.helpers;

import static io.smallrye.mutiny.helpers.EmptyUniSubscription.CANCELLED;
import static io.smallrye.mutiny.helpers.ParameterValidation.nonNull;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.reactivestreams.Subscription;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.UniSubscriber;
import io.smallrye.mutiny.subscription.UniSubscription;

/**
 * Implementation of a {@link UniSubscriber} based on callbacks.
 * This implementation also implement {@link UniSubscription} to expose the {@link #cancel()} method.
 *
 * @param <T> the type of item received by this subscriber
 */
public class UniCallbackSubscriber<T> implements UniSubscriber<T>, UniSubscription {

    private final AtomicReference<UniSubscription> subscription = new AtomicReference<>();
    private final Consumer<? super T> onResultCallback;
    private final Consumer<? super Throwable> onFailureCallback;
    private final ExecutionChain executionChain;

    /**
     * Creates a {@link UniSubscriber} consuming the item and failure of a
     * {@link Uni}.
     * 
     * @param onResultCallback callback invoked on item event, must not be {@code null}
     * @param onFailureCallback callback invoked on failure event, must not be {@code null}
     * @param executionChain
     */
    public UniCallbackSubscriber(Consumer<? super T> onResultCallback,
            Consumer<? super Throwable> onFailureCallback, ExecutionChain executionChain) {
        this.onResultCallback = nonNull(onResultCallback, "onResultCallback");
        this.onFailureCallback = nonNull(onFailureCallback, "onFailureCallback");
        this.executionChain = nonNull(executionChain, "executionChain");
    }

    @Override
    public final void onSubscribe(UniSubscription sub) {
        if (!subscription.compareAndSet(null, sub)) {
            // cancelling this second subscription
            // because we already add a subscription (maybe CANCELLED)
            executionChain.execute(new ExecutionChain.ChainTask<Void>() {
                @Override
                public void runChainTask(Void ctx, ExecutionChain chain) {
                    sub.cancel();
                }
            });
        }
    }

    @Override
    public final void onFailure(Throwable t) {
        UniSubscription sub = subscription.getAndSet(CANCELLED);
        if (sub == CANCELLED) {
            // Already cancelled, do nothing
            return;
        }
        executionChain.execute(new ExecutionChain.ChainTask<Void>() {
            @Override
            public void runChainTask(Void v, ExecutionChain chain) {
                onFailureCallback.accept(t);
            }
        });
    }

    @Override
    public final void onItem(T x) {
        Subscription sub = subscription.getAndSet(CANCELLED);
        if (sub == CANCELLED) {
            // Already cancelled, do nothing
            return;
        }
        executionChain.execute(new ExecutionChain.ChainTask<Void>() {
            @Override
            public void runChainTask(Void obj, ExecutionChain chain) {
                try {
                    onResultCallback.accept(x);
                } catch (Throwable t) {
                    Infrastructure.handleDroppedException(t);
                }
            }
        });
    }

    @Override
    public void cancel() {
        Subscription sub = subscription.getAndSet(CANCELLED);
        if (sub != null) {
            executionChain.execute(new ExecutionChain.ChainTask<Void>() {
                @Override
                public void runChainTask(Void obj, ExecutionChain chain) {
                    sub.cancel();
                }
            });
        }
    }
}
