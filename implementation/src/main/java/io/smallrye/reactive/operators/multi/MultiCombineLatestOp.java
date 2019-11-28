package io.smallrye.reactive.operators.multi;

import static io.smallrye.reactive.helpers.Subscriptions.CANCELLED;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.smallrye.reactive.helpers.ParameterValidation;
import io.smallrye.reactive.helpers.Subscriptions;
import io.smallrye.reactive.helpers.queues.SpscLinkedArrayQueue;
import io.smallrye.reactive.operators.MultiOperator;

/**
 * Combines the latest values from multiple sources through a function.
 *
 * @param <I> the type of item coming from upstreams
 * @param <O> the result type
 */
public class MultiCombineLatestOp<I, O> extends MultiOperator<I, O> {

    private final Iterable<? extends Publisher<? extends I>> upstreams;

    private final Function<List<?>, ? extends O> combinator;

    private final int bufferSize;

    private final boolean delayErrors;

    public MultiCombineLatestOp(
            Iterable<? extends Publisher<? extends I>> upstreams,
            Function<List<?>, ? extends O> combinator,
            int bufferSize, boolean delayErrors) {
        super(null);
        this.upstreams = ParameterValidation.doesNotContainNull(upstreams, "upstreams");
        this.combinator = ParameterValidation.nonNull(combinator, "combinator");
        this.bufferSize = bufferSize;
        this.delayErrors = delayErrors;
    }

    @Override
    public void subscribe(Subscriber<? super O> downstream) {
        List<Publisher<? extends I>> publishers = new ArrayList<>();
        this.upstreams.forEach(publishers::add);

        if (publishers.isEmpty()) {
            Subscriptions.complete(downstream);
            return;
        }

        if (publishers.size() == 1) {
            publishers.get(0).subscribe(
                    new MultiMapOp.MapProcessor<>(downstream, x -> combinator.apply(Collections.singletonList(x))));
            return;
        }

        CombineLatestCoordinator<I, O> coordinator = new CombineLatestCoordinator<>(downstream, combinator, publishers.size(),
                bufferSize, delayErrors);
        downstream.onSubscribe(coordinator);
        coordinator.subscribe(publishers);
    }

    @Override
    protected Publisher<O> publisher() {
        return this;
    }

    private static final class CombineLatestCoordinator<I, O> implements Subscription {

        private final Subscriber<? super O> downstream;
        private final Function<List<?>, ? extends O> combinator;
        private final List<CombineLatestInnerSubscriber<I>> subscribers = new ArrayList<>();
        private final SpscLinkedArrayQueue<Object> queue;
        private final Object[] latest;
        private final boolean delayErrors;

        private int nonEmptySources;
        private int completedSources;
        private volatile boolean cancelled;
        private volatile boolean done;
        private final AtomicLong requested = new AtomicLong();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger wip = new AtomicInteger();

        CombineLatestCoordinator(Subscriber<? super O> downstream,
                Function<List<?>, ? extends O> combinator, int size,
                int bufferSize, boolean delayErrors) {
            this.downstream = downstream;
            this.combinator = combinator;
            for (int i = 0; i < size; i++) {
                subscribers.add(new CombineLatestInnerSubscriber<>(this, i, bufferSize));
            }
            this.latest = new Object[size];
            this.queue = new SpscLinkedArrayQueue<>(bufferSize);
            this.delayErrors = delayErrors;
        }

