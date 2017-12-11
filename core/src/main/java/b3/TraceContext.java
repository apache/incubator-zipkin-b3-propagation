/**
 * Copyright 2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package b3;

import java.nio.ByteBuffer;

/**
 * Contains trace data that's propagated in-band across requests, sometimes known as Baggage.
 *
 * <p>Particularly, this includes trace identifiers, sampled state, and a portable binary
 * representation.
 *
 * <p>This binary representation is fixed-length, depending on the size of the trace identifier.
 * This representation doesn't have a way to represent an absent parent (root span). In this
 * serialized form, a root span is when all three ids are the same. Alternatively, you can use
 * {@link #nullableParentId}.
 *
 * <p>The implementation was originally {@code com.github.kristofa.brave.TraceContext}, which was a
 * port of {@code com.twitter.finagle.tracing.TraceId}.
 */
public final class TraceContext {

  /** Unique 8 or 16-byte identifier for a trace, set on all spans within it. */
  public static final class TraceId {

    /** 0 may imply 8-byte identifiers are in use */
    public final long hi;
    public final long lo;

    TraceId(long hi, long lo) {
      this.hi = hi;
      this.lo = lo;
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) return true;
      if (o instanceof TraceId) {
        TraceId that = (TraceId) o;
        return (this.hi == that.hi)
            && (this.lo == that.lo);
      }
      return false;
    }

