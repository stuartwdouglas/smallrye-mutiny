package io.smallrye.mutiny.operators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.UniSubscriber;

public class UniRunSubscriptionOnTest {

    @Test
    public void testRunSubscriptionOnWithSupplier() {
        UniAssertSubscriber<Integer> subscriber = UniAssertSubscriber.create();
        Uni.createFrom().item(() -> 1)
                .runSubscriptionOn(ForkJoinPool.commonPool())
                .subscribe().withSubscriber(subscriber);
        subscriber.await().assertItem(1);
        assertThat(subscriber.getOnSubscribeThreadName()).isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    public void testRunSubscriptionOnWithSupplierWithDeprecatedMethod() {
        UniAssertSubscriber<Integer> subscriber = UniAssertSubscriber.create();
        Uni.createFrom().item(() -> 1)
                .subscribeOn(ForkJoinPool.commonPool())
                .subscribe().withSubscriber(subscriber);
        subscriber.await().assertItem(1);
        assertThat(subscriber.getOnSubscribeThreadName()).isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    public void testWithWithImmediateValue() {
        UniAssertSubscriber<Integer> subscriber = UniAssertSubscriber.create();

        Uni.createFrom().item(1)
                .runSubscriptionOn(ForkJoinPool.commonPool())
                .subscribe().withSubscriber(subscriber);

        subscriber.await().assertItem(1);
        assertThat(subscriber.getOnSubscribeThreadName()).isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    public void testWithTimeout() {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        UniAssertSubscriber<Integer> subscriber = UniAssertSubscriber.create();

        Uni.createFrom().item(() -> {
            try {
                TimeUnit.SECONDS.sleep(2L);
            } catch (InterruptedException e) {
                // ignored
            }
            return 0;
        })
                .ifNoItem().after(Duration.ofMillis(100)).recoverWithUni(Uni.createFrom().item(() -> 1))
                // Should not use the default as in container you may have a single thread, blocked by the sleep statement.
                .runSubscriptionOn(executorService)
                .subscribe().withSubscriber(subscriber);

        subscriber.await().assertItem(1);

        executorService.shutdownNow();
    }

    @Test
    public void callableEvaluatedTheRightTime() {
        AtomicInteger count = new AtomicInteger();

        Uni<Integer> uni = Uni.createFrom().item(count::incrementAndGet)
                .runSubscriptionOn(ForkJoinPool.commonPool());

        assertThat(count).hasValue(0);
        uni.subscribe().withSubscriber(UniAssertSubscriber.create()).await();
        assertThat(count).hasValue(1);
    }

    @Test
    public void testWithFailure() {
        Uni.createFrom().<Void> failure(new IOException("boom"))
                .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .await()
                .assertFailedWith(IOException.class, "boom");
    }

    @Test
    public void testImmediateCancellation() {
        UniAssertSubscriber<Integer> subscriber = Uni.createFrom().<Integer> emitter(e -> {
            // Do nothing
        })
                .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                .subscribe().withSubscriber(new UniAssertSubscriber<>(true));

        subscriber.assertNotTerminated();
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void testCancellation() {
        AtomicBoolean called = new AtomicBoolean();
        UniAssertSubscriber<Integer> subscriber = Uni.createFrom().<Integer> emitter(e -> {
            called.set(true);
        })
                .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                .subscribe().withSubscriber(new UniAssertSubscriber<>(true));

        await().untilAsserted(subscriber::assertSubscribed);
        subscriber.assertSubscribed();
        assertThat(called).isTrue();
    }

    @Test
    public void testRejectedTask() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.shutdown();
        AtomicBoolean called = new AtomicBoolean();
        UniAssertSubscriber<Integer> subscriber = Uni.createFrom().<Integer> emitter(e -> {
            called.set(true);
        })
                .runSubscriptionOn(pool)
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        assertThat(called).isFalse();
        subscriber.await()
                .assertFailedWith(RejectedExecutionException.class, "");
    }

    @Test
    public void testSubscriptionFailing() {
        AtomicBoolean called = new AtomicBoolean();
        UniAssertSubscriber<Integer> subscriber = new AbstractUni<Integer>(null) {

            @Override
            protected void subscribing(UniSubscriber<? super Integer> subscriber) {
                throw new IllegalArgumentException("boom");
            }
        }
                .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        assertThat(called).isFalse();
        subscriber
                .await()
                .assertFailedWith(IllegalArgumentException.class, "boom");
    }

}
