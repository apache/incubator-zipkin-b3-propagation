# b3 single header format

In designing the Trace Context format, we made a section called tracestate which holds the authoritative propagation data.

This section defines a value that could be used as a separate "b3" header, and would be the same value used in the w3c tracestate field. Specific to the w3c format, this holds data not in the "traceparent" format, such as parent ID and the debug flag. It would be a completely non-lossy way to allocate our current headers into one value.

In simplest terms it is a mapping:

```
b3={x-b3-traceid}-{x-b3-spanid}-{if x-b3-flags 'd' else x-b3-sampled}-{x-b3-parentspanid}, where the last two fields are optional.
```

For example, the following headers:
```
X-B3-TraceId: 80f198ee56343ba864fe8b2a57d3eff7
X-B3-ParentSpanId: 05e3ac9a4f6e3b90
X-B3-SpanId: e457b5a2e4d86bd1
X-B3-Sampled: 1
```

Become one header or state field. For example, if a header:
```
b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-1-05e3ac9a4f6e3b90
```

Or if using w3c trace context format
```
tracestate: b3=80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-1-05e3ac9a4f6e3b90
```

Here are some more examples:

A sampled root span would look like:
```
b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-1
```

A not yet sampled root span would look like:
```
b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1
```

A debug RPC child span would look like:
```
b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-d-05e3ac9a4f6e3b90
```

Like normal B3, it is valid to omit trace identifiers in order to only propagate a sampling decision. For example, the following are valid downstream hints:

* don't sample - b3: 0
* sampled - b3: 1
* debug - b3: d

*NOTE* this does not match the prefix of traceparent, so we must define ours independently and consider that the w3c may change in different ways. This is ok as the "tracestate" entries in w3c format are required to be treated opaque. In other words we can be different on purpose or by accident of drift in their spec.

## On positional encoding vs nested key/values

Positional encoding is more space efficient and less complicated to parse vs key/value encoding. For example, the AWS format code in brave is complex due to splitting, dealing with white space etc. Positional is simple to parse and straight-forward to map. Rationale is same as w3c traceparent for the most part.

Different than new specs, we expect no additional fields. B3 is a very stable spec and we are not defining anything new except how to encode it. For this reason, positional should be fine.

## On putting mandatory fields up front

The trace ID and span ID fields are the only mandatory fields. This would allow fixed-length parsing for those just correlating on these values. Usually parentid is not used for correlation, rather scraping. Moreover, this is easier for existing proxies who only create trace identifiers.

Ex: you can control trace identifiers without making a sampling decision like so:
```
# root trace and span IDs
b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1
```

## On sampled before parent span ID

When name-values aren't used, it could be confusing which of the equal length fields are the parent. By placing the sampled flag in-between, we make this more clear. Also, it matches the prefix of the current [traceparent encoding](https://github.com/w3c/distributed-tracing/blob/master/trace_context/HTTP_HEADER_FORMAT.md#traceparent-field).

## Encoding "not yet sampled"

Leaving out the single-character sampled field is how we encoded the "no decision" state. This matches the way we used to address this (by leaving out `X-B3-Sampled`).

## Encoding debug

We encode the debug flag (previously `X-B3-Flags: 1`), as the letter 'd' in the same place as sampled ('1'). This is because debug is a boosted sampled signal. Most implementations record it out-of-band as `Span.debug=true` to ensure it reaches the collector tier.

```
# force trace on a root span
b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-d
```

One alternative considered was adding another field just to hold debug (ex a trailing -1). Not only was this less intuitive, it made parsing harder especially as parentId is also optional. This was reverted in openzipkin/brave#773

## W3C drift alert

