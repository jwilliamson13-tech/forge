package forge.ai;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class AiCache {

    // stores result + args as vector
    private final static Multimap<String, List<Object>> dataMap = Multimaps.synchronizedMultimap(ArrayListMultimap.create());

    public static boolean identity(Object a, Object b) {
        return a == b;
    }

    /**
     * Compares an argument by identity, like {@link #identity}, and lets the cache hold that argument weakly. Only an
     * argument compared this way can be: an entry matches it only while a caller passes that very object, and an object
     * a caller passes is reachable, so letting go of one that nothing else holds changes no lookup. The full-sim search's
     * game copies are such arguments. Held strongly, every copy a decision made stayed alive until the next decision
     * cleared the cache, which ran forge sim out of heap on large boards.
     */
    public static final BiFunction<Object, Object, Boolean> IDENTITY = AiCache::identity;

    /** An argument the cache holds weakly. Once it is cleared, the entry can never be hit again. */
    private static final class WeakArg extends WeakReference<Object> {
        WeakArg(Object referent) {
            super(referent);
        }
    }

    private static final Object CLEARED = new Object();

    /** Only for tests; the cache can be reached from more than one thread, so the count is a rough one there. */
    private static volatile int weakArgsStored;

    /** The argument an entry holds at {@code index}, or {@code cleared} when it was held weakly and is gone. */
    private static Object argOf(List<Object> cached, int index, Object cleared) {
        Object stored = cached.get(index);
        if (stored instanceof WeakArg weak) {
            Object arg = weak.get();
            return arg == null ? cleared : arg;
        }
        return stored;
    }

    // the cache is global for calculations that can be shared between games
    // but that also means unwanted collisions need to be considered:
    // for that you can pass Functions that compare the args
    public static <T> T getCached(String key, Supplier<T> func, List<BiFunction<Object, Object, Boolean>> argsCheck, Object... args) {
        // TODO would like a good strategy to derive default key, but there's no clean way to obtain the method name
        for (List<Object> cached : Lists.newArrayList(dataMap.get(key))) {
            boolean hit = true;
            for (int i = 0; i < args.length; i++) {
                Object arg = argOf(cached, i + 1, CLEARED);
                if (arg == CLEARED) {
                    // what it was keyed to is gone, so it can't match: drop it to keep the scan short
                    dataMap.remove(key, cached);
                    hit = false;
                    break;
                }
                BiFunction<Object, Object, Boolean> checker = argsCheck == null ? Object::equals : argsCheck.get(i);
                if (!checker.apply(args[i], arg)) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return (T) cached.get(0);
            }
        }
        T result = func.get();
        List<Object> cached = Lists.newArrayList(result);
        for (int i = 0; i < args.length; i++) {
            boolean weak = args[i] != null && argsCheck != null && argsCheck.get(i) == IDENTITY;
            if (weak) {
                cached.add(new WeakArg(args[i]));
                weakArgsStored++;
            } else {
                cached.add(args[i]);
            }
        }
        dataMap.put(key, cached);
        return result;
    }

    // TODO add different scopes + staleness indicator
    public static void clear() {
        dataMap.clear();
        weakArgsStored = 0;
    }

    /** For tests: how many entries the cache holds, counting those whose weakly held argument is gone. */
    public static int size() {
        return dataMap.size();
    }

    /** For tests: how many arguments the cache has held weakly since it was last cleared. */
    public static int weakArgsStored() {
        return weakArgsStored;
    }

    /** For tests: how many entries hold an argument, still there, that {@code test} accepts. */
    public static int countEntriesWithArg(Predicate<Object> test) {
        return countEntries(test, true);
    }

    /** For tests: how many entries hold strongly an argument that {@code test} accepts, so that it can't be collected. */
    public static int countEntriesHoldingStrongly(Predicate<Object> test) {
        return countEntries(test, false);
    }

    private static int countEntries(Predicate<Object> test, boolean includeWeak) {
        List<List<Object>> entries;
        synchronized (dataMap) {
            entries = new ArrayList<>(dataMap.values());
        }
        int count = 0;
        for (List<Object> cached : entries) {
            for (int i = 1; i < cached.size(); i++) {
                if (!includeWeak && cached.get(i) instanceof WeakArg) {
                    continue;
                }
                Object arg = argOf(cached, i, null);
                if (arg != null && test.test(arg)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

}
