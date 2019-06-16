# Status of instrumentation libraries and B3 support

This is a list of zipkin libraries and their status in supporting b3 features

| language   | library | 128-bit trace ID | b3 single format | notes |
| ---------- | ------- | ---------------- | ---------------- | ----- |
| javascript | [zipkin-js](https://github.com/openzipkin/zipkin-js) | v0.5+ [supported](https://github.com/openzipkin/zipkin-js/blob/a8ab73d26157a3b25b207425e2808ee39105afa1/packages/zipkin/README.md#usage) | [unsupported](https://github.com/openzipkin/zipkin-js/issues/259) | |
| python     | [py_zipkin](https://github.com/Yelp/py_zipkin) | 0.8+ [supported](https://github.com/Yelp/py_zipkin/blob/665428305ba3cadec5e6488c801bdaca12b3e311/py_zipkin/zipkin.py#L164) | [unsupported](https://github.com/Yelp/py_zipkin/issues/98) | |
| java       | [brave](https://github.com/openzipkin/brave) | 3.15+/4.9+ [supported/epoch128](https://github.com/openzipkin/brave/tree/master/brave-core#128-bit-trace-ids) | 5.3+ [supported](https://github.com/apache/incubator-zipkin-brave/blob/5a17fc018613958db00cc7b6951826e95f1b9d6c/brave/src/main/java/brave/propagation/B3SingleFormat.java) | |
| scala      | [finagle](https://github.com/twitter/finagle) | 0.40+ downgrade | [in progress](https://github.com/twitter/finagle/pull/749) | |
| ruby       | [zipkin-ruby](https://github.com/openzipkin/zipkin-ruby) | 0.28+ [supported](https://github.com/openzipkin/zipkin-ruby/blob/ec875f3640dc2ce8070969f3b0c889d7b7121063/README.md#configuration-options) | [unsupported](https://github.com/openzipkin/zipkin-ruby/issues/126) | |
| go         | [zipkin-go](https://github.com/openzipkin/zipkin-go) | 0.1+ [supported](https://godoc.org/github.com/openzipkin/zipkin-go#WithTraceID128Bit) | 0.1+ [supported](https://godoc.org/github.com/openzipkin/zipkin-go/propagation/b3#InjectOption) | |
| go         | [zipkin-go-opentracing](https://github.com/openzipkin/zipkin-go-opentracing) | 0.2+ [supported](https://godoc.org/github.com/openzipkin/zipkin-go-opentracing#TraceID128Bit) | unsupported | |
| php        | [zipkin-php](https://github.com/openzipkin/zipkin-php) | 1+ [supported](https://github.com/openzipkin/zipkin-php/blob/b143bf577b5b328df656dede4f6b8aeec38b201a/src/Zipkin/TracingBuilder.php#L122) | [unsupported](https://github.com/openzipkin/zipkin-php/issues/92) | |
| java       | [wingtips](https://github.com/Nike-Inc/wingtips) | 0.11.2+ transparent | [unsupported](https://github.com/Nike-Inc/wingtips/issues/80) | |
| java       | [jaeger](https://github.com/uber/jaeger-client-java) | 0.10+ downgrade | unsupported | | 
| csharp     | [zipkin4net](https://github.com/openzipkin/zipkin4net) | 0.4+ supported | [supported](https://github.com/openzipkin/zipkin4net/blob/c30d8244cad5c219f9b891a513b0032f0047a2fd/Src/zipkin4net/Src/Propagation/B3SingleFormat.cs) | | 

## 128-bit trace ID

A 128-bit trace ID is when `X-B3-TraceId` has 32 hex characters as opposed to 16. For example, `X-B3-TraceId: 463ac35c9f6413ad48485a3953bb6124`.

Here are the status options for 128bit `X-B3-TraceId`:
* unsupported: 32 character trace ids will break the library
* downgrade: Read 32 character trace ids by throwing away the high bits (any characters left of 16 characters). This effectively downgrades the ID to 64 bits.
* transparent: Pass `X-B3-TraceId` through the system without interpreting it.
* supported:  Can start traces with 128-bit trace IDs, propagates and reports them as 128-bits
* epoch128: Can start traces with 128-bit trace IDs which are prefixed with epoch seconds

### epoch128
When a trace ID is 128-bits and the first 32 bits are epoch seconds, the ID can be translated into
an Amazon Web Services ID. Tracers who do this can tunnel through ELBs, for example.

Here's an example implementation
```java
  static long nextTraceIdHigh(Random prng) {
    long epochSeconds = System.currentTimeMillis() / 1000;
    int random = prng.nextInt();
    return (epochSeconds & 0xffffffffL) << 32
        |  (random & 0xffffffffL);
  }
```

See openzipkin/zipkin#1754
