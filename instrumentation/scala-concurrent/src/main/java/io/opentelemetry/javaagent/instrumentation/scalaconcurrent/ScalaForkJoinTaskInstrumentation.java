/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.scalaconcurrent;

import static io.opentelemetry.javaagent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.extendsClass;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.instrumentation.api.ContextStore;
import io.opentelemetry.javaagent.instrumentation.api.InstrumentationContext;
import io.opentelemetry.javaagent.instrumentation.api.concurrent.AdviceUtils;
import io.opentelemetry.javaagent.instrumentation.api.concurrent.State;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.concurrent.forkjoin.ForkJoinPool;
import scala.concurrent.forkjoin.ForkJoinTask;

/**
 * Instrument {@link ForkJoinTask}.
 *
 * <p>Note: There are quite a few separate implementations of {@code ForkJoinTask}/{@code
 * ForkJoinPool}: JVM, Akka, Scala, Netty to name a few. This class handles Scala version.
 */
@AutoService(Instrumenter.class)
public final class ScalaForkJoinTaskInstrumentation extends Instrumenter.Default {

  static final String TASK_CLASS_NAME = "scala.concurrent.forkjoin.ForkJoinTask";

  public ScalaForkJoinTaskInstrumentation() {
    super("java_concurrent", "scala_concurrent");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed(TASK_CLASS_NAME);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return extendsClass(named(TASK_CLASS_NAME));
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> map = new HashMap<>();
    map.put(Runnable.class.getName(), State.class.getName());
    map.put(Callable.class.getName(), State.class.getName());
    map.put(TASK_CLASS_NAME, State.class.getName());
    return Collections.unmodifiableMap(map);
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("exec").and(takesArguments(0)).and(not(isAbstract())),
        ScalaForkJoinTaskInstrumentation.class.getName() + "$ForkJoinTaskAdvice");
  }

  public static class ForkJoinTaskAdvice {

    /**
     * When {@link ForkJoinTask} object is submitted to {@link ForkJoinPool} as {@link Runnable} or
     * {@link Callable} it will not get wrapped, instead it will be casted to {@code ForkJoinTask}
     * directly. This means state is still stored in {@code Runnable} or {@code Callable} and we
     * need to use that state.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope enter(@Advice.This ForkJoinTask thiz) {
      ContextStore<ForkJoinTask, State> contextStore =
          InstrumentationContext.get(ForkJoinTask.class, State.class);
      Scope scope = AdviceUtils.startTaskScope(contextStore, thiz);
      if (thiz instanceof Runnable) {
        ContextStore<Runnable, State> runnableContextStore =
            InstrumentationContext.get(Runnable.class, State.class);
        Scope newScope = AdviceUtils.startTaskScope(runnableContextStore, (Runnable) thiz);
        if (null != newScope) {
          if (null != scope) {
            newScope.close();
          } else {
            scope = newScope;
          }
        }
      }
      if (thiz instanceof Callable) {
        ContextStore<Callable, State> callableContextStore =
            InstrumentationContext.get(Callable.class, State.class);
        Scope newScope = AdviceUtils.startTaskScope(callableContextStore, (Callable) thiz);
        if (null != newScope) {
          if (null != scope) {
            newScope.close();
          } else {
            scope = newScope;
          }
        }
      }
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Scope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
