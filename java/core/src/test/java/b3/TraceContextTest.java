package b3;

import b3.TraceContext.TraceId;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static b3.TraceContext.FLAG_DEBUG;
import static b3.TraceContext.FLAG_SAMPLED;
import static b3.TraceContext.FLAG_SAMPLING_SET;
import static org.assertj.core.api.Assertions.assertThat;

public class TraceContextTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test public void rootSpan_whenTraceIdsAreSpanIds() {
    TraceContext id = TraceContext.builder().spanId(333L).build();

    assertThat(id.root()).isTrue();
    assertThat(id.nullableParentId()).isNull();

    assertThat(TraceContext.fromBytes(id.bytes()))
        .isEqualToComparingFieldByField(id);
  }

  @Test public void equals() {
    assertThat(TraceContext.builder().spanId(333L).build())
        .isEqualTo(TraceContext.builder().spanId(333L).build());
  }

  // NOTE: finagle doesn't support this, but then again it doesn't provision non-span trace ids
  @Test public void rootSpan_whenTraceIdsArentSpanIds() {
    TraceContext id = TraceContext.builder().lo(555L).nullableParentId(null).spanId(333L).build();

    assertThat(id.root()).isTrue();
    assertThat(id.nullableParentId()).isNull();

    assertThat(TraceContext.fromBytes(id.bytes()))
        .isEqualToComparingFieldByField(id);
  }

  @Test public void compareUnequalIds() {
    TraceContext id = TraceContext.builder().spanId(0L).build();

    assertThat(id)
        .isNotEqualTo(TraceContext.builder().spanId(1L).build());
  }

  @Test public void compareEqualIds() {
    TraceContext id = TraceContext.builder().spanId(0L).build();

    assertThat(id)
        .isEqualTo(TraceContext.builder().spanId(0L).build());
  }

  @Test public void compareSynthesizedParentId() {
    TraceContext id = TraceContext.builder().nullableParentId(1L).spanId(1L).build();

    assertThat(id)
        .isEqualTo(TraceContext.builder().spanId(1L).build());
  }

  @Test public void compareSynthesizedTraceId() {
    TraceContext id = TraceContext.builder().lo(1L).nullableParentId(1L).spanId(1L).build();

    assertThat(id)
        .isEqualTo(TraceContext.builder().nullableParentId(1L).spanId(1L).build());
  }

  @Test public void serializationRoundTrip() {
    TraceContext id =
        TraceContext.builder().lo(1L).nullableParentId(2L).spanId(3L).nullableSampled(true).build();

    assertThat(TraceContext.fromBytes(id.bytes()))
        .isEqualToComparingFieldByField(id);
  }

  @Test public void fromBytesFail() {
    thrown.expect(IllegalArgumentException.class);

    TraceContext.fromBytes("not-a-trace".getBytes());
  }

  @Test public void sampledTrueWhenDebug() {
    TraceContext id = TraceContext.builder().spanId(1L).debug(true).build();

    assertThat(id.nullableSampled()).isTrue();
  }

  @Test public void builderClearsSampled() {
    TraceContext id =
        new TraceContext(new TraceId(0L, 1L), 1L, 1L, FLAG_SAMPLED | FLAG_SAMPLING_SET);

    assertThat(id.nullableSampled()).isTrue();

    id = id.toBuilder().nullableSampled(null).build();

    assertThat(id.nullableSampled()).isNull();
  }

  @Test public void builderUnsetsDebug() {
    TraceContext id = new TraceContext(new TraceId(0L, 1L), 1L, 1L, FLAG_DEBUG);

    assertThat(id.debug()).isTrue();

    id = id.toBuilder().debug(false).build();

    assertThat(id.debug()).isFalse();
  }

  @Test public void equalsOnlyAccountsForIdFields() {
    assertThat(new TraceContext(new TraceId(0L, 1L), 1L, 1L, FLAG_DEBUG).hashCode())
        .isEqualTo(new TraceContext(new TraceId(0L, 1L), 1L, 1L, FLAG_SAMPLING_SET).hashCode());
  }

  @Test public void hashCodeOnlyAccountsForIdFields() {
    assertThat(new TraceContext(new TraceId(0L, 1L), 1L, 1L, FLAG_DEBUG))
        .isEqualTo(new TraceContext(new TraceId(0L, 1L), 1L, 1L, FLAG_SAMPLING_SET));
  }

  @Test
  public void testToString_lo() {
    TraceContext id = TraceContext.builder().lo(1).spanId(3).nullableParentId(2L).build();

    assertThat(id.toString())
        .isEqualTo("0000000000000001.0000000000000003<:0000000000000002");
  }

  @Test
  public void testToStringNullParent_lo() {
    TraceContext id = TraceContext.builder().lo(1).spanId(1).build();

    assertThat(id.toString())
        .isEqualTo("0000000000000001.0000000000000001<:0000000000000001");
  }

  @Test
  public void testToString_hi() {
    TraceContext id = TraceContext.builder().hi(1).lo(1).spanId(3).nullableParentId(2L).build();

    assertThat(id.toString())
        .isEqualTo("00000000000000010000000000000001.0000000000000003<:0000000000000002");
  }

  @Test
  public void testToStringNullParent_hi() {
    TraceContext id = TraceContext.builder().hi(1).lo(1).spanId(1).build();

    assertThat(id.toString())
        .isEqualTo("00000000000000010000000000000001.0000000000000001<:0000000000000001");
  }
}
