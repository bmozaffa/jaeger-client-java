/*
 * Copyright (c) 2017, Uber Technologies, Inc
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

package com.uber.jaeger.baggage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.jaeger.LogData;
import com.uber.jaeger.Span;
import com.uber.jaeger.SpanContext;
import com.uber.jaeger.Tracer;
import com.uber.jaeger.metrics.InMemoryStatsReporter;
import com.uber.jaeger.metrics.Metrics;
import com.uber.jaeger.metrics.StatsFactoryImpl;
import com.uber.jaeger.reporters.InMemoryReporter;
import com.uber.jaeger.samplers.ConstSampler;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

public class BaggageSetterTest {

  private InMemoryReporter reporter;
  private Tracer tracer;
  private Span span;
  private InMemoryStatsReporter metricsReporter;
  private Metrics metrics;
  private BaggageRestrictionManager mgr;
  private BaggageSetter setter;

  private static final String KEY = "key";
  private static final String SERVICE = "SamplerTest";

  @Before
  public void setUp() throws Exception {
    metricsReporter = new InMemoryStatsReporter();
    reporter = new InMemoryReporter();
    metrics = new Metrics(new StatsFactoryImpl(metricsReporter));
    mgr = mock(DefaultBaggageRestrictionManager.class);
    setter = new BaggageSetter(mgr, metrics);
    tracer =
        new Tracer.Builder(SERVICE, reporter, new ConstSampler(true))
            .withStatsReporter(metricsReporter)
            .build();
    span = (Span) tracer.buildSpan("some-operation").startManual();
  }

  @Test
  public void testInvalidBaggage() {
    when(mgr.getRestriction(SERVICE, KEY)).thenReturn(Restriction.of(false, 0));

    final String value = "value";
    SpanContext ctx = setter.setBaggage(span, KEY, value);

    assertBaggageLogs(span, KEY, value, false, false, true);
    assertNull(ctx.getBaggageItem(KEY));

    assertEquals(
        1L, metricsReporter.counters.get("jaeger.baggage-update.result=err").longValue());
  }

  @Test
  public void testTruncatedBaggage() {
    when(mgr.getRestriction(SERVICE, KEY)).thenReturn(Restriction.of(true, 5));
    final String value = "0123456789";
    final String expected = "01234";
    SpanContext ctx = setter.setBaggage(span, KEY, value);

    assertBaggageLogs(span, KEY, expected, true, false, false);
    assertEquals(expected, ctx.getBaggageItem(KEY));

    assertEquals(
        1L, metricsReporter.counters.get("jaeger.baggage-truncate").longValue());
    assertEquals(
        1L, metricsReporter.counters.get("jaeger.baggage-update.result=ok").longValue());
  }

  @Test
  public void testOverrideBaggage() {
    when(mgr.getRestriction(SERVICE, KEY)).thenReturn(Restriction.of(true, 5));
    final String value = "value";
    SpanContext ctx = setter.setBaggage(span, KEY, value);
    Span child = (Span) tracer.buildSpan("some-operation").asChildOf(ctx).startManual();
    ctx = setter.setBaggage(child, KEY, value);

    assertBaggageLogs(child, KEY, value, false, true, false);
    assertEquals(value, ctx.getBaggageItem(KEY));

    assertEquals(
        2L, metricsReporter.counters.get("jaeger.baggage-update.result=ok").longValue());
  }

  @Test
  public void testUnsampledSpan() {
    tracer =
        new Tracer.Builder("SamplerTest", reporter, new ConstSampler(false))
            .withStatsReporter(metricsReporter)
            .build();
    span = (Span) tracer.buildSpan("some-operation").startManual();

    when(mgr.getRestriction(SERVICE, KEY)).thenReturn(Restriction.of(true, 5));
    final String value = "value";
    SpanContext ctx = setter.setBaggage(span, KEY, value);

    assertEquals(value, ctx.getBaggageItem(KEY));
    // No logs should be written if the span is not sampled
    assertNull(span.getLogs());
  }

  @Test
  public void testBaggageNullValueTolerated() {
    when(mgr.getRestriction(SERVICE, KEY)).thenReturn(Restriction.of(true, 5));
    final String value = null;
    SpanContext ctx = setter.setBaggage(span, KEY, value);

    assertBaggageLogs(span, KEY, null, false, false, false);
    assertNull(ctx.getBaggageItem(KEY));
  }

  @Test
  public void testBaggageNullRemoveValue() {
    when(mgr.getRestriction(SERVICE, KEY)).thenReturn(Restriction.of(true, 5));
    final String value = "value";
    SpanContext ctx = setter.setBaggage(span, KEY, value);
    Span child = (Span) tracer.buildSpan("some-operation").asChildOf(ctx).startManual();
    ctx = setter.setBaggage(child, KEY, null);

    assertBaggageLogs(child, KEY, null, false, true, false);
    assertNull(ctx.getBaggageItem(KEY));

    assertEquals(
            2L, metricsReporter.counters.get("jaeger.baggage-update.result=ok").longValue());
  }

  private void assertBaggageLogs(
      Span span,
      String key,
      String value,
      boolean truncate,
      boolean override,
      boolean invalid
  ) {
    List<LogData> logs = span.getLogs();
    assertEquals(false, logs.isEmpty());
    Map<String, ?> fields = logs.get(logs.size() - 1).getFields();
    assertEquals("baggage", fields.get("event"));
    assertEquals(key, fields.get("key"));
    assertEquals(value, fields.get("value"));
    if (truncate) {
      assertEquals("true", fields.get("truncated"));
    }
    if (override) {
      assertEquals("true", fields.get("override"));
    }
    if (invalid) {
      assertEquals("true", fields.get("invalid"));
    }
  }
}
