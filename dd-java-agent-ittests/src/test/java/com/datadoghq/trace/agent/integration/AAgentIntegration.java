package com.datadoghq.trace.agent.integration;

import com.datadoghq.trace.DDTracer;
import com.datadoghq.trace.writer.ListWriter;
import io.opentracing.util.GlobalTracer;
import java.lang.reflect.Field;
import org.junit.Before;

public class AAgentIntegration {

  protected static ListWriter writer = new ListWriter();
  protected static DDTracer tracer = new DDTracer(writer);

  @Before
  public void beforeTest() throws Exception {
    try {
      GlobalTracer.register(tracer);
    } catch (Exception e) {
      // Force it anyway using reflexion
      final Field field = GlobalTracer.class.getDeclaredField("tracer");
      field.setAccessible(true);
      field.set(null, tracer);
    }
    writer.start();
  }
}