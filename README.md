# b3-propagation

This repository elaborates identifiers used to place an operation in a trace tree. These attributes are propagated in-process, and eventually downstream (often via http headers), to ensure all activity originating from the same root are collected together. A sampling decision is made at the root of the trace, and this indicates if trace details should be collected and reported to the tracing system (usually Zipkin) or not.

# Overall Process

The most common propagation use case is to copy a trace context from a client 
sending an RPC request to a server receiving it.

In this case, the same trace IDs are used, which means that both the client and
server side of an operation end up in the same node in the trace tree.

Here's an example flow, assuming an HTTP request carries the propagated trace:

```
   Client Tracer                                                  Server Tracer     
┌───────────────────────┐                                       ┌───────────────────────┐
│                       │                                       │                       │
│   TraceContext        │           Http Request Headers        │   TraceContext        │
│ ┌───────────────────┐ │          ┌───────────────────┐        │ ┌───────────────────┐ │
│ │ TraceId           │ │          │ X-B3-TraceId      │        │ │ TraceId           │ │
│ │                   │ │          │                   │        │ │                   │ │
│ │ ParentSpanId      │ │ Extract  │ X-B3-ParentSpanId │ Inject │ │ ParentSpanId      │ │
│ │                   ├─┼─────────>│                   ├────────┼>│                   │ │
│ │ SpanId            │ │          │ X-B3-SpanId       │        │ │ SpanId            │ │
│ │                   │ │          │                   │        │ │                   │ │
│ │ Sampling decision │ │          │ X-B3-Sampled      │        │ │ Sampling decision │ │
│ └───────────────────┘ │          └───────────────────┘        │ └───────────────────┘ │
│                       │                                       │                       │
└────────────────-----──┘                                       └───────────────────────┘
```


# Identifiers
Trace identifiers are 64 or 128-bit, but all span identifiers within a trace are 64-bit. All identifiers are opaque.

## TraceId

The TraceId is 64 or 128-bit in length and indicates the overall ID of the trace. Every span in a trace shares this ID.

## SpanId

The SpanId is 64-bit in length and indicates the position of the current operation in the trace tree. The value should not be interpreted: it may or may not be derived from the value of the TraceId.

## ParentSpanId

The ParentSpanId is 64-bit in length and indicates the position of the parent operation in the trace tree. When the span is the root of the trace tree, the ParentSpanId is absent.

# Sampling State

Sampling is a mechanism to reduce the volume of data that ends up in the tracing system. In B3, sampling applies consistently per-trace: once the sampling decision is made, the same value should be consistently sent downstream. This means you will see all spans sharing a trace ID or none.

Here are the valid sampling states. Note they all applied to the trace ID, not the span ID:
* Defer: aka I don't know yet!
  * Defer is used when trace identifiers are set by a proxy, but that proxy doesn't send data to Zipkin. The most common use case for defering a decision is pre-provisioning trace identifiers.
* Deny: aka don't sample or don't record
  * Deny is used to achieve a probabilistic rate or to prevent certain paths (such as health checks) from generating traces. Where possible, instrumentation should should optimize deny such that less overhead occurs.
* Accept: aka sample or record
  * Accept is used to achieve a probabilistic rate or to ensure certain paths (such as low-traffic endpoints) are always traced. When a trace is accepted, spans should be reported to zipkin except in overload scenarios.
* Debug: aka force trace
  * Debug is a production troubleshooting aid used in tools like curl or chrome debug. Debug is an emphasized accept decision that implies accept, additionally reporting `Span.debug = true` for each span in the trace.

The most common use of sampling is probablistic: eg, accept 0.01% of traces and deny the rest. Debug is the least common use case.

# Http Encoding
B3 attributes are most commonly propagated as Http headers. All B3 headers follows the convention of `X-B3-${name}` with special-casing for flags. When reading headers, the first value wins.

## TraceId
The `X-B3-TraceId` header is required and is encoded as 32 or 16 lower-hex characters. For example, a 128-bit TraceId header might look like: `X-B3-TraceId: 463ac35c9f6413ad48485a3953bb6124`

## SpanId
The `X-B3-SpanId` header is required and is encoded as 16 lower-hex characters. For example, a SpanId header might look like: `X-B3-SpanId: a2fb4a1d1a96d312`

## ParentSpanId
The `X-B3-ParentSpanId` header must be present on a child span and absent on the root span. It is encoded as 16 lower-hex characters. For example, a ParentSpanId header might look like: `X-B3-ParentSpanId: 0020000000000001`

## Sampling State
An accept sampling decision is encoded as `X-B3-Sampled: 1` and a reject as `X-B3-Sampled: 0`. Absent means defer the decision to the receiver of this header. For example, a Sampled header might look like: `X-B3-Sampled: 1`.

