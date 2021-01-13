package io.smallrye.mutiny.operators.uni.builders;

import static io.smallrye.mutiny.helpers.EmptyUniSubscription.propagateFailureEvent;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import io.smallrye.mutiny.helpers.ExecutionChain;
import io.smallrye.mutiny.helpers.InternalUniSubscriber;
import io.smallrye.mutiny.helpers.ParameterValidation;
import io.smallrye.mutiny.operators.AbstractUni;
import io.smallrye.mutiny.subscription.UniSubscriber;

public class UniCreateFromCompletionStage<T> extends AbstractUni<T> {
    private final Supplier<? extends CompletionStage<? extends T>> supplier;

    public UniCreateFromCompletionStage(Supplier<? extends CompletionStage<? extends T>> supplier) {
        this.supplier = supplier; // Already checked
    }

    public UniCreateFromCompletionStage(Supplier<? extends CompletionStage<? extends T>> supplier,
            ExecutionChain executionChain) {
        super(executionChain);
        this.supplier = ParameterValidation.nonNull(supplier, "supplier");
    }

    static <O> void forwardFromCompletionStage(CompletionStage<? extends O> stage,
            UniSubscriber<? super O> subscriber, ExecutionChain executionChain) {
        subscriber.onSubscribe(() -> stage.toCompletableFuture().cancel(false));
        stage.whenComplete((res, fail) -> {
            executionChain.executeAndRun(new ExecutionChain.ChainTask<Void>() {
                @Override
                public void runChainTask(Void contextual, ExecutionChain chain) {
                    if (subscriber instanceof InternalUniSubscriber) {
                        InternalUniSubscriber intsub = (InternalUniSubscriber) subscriber;
                        if (fail != null) {
                            if (fail instanceof CompletionException) {
                                intsub.onFailure(fail.getCause(), executionChain);
                            } else {
                                intsub.onFailure(fail, executionChain);
                            }
                        } else {
                            intsub.onItem(res, executionChain);
                        }
                    } else {
                        if (fail != null) {
                            if (fail instanceof CompletionException) {
                                subscriber.onFailure(fail.getCause());
                            } else {
                                subscriber.onFailure(fail);
                            }
                        } else {
                            subscriber.onItem(res);
                        }
                    }
                }
            });
        });
    }

    @Override
    protected void subscribing(UniSubscriber<? super T> subscriber) {
        CompletionStage<? extends T> stage;
        try {
            stage = supplier.get();
        } catch (Throwable e) {
            propagateFailureEvent(subscriber, e);
            return;
        }

        if (stage == null) {
            propagateFailureEvent(subscriber, new NullPointerException("The produced CompletionStage is `null`"));
            return;
        }

        forwardFromCompletionStage(stage, subscriber, executionChain);
    }
}
