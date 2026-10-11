package dev.flyaway.perf.mixin.compat.architectury;

import dev.architectury.event.EventFactory;
import dev.flyaway.perf.compat.ArchEventHandles;
import java.lang.reflect.Method;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Architectury dispatches every proxy event (tick events of every Architectury-based mod, on client and server)
 * through EventFactory.invokeMethod, which did MethodHandles.lookup().unreflect(method).bindTo(listener)
 * .invokeWithArguments(args) for EVERY listener on EVERY call: a reflective lookup + a fresh bound handle + the slow
 * generic invoker each time (~2% of the integrated server tick in the user's server profile, more on the client).
 * We cache one (listener, Object[]) -> Object handle per Method and call it with invokeExact. Same target, same
 * arguments, same boxing of the result, same exceptions.
 */
@Mixin(value = EventFactory.class, remap = false)
public abstract class EventFactoryHandleCacheMixin {
    /**
     * @author flyaway_perf
     * @reason cache the MethodHandle instead of unreflecting on every event dispatch
     */
    @SuppressWarnings("unchecked")
    @Overwrite
    private static <T, R> R invokeMethod(T listener, Method method, Object[] args) throws Throwable {
        return (R) ArchEventHandles.invoke(listener, method, args);
    }
}