Note: Before this specification was written, some tracers propagated `X-B3-Sampled` as `true` or `false` as opposed to `1` or `0`. While you shouldn't encode `X-B3-Sampled` as `true` or `false`, a lenient implementation may accept them.

### Debug Flag
Debug is encoded as `X-B3-Flags: 1`. Debug implies an accept decision, so don't also send `X-B3-Sampled: 1`.

# gRPC Encoding
B3 attributes can also be propagated as ASCII headers in the Custom Metadata of
a request. The encoding is exactly the same as Http headers, except the names are
explicitly or implicitly down-cased.

For example, the Http header `X-B3-ParentSpanId: 0020000000000001` would become
an ASCII header `x-b3-parentspanid` with the same value.

# Frequently Asked Questions

## Why is ParentSpanId propagated?

In B3, the trace context is extracted from incoming headers. Timing and
metadata for the client and server side of an operation are recorded with
the same context. The `ParentSpanId` is the ID of the operation that caused
the current RPC. For example, it could be the ID of another server request
or a scheduled job. `ParentSpanId` is propagated so that when data is reported
to Zipkin, it can be placed in the correct spot in the trace tree.

Here's an example of a B3 library extracting a trace context from incoming http
request headers:
```
                           ┌───────────────────┐
 Incoming Headers          │   TraceContext    │
┌───────────────────┐      │ ┌───────────────┐ │
│ X-B3-TraceId      │──────┼─┼> TraceId      │ │
│                   │      │ │               │ │
│ X-B3-ParentSpanId │──────┼─┼> ParentSpanId │ │
│                   │      │ │               │ │
│ X-B3-SpanId       │──────┼─┼> SpanId       │ │
│                   │      │ └───────────────┘ │
└───────────────────┘      └───────────────────┘
```

Some propagation formats look similar to B3, but don't propagate a field named
parent. Instead, they propagate a span ID field which serves the same purpose
as `ParentSpanId`. Unlike B3, these systems use a different span ID for the
client and server side of an RPC. When a server reads headers like this, it is
expected to provision a new span ID for itself, and use the one it extracted as
its parent.

Here's an example of an alternate library composing a trace context with
incoming http request headers and an ID generator:
```
                           ┌───────────────────┐
 Incoming Headers          │   TraceContext    │
┌───────────────────┐      │ ┌───────────────┐ │
│ XXXX-TraceId      │──────┼─┼> TraceId      │ │
│                   │      │ │               │ │
│ XXXX-SpanId       │──────┼─┼> ParentSpanId │ │
└───────────────────┘      │ │               │ │      ┌──────────────┐
                           │ │  SpanId      <┼─┼──────│ ID Generator │
                           │ └───────────────┘ │      └──────────────┘
                           └───────────────────┘
```

In both B3 and the above example, incoming headers contain the parent's span ID,
and three IDs (trace, parent, span) end up in the trace context. The difference
is that B3 uses the same span ID for the client and server side of an RPC, where
the latter does not.

### Why propagate a reject sampling decision?

It may not be obvious why you'd send a reject sampling decision to the next hop.
Imagine a service decides not to trace an operation and makes 2 out-going calls,
and these branched out further. If reject ("don't trace") isn't propagated, the
system might receive only parts of the operation, confusing users.

Another reason to reject sampling is to prevent overwhelming the collector with
high throughput. If the reject-sampling decision is not propagated then there's
no way to tell recipients of outbound calls that they should not send span info
to the collector.

#### Why send trace IDs with a reject sampling decision?

While valid to propagate only a reject sampling decision (`X-B3-Sampled: 0`),
if trace identifiers are established, they should be propagated, too. The
tracing system is often not the only consumer of trace identifiers. Services may
still want to do things with the tracing info even if they aren't sending span
data to the collector, e.g. tag logs with trace IDs.

### Why defer a sampling decision?

Deferring the sampling decision is special-case. The only known use-cases are
the following:

* Debug trace: A debug trace is implicitly an accepted trace
* Externally provisioned IDs: When you want to control IDs, but not sampling policy

Unless it is a debug trace, leaving out a sampling decision is typically ID
correlation. For example, someone re-uses a global identifier from another
system, or correlating in logs. In these cases, the caller knows the ID they
want, but allows the next hop to decide if it will be traced or not. The caller
should not report a span to the tracing system using this ID unless they
propagate an accept sampling decision.

## Why is Debug encoded as `X-B3-Flags: 1`?
The first tracer to use B3 was Finagle. In Finagle, thrift-rpc was a dominant
protocol, and a fixed-width binary encoding held B3 attributes. This binary
encoding used a bit field to encode the sampling decision and the debug flag.

Http encoding chose to use a separate header for the sampling decision, but
kept the flags field for debug. In hind sight, it would have made more sense to
use a separate header such as `X-B3-Debug: 1` as no other flags were added and
no implementation treats `X-B3-Flags` as a bit field. That said, renaming the
field would cause more damage than good, so it was left alone.