While we should watch out for changes in the [TraceContext spec](https://github.com/w3c/distributed-tracing/issues/8). For example, if they add a "priority flag", we should keep our impl independent. B3 fields haven't changed in years and we can lock in something far safer knowing that.

## Why also define as a separate header

We have had continual problems with b3 with technology like JMS. In addition to declaring this format for w3c Trace Context, we could use it right away as the header "b3". This would solve all the problems we have like JMS hating hyphens in names, and allow those who opt into it a consistent format for when they transition to w3c.

openzipkin/brave#584

In other words, in messaging propagation and even normal http, some libraries could choose to read the "b3" header for the exact same format instead of "X-B3-X"

## Should we use flags instead of two fields for sampled and debug?

We could encode the three sampled states and debug as a single 8-bit field encoded as hex. If we used flags, an example sampled span would be:

```
b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-3-05e3ac9a4f6e3b90
```

Notice this is 3, not 1. That's because if using bit field we need to tell the difference between unsampled and no sampling decision. This could be confusing to people.

On flags, in java, we already encode sampled and debug state internally flags like this internally
```java
  static final int FLAG_SAMPLED = 1 << 1;
  static final int FLAG_SAMPLED_SET = 1 << 2;
  static final int FLAG_DEBUG = 1 << 3;

  static Boolean sampled(int flags) {
    return (flags & FLAG_SAMPLED_SET) == FLAG_SAMPLED_SET
        ? (flags & FLAG_SAMPLED) == FLAG_SAMPLED
        : null;
  }

  static boolean debug(int flags) {
    return (flags & FLAG_DEBUG) == FLAG_DEBUG;
  }
}
```

This might be better off than having two fields, although it is less simple as people often [make mistakes coding bit fields](https://github.com/w3c/distributed-tracing/pull/116), and X-B3-Flags caused confusion many times here including [#20](https://github.com/openzipkin/b3-propagation/issues/20).

## What about finagle's flags?

If we used flags, we could also do it the same way as [finagle does](https://github.com/twitter/finagle/blob/develop/finagle-core/src/main/scala/com/twitter/finagle/tracing/Flags.scala), except I think it would be confusing as the length they allocated (64 bits or 16 characters in hex) was never used in practice.

In practice we could use a single hex character to encode all the flags in our format (that supports 8 flags). Also, using Finagle's flag encoding could further confusion about the "X-B3-Flags" header, which in http encoding never has a value besides "1" [#20](https://github.com/openzipkin/b3-propagation/issues/20). At any rate, we can consider using the first 8 bits of their format as prior art regardless of if we use it.

```scala
/*
 * The debug flag is used to ensure this the current trace passes
 * all of the sampling stages.
 */
  val Debug = 1L << 0 // 1

/**
 * Reserved for future use to encode sampling behavior, currently
 * encoded explicitly in TraceId.sampled (Option[Boolean]).
 */
  val SamplingKnown = 1L << 1
  val Sampled = 1L << 2
```

## Relationship to JMS (Java Message Service)

The single header format, notably the name of "b3" solves propagation concerns a prefixed format causes with JMS.

JMS requires that message header names follow java naming conventions. Notably this excludes hyphens and dots. For example, [in Camel](http://people.apache.org/~dkulp/camel/jms.html), there's a naming policy to map to and from these constraints.

For example, many simply downcase B3 headers to `x-b3-traceid` for use in non-http transports (which might not be case insensitive). In JMS, this wouldn't work, even if rabbitmq is used underneath which has no such constraint. Ex, if using camel JMS, this header would map into `x_HYPHEN_b3_HYPHEN_traceid`.

In some cases, JMS headers are replaced based on constants or other patterns, like globally replacing hyphens with underscores. Interestingly opentracing decided on a [different pattern](https://github.com/opentracing-contrib/java-jms/pull/1) `_$dash$_`, though they have no pattern for dots: For example, if you were using the OT library and camel on the other, you'd get `x_HYPHEN_b3_HYPHEN_traceid` on one side and `x_$dash$_b3_$dash$_traceid` on the other, mutually unreadable.

The universal implication of using JMS is that it implies global coordination of these naming patterns, or there will be propagation incompatibility (ex traces will restart). This will also break anything else propagated that isn't a trace ID. This type of problem has [already been noticed](https://github.com/spring-cloud/spring-cloud-sleuth/issues/537) in early users of message tracing.

Importantly, even if we were to have static replacements for `X-B3-` headers, we don't know if users will introduce propagated headers with dots or hyphens in them. In other words, a constant-based solution helps, but won't fix it.

To compound issues, any mapping or heuristic approach to prefixed headers becomes virulent and not limited to only messaging systems. Some integration services pass headers from http directly into messaging headers. Even if it feels tempting to make a "messaging" header name, we have to keep in mind that many are already using header names with hyphens. If we start propagating underscores universally, we introduce complexity to existing non-JMS consumers.

For all of these reasons, a policy of only using "b3" format when using JMS is the safest policy. That implies using prefixed headers `X-B3-` is an antipattern.
