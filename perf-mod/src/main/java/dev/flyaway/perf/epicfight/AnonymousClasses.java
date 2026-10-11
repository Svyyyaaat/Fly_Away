package dev.flyaway.perf.epicfight;

/**
 * Cached {@link Class#isAnonymousClass()}. The JDK implementation is not cached: every call goes through the
 * native {@code getEnclosingMethod0} and parses the InnerClasses attribute. A class' anonymity never changes,
 * so a {@link ClassValue} answers it with one lookup.
 */
public final class AnonymousClasses {
    private static final ClassValue<Boolean> ANON = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.isAnonymousClass();
        }
    };

    private AnonymousClasses() {}

    public static boolean isAnonymous(Class<?> type) {
        return ANON.get(type);
    }
}
