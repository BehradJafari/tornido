package io.tornado.reporting;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import io.tornado.persistence.MixTradeSimulation;

import static io.tornado.reporting.FirstTouchOutcomeResolver.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FirstTouchOutcomeResolverTest {
    private final FirstTouchOutcomeResolver resolver = new FirstTouchOutcomeResolver();
    private final Instant first = Instant.parse("2026-08-15T10:00:00Z");
    private final Instant second = first.plusSeconds(1);

    @Test void unresolvedWhenNeitherLevelWasTouched() { assertThat(resolver.resolve(null, null)).isEqualTo(UNRESOLVED); }
    @Test void successWhenOnlyTpWasTouched() { assertThat(resolver.resolve(first, null)).isEqualTo(SUCCESS); }
    @Test void failedWhenOnlySlWasTouched() { assertThat(resolver.resolve(null, first)).isEqualTo(FAILED); }
    @Test void successWhenTpWasFirst() { assertThat(resolver.resolve(first, second)).isEqualTo(SUCCESS); }
    @Test void failedWhenSlWasFirst() { assertThat(resolver.resolve(second, first)).isEqualTo(FAILED); }
    @Test void ambiguousWhenBothWereObservedTogether() { assertThat(resolver.resolve(first, first)).isEqualTo(AMBIGUOUS); }
    @Test void equalTimestampUsesAggregateTradeOrderForFailure() { assertThat(resolver.resolve(first,101L,first,100L)).isEqualTo(FAILED); }
    @Test void equalTimestampUsesAggregateTradeOrderForSuccess() { assertThat(resolver.resolve(first,100L,first,101L)).isEqualTo(SUCCESS); }
    @Test void equalTimestampAndEqualAggregateTradeRemainsAmbiguous() { assertThat(resolver.resolve(first,100L,first,100L)).isEqualTo(AMBIGUOUS); }

    @Test void selectedTpLevelChangesTheDerivedOutcome() {
        MixTradeSimulation signal=mock(MixTradeSimulation.class);
        when(signal.tpHitAt(1)).thenReturn(first); when(signal.tpHitAt(2)).thenReturn(null); when(signal.slHitAt(1)).thenReturn(second);
        assertThat(resolver.resolve(signal,1,1)).isEqualTo(SUCCESS);
        assertThat(resolver.resolve(signal,2,1)).isEqualTo(FAILED);
    }

    @Test void selectedSlLevelChangesTheDerivedOutcome() {
        MixTradeSimulation signal=mock(MixTradeSimulation.class);
        when(signal.tpHitAt(2)).thenReturn(second); when(signal.slHitAt(1)).thenReturn(first); when(signal.slHitAt(2)).thenReturn(second.plusSeconds(1));
        assertThat(resolver.resolve(signal,2,1)).isEqualTo(FAILED);
        assertThat(resolver.resolve(signal,2,2)).isEqualTo(SUCCESS);
    }
}
