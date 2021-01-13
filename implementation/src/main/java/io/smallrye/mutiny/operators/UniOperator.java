package io.smallrye.mutiny.operators;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.ExecutionChain;

public abstract class UniOperator<I, O> extends AbstractUni<O> {

    private final Uni<? extends I> upstream;

    public UniOperator(Uni<? extends I> upstream) {
        // NOTE: upstream can be null. It's null when creating a "source".
        super();
        this.upstream = upstream;
    }

    public UniOperator(ExecutionChain executionChain, Uni<? extends I> upstream) {
        // NOTE: upstream can be null. It's null when creating a "source".
        super(executionChain);
        this.upstream = upstream;
    }

    public Uni<? extends I> upstream() {
        return upstream;
    }

}
