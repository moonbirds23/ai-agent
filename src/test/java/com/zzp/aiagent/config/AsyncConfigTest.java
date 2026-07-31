package com.zzp.aiagent.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    @Test
    void taskExecutorPropagatesCurrentObservation() throws Exception {
        TestObservationRegistry registry = TestObservationRegistry.create();
        ObservationThreadLocalAccessor.getInstance().setObservationRegistry(registry);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().taskExecutor();
        Observation parent = Observation.start("parent", registry);

        try (Observation.Scope ignored = parent.openScope()) {
            CompletableFuture<Observation> observed = CompletableFuture.supplyAsync(
                    registry::getCurrentObservation, executor);

            assertThat(observed.get(5, TimeUnit.SECONDS)).isNotNull();
        } finally {
            parent.stop();
            executor.shutdown();
            ObservationThreadLocalAccessor.getInstance()
                    .setObservationRegistry(ObservationRegistry.NOOP);
        }
    }
}
