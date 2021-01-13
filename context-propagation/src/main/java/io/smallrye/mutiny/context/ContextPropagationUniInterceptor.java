package io.smallrye.mutiny.context;

import java.util.concurrent.Executor;

import org.eclipse.microprofile.context.ThreadContext;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.ExecutionChain;
import io.smallrye.mutiny.helpers.InternalUniSubscriber;
import io.smallrye.mutiny.infrastructure.UniInterceptor;
import io.smallrye.mutiny.operators.AbstractUni;
import io.smallrye.mutiny.subscription.UniSubscriber;
import io.smallrye.mutiny.subscription.UniSubscription;

/**
 * Provides context propagation to Uni types.
 * Subclasses need to override this to provide the Context Propagation ThreadContext.
 */
public abstract class ContextPropagationUniInterceptor implements UniInterceptor {

    static final String KEY = ContextPropagationUniInterceptor.class.getName();

    @Override
    public <T> UniSubscriber<? super T> onSubscription(Uni<T> instance, UniSubscriber<? super T> subscriber,
            ExecutionChain executionChain) {
        Executor executor;
        ExecutionChain.CurrentExecutionContext ctx = null;
        //if we are running under the same execution context avoid capturing the state again
        if (executionChain != null) {
            ctx = executionChain.currentExecutionContext();
            if (ctx != null) {
                executor = (Executor) ctx.get(KEY);
                if (executor == null) {
                    ctx.put(KEY, executor = getThreadContext().currentContextExecutor());
                }
            } else {
                executor = getThreadContext().currentContextExecutor();
            }
        } else {
            executor = getThreadContext().currentContextExecutor();
        }
        return new CtxUniSubscriber<>(executor, subscriber, executionChain, ctx);
    }

    @Override
    public <T> Uni<T> onUniCreation(Uni<T> uni, ExecutionChain executionChain) {
        Executor executor;
        ExecutionChain.CurrentExecutionContext ctx = null;
        //if we are running under the same execution context avoid capturing the state again
        if (executionChain != null) {
            ctx = executionChain.currentExecutionContext();
            if (ctx != null) {
                executor = (Executor) ctx.get(KEY);
                if (executor == null) {
                    ctx.put(KEY, executor = getThreadContext().currentContextExecutor());
                }
            } else {
                executor = getThreadContext().currentContextExecutor();
            }
        } else {
            executor = getThreadContext().currentContextExecutor();
        }
        Executor exec = executor;
        ExecutionChain.CurrentExecutionContext captured = ctx;
        return new AbstractUni<T>(executionChain) {
            @Override
            protected void subscribing(UniSubscriber<? super T> subscriber) {
                exec.execute(() -> AbstractUni.subscribe(uni, subscriber));
            }

            @Override
            protected void subscribing(UniSubscriber<? super T> subscriber, ExecutionChain cur) {
                if (cur != null && captured != null) {
                    if (cur.currentExecutionContext() == captured) {
                        AbstractUni.subscribe(uni, subscriber, executionChain);
                        return;
                    }
                }
                exec.execute(() -> AbstractUni.subscribe(uni, subscriber, cur));
            }
        };
    }

    /**
     * Gets the Context Propagation ThreadContext. External
     * implementations may implement this method.
     *
     * @return the ThreadContext
     * @see DefaultContextPropagationUniInterceptor#getThreadContext()
     */
    protected abstract ThreadContext getThreadContext();

    private static class CtxUniSubscriber<T> implements InternalUniSubscriber<T> {

        final Executor executor;
        final UniSubscriber<? super T> subscriber;
        final ExecutionChain chain;
        final ExecutionChain.CurrentExecutionContext ctx;

        public CtxUniSubscriber(Executor executor, UniSubscriber<? super T> subscriber, ExecutionChain chain,
                ExecutionChain.CurrentExecutionContext ctx) {
            this.executor = executor;
            this.subscriber = subscriber;
            this.chain = chain;
            this.ctx = ctx;
        }

        @Override
        public void onSubscribe(UniSubscription subscription) {
            //TODO: as far as I can tell this is always called straight after creation
            //it will have the same
            subscriber.onSubscribe(subscription);
        }

        @Override
        public void onItem(T item) {
            if (chain != null) {
                chain.execute(new ExecutionChain.ChainTask<Void>() {
                    @Override
                    public void runChainTask(Void contextual, ExecutionChain chain) {
                        if (chain != null && chain.currentExecutionContext() == ctx) {
                            if (subscriber instanceof InternalUniSubscriber) {
                                ((InternalUniSubscriber<T>) subscriber).onItem(item, chain);
                            } else {
                                subscriber.onItem(item);
                            }
                        } else {
                            executor.execute(() -> subscriber.onItem(item));
                        }
                    }
                });
            } else {
                executor.execute(() -> subscriber.onItem(item));
            }
        }

        @Override
        public void onFailure(Throwable failure) {
            executor.execute(() -> subscriber.onFailure(failure));
        }

        @Override
        public void onSubscribe(UniSubscription subscription, ExecutionChain executionChain) {
            if (subscriber instanceof InternalUniSubscriber) {
                ((InternalUniSubscriber<T>) subscriber).onSubscribe(subscription, executionChain);
            } else {
                subscriber.onSubscribe(subscription);
            }
        }

        @Override
        public void onItem(T item, ExecutionChain executionChain) {
            if (chain == null || executionChain != chain) {
                if (subscriber instanceof InternalUniSubscriber) {
                    executor.execute(() -> ((InternalUniSubscriber<T>) subscriber).onItem(item, executionChain));
                } else {
                    executor.execute(() -> subscriber.onItem(item));
                }
            } else {

                if (chain.currentExecutionContext() == ctx) {
                    if (subscriber instanceof InternalUniSubscriber) {
                        ((InternalUniSubscriber<T>) subscriber).onItem(item, executionChain);
                    } else {
                        subscriber.onItem(item);
                    }
                } else {
                    executor.execute(() -> subscriber.onItem(item));
                }
            }
        }

        @Override
        public void onFailure(Throwable failure, ExecutionChain executionChain) {
            if (chain == null || executionChain != chain) {
                if (subscriber instanceof InternalUniSubscriber) {
                    executor.execute(() -> ((InternalUniSubscriber<T>) subscriber).onFailure(failure, executionChain));
                } else {
                    executor.execute(() -> subscriber.onFailure(failure));
                }
            } else {
                if (subscriber instanceof InternalUniSubscriber) {
                    ((InternalUniSubscriber<T>) subscriber).onFailure(failure, executionChain);
                } else {
                    subscriber.onFailure(failure);
                }
            }
        }
    }
}