        @Override
        public void request(long n) {
            if (n > 0) {
                Subscriptions.add(requested, n);
                drain();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            cancelAll();
        }

        private void subscribe(List<Publisher<? extends I>> sources) {
            int i = 0;
            for (CombineLatestInnerSubscriber<I> subscriber : subscribers) {
                if (done || cancelled) {
                    return;
                }
                sources.get(i).subscribe(subscriber);
                i++;
            }
        }

        void innerValue(int index, I value) {
            boolean replenishInsteadOfDrain;
            synchronized (this) {
                Object[] os = latest;

                int localNonEmptySources = nonEmptySources;

                if (os[index] == null) {
                    localNonEmptySources++;
                    nonEmptySources = localNonEmptySources;
                }
                os[index] = value;
                if (os.length == localNonEmptySources) {
                    queue.offer(subscribers.get(index), os.clone());
                    replenishInsteadOfDrain = false;
                } else {
                    replenishInsteadOfDrain = true;
                }
            }

            if (replenishInsteadOfDrain) {
                subscribers.get(index).requestOneItem();
            } else {
                drain();
            }
        }

        void innerComplete(int index) {
            synchronized (this) {
                Object[] os = latest;

                if (os[index] != null) {
                    int localCompletedSources = completedSources + 1;

                    if (localCompletedSources == os.length) {
                        done = true;
                    } else {
                        completedSources = localCompletedSources;
                        return;
                    }
                } else {
                    done = true;
                }
            }
            drain();
        }

        void innerError(int index, Throwable e) {
            if (Subscriptions.addFailure(failure, e)) {
                if (!delayErrors) {
                    cancelAll();
                    done = true;
                    drain();
                } else {
                    innerComplete(index);
                }
            }
        }

        @SuppressWarnings("unchecked")
        void drainAsync() {
            final SpscLinkedArrayQueue<Object> q = queue;

            int missed = 1;

            for (;;) {
                long req = requested.get();
                long emitter = 0L;
                while (emitter != req) {
                    boolean d = done;
                    Object v = q.poll();
                    boolean empty = v == null;
                    if (isEmptyOrDone(d, empty)) {
                        return;
                    }
                    if (empty) {
                        break;
                    }

                    I[] va = (I[]) q.poll();

                    O resultOfCombination;
                    try {
                        resultOfCombination = combinator.apply(Arrays.asList(va));
                        if (resultOfCombination == null) {
                            throw new NullPointerException("The combinator returned `null`");
                        }
                    } catch (Throwable ex) {
                        cancelAll();
                        Subscriptions.addFailure(failure, ex);
                        Subscriptions.terminateAndPropagate(failure, downstream);
                        return;
                    }
                    downstream.onNext(resultOfCombination);

                    ((CombineLatestInnerSubscriber<I>) v).requestOneItem();

                    emitter++;
                }

                if (emitter == req) {
                    if (isEmptyOrDone(done, q.isEmpty())) {
                        return;
                    }
                }

                if (emitter != 0L && req != Long.MAX_VALUE) {
                    requested.addAndGet(-emitter);
                }

                missed = wip.addAndGet(-missed);
                if (missed == 0) {
                    break;
                }
            }
        }

        void drain() {
            if (wip.getAndIncrement() != 0) {
                return;
            }

            drainAsync();
        }

        boolean isEmptyOrDone(boolean d, boolean empty) {
            if (cancelled) {
                cancelAll();
                queue.clear();
                return true;
            }

            if (d) {
                if (delayErrors) {
                    if (empty) {
                        cancelAll();
                        Throwable prev = Subscriptions.terminate(failure);
                        if (prev != null && prev != Subscriptions.TERMINATED) {
                            downstream.onError(prev);
                        } else {
                            downstream.onComplete();
                        }
                        return true;
                    }
                } else {
                    Throwable prev = Subscriptions.terminate(failure);
                    if (prev != null && prev != Subscriptions.TERMINATED) {
                        cancelAll();
                        queue.clear();
                        downstream.onError(prev);
                        return true;
                    } else if (empty) {
                        cancelAll();
                        downstream.onComplete();
                        return true;
                    }
                }
            }
            return false;
        }

        void cancelAll() {
            for (CombineLatestInnerSubscriber<I> inner : subscribers) {
                inner.cancel();
            }
        }
    }

    @SuppressWarnings("SubscriberImplementation")
    private static final class CombineLatestInnerSubscriber<T> implements Subscriber<T> {

        private final AtomicReference<Subscription> upstream = new AtomicReference<>();
        private final CombineLatestCoordinator<T, ?> parent;
        private final int index;
        private final int prefetch;
        private final int limit;
        int produced;

        CombineLatestInnerSubscriber(CombineLatestCoordinator<T, ?> parent, int index, int prefetch) {
            this.parent = parent;
            this.index = index;
            this.prefetch = prefetch;
            this.limit = prefetch - (prefetch >> 2);
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (upstream.compareAndSet(null, s)) {
                s.request(prefetch);
            }
        }

        @Override
        public void onNext(T t) {
            parent.innerValue(index, t);
        }

        @Override
        public void onError(Throwable t) {
            parent.innerError(index, t);
        }

        @Override
        public void onComplete() {
            parent.innerComplete(index);
        }

        public void cancel() {
            Subscription current = upstream.getAndSet(CANCELLED);
            if (current != CANCELLED && current != null) {
                current.cancel();
            }
        }

        void requestOneItem() {
            int p = produced + 1;
            if (p == limit) {
                produced = 0;
                upstream.get().request(p);
            } else {
                produced = p;
            }
        }
    }
}