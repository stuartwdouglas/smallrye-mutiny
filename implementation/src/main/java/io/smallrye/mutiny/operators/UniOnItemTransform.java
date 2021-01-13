package io.smallrye.mutiny.operators;

import java.util.function.Function;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.ExecutionChain;
import io.smallrye.mutiny.helpers.ParameterValidation;
import io.smallrye.mutiny.subscription.UniSubscriber;

public class UniOnItemTransform<I, O> extends UniOperator<I, O> {

    private final Function<? super I, ? extends O> mapper;

    public UniOnItemTransform(Uni<I> source, Function<? super I, ? extends O> mapper) {
        super(ParameterValidation.nonNull(source, "source"));
        this.mapper = ParameterValidation.nonNull(mapper, "mapper");
    }

    @Override
    protected void subscribing(UniSubscriber<? super O> subscriber, ExecutionChain executionChain) {
        AbstractUni.subscribe(upstream(), new UniDelegatingSubscriber<I, O>(subscriber) {

            @Override
            public void onItem(I item) {
                O outcome;
                try {
                    outcome = mapper.apply(item);
                    // We cannot call onItem here, as if onItem would throw an exception
                    // it would be caught and onFailure would be called. This would be illegal.
                } catch (Throwable e) {
                    subscriber.onFailure(e);
                    return;
                }

                subscriber.onItem(outcome);
            }

        }, executionChain);
    }

    @Override
    protected void subscribing(UniSubscriber<? super O> subscriber) {
        subscribing(subscriber, new ExecutionChain());
    }
}
