package dev.flyaway.perf.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached, spread-adapted MethodHandles for Architectury's proxy-based events.
 * Each handle has the shape (Object listener, Object[] args) -> Object.
 */
public final class ArchEventHandles {
    private static final ConcurrentHashMap<Method, MethodHandle> CACHE = new ConcurrentHashMap<>();
    private static final Object[] NO_ARGS = new Object[0];

    private ArchEventHandles() {}

    private static MethodHandle build(Method method) {
        try {
            int n = method.getParameterCount();
            return MethodHandles.lookup().unreflect(method)
                    .asType(MethodType.genericMethodType(n + 1))
                    .asSpreader(Object[].class, n);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object invoke(Object listener, Method method, Object[] args) throws Throwable {
        MethodHandle mh = CACHE.get(method);
        if (mh == null) mh = CACHE.computeIfAbsent(method, ArchEventHandles::build);
        Object[] a = args == null ? NO_ARGS : args;
        return (Object) mh.invokeExact(listener, (Object[]) a);
    }
}
