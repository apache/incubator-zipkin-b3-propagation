# b3-propagation

This repository elaborates identifiers used to place an operation in a trace tree. These attributes are propagated in-process, and eventually downstream (often via http headers), to ensure all activity originating from the same root are collected together. A sampling decision is made at the root of the trace, and this indicates if trace details should be collected and reported to the tracing system (usually Zipkin) or not.

# Identifiers
Trace identifiers are 64 or 128-bit, but all span identifiers within a trace are 64-bit. All identifiers are opaque.

## TraceId

The TraceId is 64 or 128-bit in length and indicates the overall ID of the trace. Every span in a trace shares this ID.

### Http Header format
The `X-B3-TraceId` header is required and is encoded as 32 or 16 hex characters. For example, a 128-bit TraceId header might look like: `X-B3-TraceId: 463ac35c9f6413ad48485a3953bb6124`

## SpanId

The SpanId is 64-bit in length and indicates the position of the current operation in the trace tree. The value should not be interpreted: it may or may not be derived from the value of the TraceId.

### Http Header format
The `X-B3-SpanId` header is required and is encoded as 16 hex characters. For example, a SpanId header might look like: `X-B3-SpanId: a2fb4a1d1a96d312`

## ParentSpanId

The ParentSpanId is 64-bit in length and indicates the position of the parent operation in the trace tree. When the span is the root of the trace tree, the ParentSpanId is absent.

### Http Header format
The `X-B3-ParentSpanId` header must be present on a child span and absent on the root span. It is encoded as 16 hex characters. For example, a ParentSpanId header might look like: `X-B3-ParentSpanId: 0020000000000001`

# Flags
The following flags are reported either in a flag set or separate attributes.

## Sampled

When the Sampled flag is 1, report this span to the tracing system. When it is 0, do not. When B3 attributes are sent without the Sampled flag, the receiver should make the decision. Once Sampled is set to 0 or 1, the same value should be consistently sent downstream.

### Http Header format
The `X-B3-Sampled` header is encoded as "1" or "0". Absent means defer the decision to the receiver of this header. For example, a Sampled header might look like: `X-B3-Sampled: 1`

### Details

It may not be obvious why you'd send Sampled=0 to the next hop. Imagine a service decides not to trace an operation and makes 2 out-going calls, and these branched out further. If 0 ("don't trace") isn't propagated, the system might receive only parts of the operation, confusing users.

Leaving Sampled absent is special-case. The only known use-cases are the following:

* Debug trace: When setting Flags to 1, sampling is implicit
* Externally provisioned IDs: When you want to control IDs, but not sampling policy

Unless it is a debug trace, leaving sampled unset is typically for ID correlation. For example, someone re-uses a global identifier from another system, or correlating in logs. In these cases, the caller knows the ID they want, but allows the next hop to decide if it will be traced or not. The caller should not report a span to the tracing system using this ID unless they propagate Sampled=1.

## Debug
When Debug is set, the trace should be reported to the tracing system and also override any collection-tier sampling policy. Debug implies Sampled.

### Http Header format
Debug is encoded as `X-B3-Flags: 1`. Since Debug implies Sampled, so don't also send "X-B3-Sampled: 1".