    @Override
    public int hashCode() {
      int h = 1;
      h *= 1000003;
      h ^= (hi >>> 32) ^ hi;
      h *= 1000003;
      h ^= (lo >>> 32) ^ lo;
      return h;
    }
  }

  public static final int FLAG_DEBUG = 1 << 0;
  /** When set, we can interpret {@link #FLAG_SAMPLED} as a set value. */
  public static final int FLAG_SAMPLING_SET = 1 << 1;
  public static final int FLAG_SAMPLED = 1 << 2;
  /**
   * When set, we can ignore the value of the {@link #parentId}
   *
   * <p>While many zipkin systems re-use a trace id as the root span id, we know that some don't.
   * With this flag, we can tell for sure if the span is root as opposed to the convention trace id
   * == span id == parent id.
   */
  public static final int FLAG_IS_ROOT = 1 << 3;

  TraceContext(TraceId traceId, long parentId, long spanId, long flags) {
    this.traceId = traceId;
    this.parentId = (parentId == spanId) ? traceId.lo : parentId;
    this.spanId = spanId;
    this.flags = flags;
  }

  /** Deserializes this from a big-endian byte array */
  public static TraceContext fromBytes(byte[] bytes) {
    checkNotNull(bytes, "bytes");
    if (bytes.length != 32 && bytes.length != 40) {
      throw new IllegalArgumentException("bytes.length " + bytes.length + " != 32 or 40");
    }

    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    long spanId = buffer.getLong(0);
    long parentId = buffer.getLong(8);
    TraceId traceId;
    long flags;
    if (bytes.length == 32) {
      traceId = new TraceId(0, buffer.getLong(16));
      flags = buffer.getLong(24);
    } else {
      traceId = new TraceId(
          buffer.getLong(16),
          buffer.getLong(24)
      );
      flags = buffer.getLong(32);
    }

    return new TraceContext(traceId, parentId, spanId, flags);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Unique 8 or 16-byte identifier for a trace, set on all spans within it. */
  public final TraceId traceId;

  /** The parent's {@link #spanId} or {@link #spanId} if this the root span in a trace. */
  public final long parentId;

  /** Returns null when this is a root span. */
  public Long nullableParentId() {
    return root() ? null : parentId;
  }

  /**
   * Unique 8-byte identifier of this span within a trace.
   *
   * <p>A span is uniquely identified in storage by ({@linkplain #traceId}, {@code #id}).
   */
  public final long spanId;

  /** Returns true if this is the root span. */
  public final boolean root() {
    return (flags & FLAG_IS_ROOT) == FLAG_IS_ROOT || parentId == traceId.lo && parentId == spanId;
  }

  /**
   * True is a request to store this span even if it overrides sampling policy. Implies {@link
   * #nullableSampled()}.
   */
  public final boolean debug() {
    return (flags & FLAG_DEBUG) == FLAG_DEBUG;
  }

  /**
   * Should we sample this request or not? True means sample, false means don't, null means we defer
   * decision to someone further down in the stack.
   */
  public final Boolean nullableSampled() {
    if (debug()) return true;
    return (flags & FLAG_SAMPLING_SET) == FLAG_SAMPLING_SET
        ? (flags & FLAG_SAMPLED) == FLAG_SAMPLED
        : null;
  }

  /** Raw flags encoded in {@link #bytes()} */
  public final long flags;

  /** Serializes this into a big-endian byte array */
  public byte[] bytes() {
    boolean traceHi = traceId.hi != 0;
    byte[] result = new byte[traceHi ? 40 : 32];
    ByteBuffer buffer = ByteBuffer.wrap(result);
    buffer.putLong(0, spanId);
    buffer.putLong(8, parentId);
    if (traceHi) {
      buffer.putLong(16, traceId.hi);
      buffer.putLong(24, traceId.lo);
      buffer.putLong(32, flags);
    } else {
      buffer.putLong(16, traceId.lo);
      buffer.putLong(24, flags);
    }
    return result;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  /** Returns {@code $traceId.$spanId<:$parentId} */
  @Override
  public String toString() {
    boolean traceHi = traceId.hi != 0;
    char[] result = new char[((traceHi ? 4 : 3) * 16) + 3]; // 3 ids and the constant delimiters
    int pos = 0;
    if (traceHi) {
      writeHexLong(result, pos, traceId.hi);
      pos += 16;
    }
    writeHexLong(result, pos, traceId.lo);
    pos += 16;
    result[pos++] = '.';
    writeHexLong(result, pos, spanId);
    pos += 16;
    result[pos++] = '<';
    result[pos++] = ':';
    writeHexLong(result, pos, parentId);
    return new String(result);
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) return true;
    if (o instanceof TraceContext) {
      TraceContext that = (TraceContext) o;
      return this.traceId.equals(that.traceId)
          && this.parentId == that.parentId
          && this.spanId == that.spanId;
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h = 1;
    h *= 1000003;
    h ^= traceId.hashCode();
    h *= 1000003;
    h ^= (parentId >>> 32) ^ parentId;
    h *= 1000003;
    h ^= (spanId >>> 32) ^ spanId;
    return h;
  }

  public static final class Builder {
    long hi = 0;
    Long lo;
    Long nullableParentId;
    Long spanId;
    long flags;

    Builder() {
    }

    Builder(TraceContext source) {
      this.hi = source.traceId.hi;
      this.lo = source.traceId.lo;
      this.nullableParentId = source.nullableParentId();
      this.spanId = source.spanId;
      this.flags = source.flags;
    }

    /** @see TraceId#hi */
    public Builder hi(long hi) {
      this.hi = hi;
      return this;
    }

    /** @see TraceId#lo */
    public Builder lo(long lo) {
      this.lo = lo;
      return this;
    }

    /**
     * If your trace ids are not span ids, you must call this method to indicate absent parent.
     *
     * @see TraceContext#nullableParentId()
     */
    public Builder nullableParentId(Long nullableParentId) {
      if (nullableParentId == null) {
        this.flags |= FLAG_IS_ROOT;
      } else {
        this.flags &= ~FLAG_IS_ROOT;
      }
      this.nullableParentId = nullableParentId;
      return this;
    }

    /** @see TraceContext#spanId */
    public Builder spanId(long spanId) {
      this.spanId = spanId;
      return this;
    }

    /** @see TraceContext#flags */
    public Builder flags(long flags) {
      this.flags = flags;
      return this;
    }

    /** @see TraceContext#debug() */
    public Builder debug(boolean debug) {
      if (debug) {
        this.flags |= FLAG_DEBUG;
      } else {
        this.flags &= ~FLAG_DEBUG;
      }
      return this;
    }

    /** @see TraceContext#nullableSampled */
    public Builder nullableSampled(Boolean nullableSampled) {
      if (nullableSampled != null) {
        this.flags |= FLAG_SAMPLING_SET;
        if (nullableSampled) {
          this.flags |= FLAG_SAMPLED;
        } else {
          this.flags &= ~FLAG_SAMPLED;
        }
      } else {
        this.flags &= ~FLAG_SAMPLING_SET;
      }
      return this;
    }

    public TraceContext build() {
      checkNotNull(spanId, "spanId");
      long lo = this.lo != null ? this.lo : spanId;
      long nullableParentId = this.nullableParentId != null ? this.nullableParentId : lo;
      return new TraceContext(new TraceId(hi, lo), nullableParentId, spanId, flags);
    }
  }

  /** Inspired by {@code okio.Buffer.writeLong} */
  static void writeHexLong(char[] data, int pos, long v) {
    writeHexByte(data, pos + 0, (byte) ((v >>> 56L) & 0xff));
    writeHexByte(data, pos + 2, (byte) ((v >>> 48L) & 0xff));
    writeHexByte(data, pos + 4, (byte) ((v >>> 40L) & 0xff));
    writeHexByte(data, pos + 6, (byte) ((v >>> 32L) & 0xff));
    writeHexByte(data, pos + 8, (byte) ((v >>> 24L) & 0xff));
    writeHexByte(data, pos + 10, (byte) ((v >>> 16L) & 0xff));
    writeHexByte(data, pos + 12, (byte) ((v >>> 8L) & 0xff));
    writeHexByte(data, pos + 14, (byte) (v & 0xff));
  }

  static final char[] HEX_DIGITS =
      {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

  static void writeHexByte(char[] data, int pos, byte b) {
    data[pos + 0] = HEX_DIGITS[(b >> 4) & 0xf];
    data[pos + 1] = HEX_DIGITS[b & 0xf];
  }

  /** Inspired by {@code com.google.common.base.Preconditions#checkNotNull}. */
  public static <T> T checkNotNull(T reference, String errorMessage) {
    if (reference == null) throw new NullPointerException(errorMessage);
    return reference;
  }
}
