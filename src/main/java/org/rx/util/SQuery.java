package org.rx.util;

import com.alibaba.fastjson.JSON;
import org.rx.common.InvalidOperationException;
import org.rx.common.Tuple;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.rx.common.Contract.*;

public class SQuery<T> {
    @FunctionalInterface
    public interface IndexSelector<T, TR> {
        TR apply(T t, long index);
    }

    @FunctionalInterface
    public interface IndexPredicate<T> {
        boolean test(T t, long index);

        default IndexPredicate<T> negate() {
            return (t, i) -> !test(t, i);
        }
    }

    //region of
    private static final Comparator NaturalOrder = Comparator.naturalOrder(), ReverseOrder = Comparator.reverseOrder();

    public static <T> SQuery<T> of(Collection<T> set) {
        require(set);

        return of(set.stream());
    }

    public static <T> SQuery<T> of(Stream<T> stream) {
        require(stream);

        return new SQuery<>(stream);
    }
    //endregion

    //region Member
    private Stream current;

    public Stream<T> stream() {
        return current;
    }

    private SQuery(Stream<T> stream) {
        current = stream;
    }

    private <TR> List<TR> newList() {
        return current.isParallel() ? Collections.synchronizedList(new ArrayList<>()) : new ArrayList<>();
    }

    private <TK, TR> Map<TK, TR> newMap() {
        return current.isParallel() ? new ConcurrentHashMap<>() : new HashMap<>();
    }

    private <TR> Stream<TR> toStream(Collection<TR> set) {
        return current.isParallel() ? set.parallelStream() : set.stream();
    }

    private <TR> SQuery<TR> me(Stream<TR> stream) {
        current = stream;
        return (SQuery<TR>) this;
    }

    private SQuery<T> me(EachFunc<T> func) {
        return me(stream(), func);
    }

    private <TR> SQuery<TR> me(Stream<TR> stream, EachFunc<TR> func) {
        boolean isParallel = stream.isParallel();
        Spliterator<TR> spliterator = stream.spliterator();
        return me(StreamSupport.stream(
                new Spliterators.AbstractSpliterator<TR>(spliterator.estimateSize(), spliterator.characteristics()) {
                    AtomicBoolean breaker = new AtomicBoolean();
                    AtomicLong counter = new AtomicLong();

                    @Override
                    public boolean tryAdvance(Consumer action) {
                        return spliterator.tryAdvance(p -> {
                            //                            StringBuilder log = new StringBuilder();
                            //                            log.appendLine("start %s %s...", JSON.toJSONString(p), counter.get());
                            int flags = func.each(p, counter.getAndIncrement());
                            if ((flags & EachFunc.Accept) == EachFunc.Accept) {
                                action.accept(p);
                            }
                            if ((flags & EachFunc.Break) == EachFunc.Break) {
                                breaker.set(true);
                            }
                            //                            log.appendLine("end %s...", flags);
                            //                            System.out.println(log);
                        }) && !breaker.get();
                    }
                }, isParallel));
    }

    @FunctionalInterface
    private interface EachFunc<T> {
        int None   = 0;
        int Accept = 1;
        int Break  = 1 << 1;
        int All    = Accept | Break;

        int each(T t, long index);
    }
    //endregion

    public <TR> SQuery<TR> select(Function<T, TR> selector) {
        return me(stream().map(selector));
    }

    public <TR> SQuery<TR> select(IndexSelector<T, TR> selector) {
        List<TR> result = newList();
        AtomicLong counter = new AtomicLong();
        stream().forEach(t -> result.add(selector.apply(t, counter.getAndIncrement())));
        return me(toStream(result));
    }

    public <TR> SQuery<TR> selectMany(Function<T, Stream<TR>> selector) {
        return me(stream().flatMap(selector));
    }

    public <TR> SQuery<TR> selectMany(IndexSelector<T, Stream<TR>> selector) {
        List<TR> result = newList();
        AtomicLong counter = new AtomicLong();
        stream().forEach(t -> selector.apply(t, counter.getAndIncrement()).forEach(result::add));
        return me(toStream(result));
    }

    public SQuery<T> where(Predicate<T> predicate) {
        return me(stream().filter(predicate));
    }

    public SQuery<T> where(IndexPredicate<T> predicate) {
        List<T> result = newList();
        AtomicLong counter = new AtomicLong();
        stream().forEach(t -> {
            if (!predicate.test(t, counter.getAndIncrement())) {
                return;
            }
            result.add(t);
        });
        return me(toStream(result));
    }

    public <TI, TR> SQuery<TR> join(Stream<TI> inner, BiPredicate<T, TI> keySelector,
                                    BiFunction<T, TI, TR> resultSelector) {
        return me(stream()
                .flatMap(p -> inner.filter(p2 -> keySelector.test(p, p2)).map(p3 -> resultSelector.apply(p, p3))));
    }

    public <TI, TR> SQuery<TR> join(Function<T, TI> innerSelector, BiPredicate<T, TI> keySelector,
                                    BiFunction<T, TI, TR> resultSelector) {
        List<TI> inner = newList();
        stream().forEach(t -> inner.add(innerSelector.apply(t)));
        return join(toStream(inner), keySelector, resultSelector);
    }

    public <TI, TR> SQuery<TR> joinMany(Function<T, Stream<TI>> innerSelector, BiPredicate<T, TI> keySelector,
                                        BiFunction<T, TI, TR> resultSelector) {
        List<TI> inner = newList();
        stream().forEach(t -> innerSelector.apply(t).forEach(inner::add));
        return join(toStream(inner), keySelector, resultSelector);
    }

    public boolean all(Predicate<T> predicate) {
        return stream().allMatch(predicate);
    }

    public boolean any() {
        return stream().findAny().isPresent();
    }

    public boolean any(Predicate<T> predicate) {
        return stream().anyMatch(predicate);
    }

    public boolean contains(T item) {
        return stream().anyMatch(p -> p.equals(item));
    }

    public SQuery<T> concat(Stream<T> set) {
        return me(Stream.concat(stream(), set));
    }

    public SQuery<T> distinct() {
        return me(stream().distinct());
    }

    public SQuery<T> except(Stream<T> set) {
        return me(stream().filter(p -> !set.anyMatch(p2 -> p2.equals(p))));
    }

    public SQuery<T> intersect(Stream<T> set) {
        return me(stream().filter(p -> set.anyMatch(p2 -> p2.equals(p))));
    }

    public SQuery<T> union(Stream<T> set) {
        return concat(set);
    }

    public <TK> SQuery<T> orderBy(Function<T, TK> keySelector) {
        return me(stream().sorted(getComparator(keySelector)));
    }

    private <TK> Comparator<T> getComparator(Function<T, TK> keySelector) {
        return (p1, p2) -> {
            Comparable c1 = as(keySelector.apply(p1), Comparable.class);
            if (c1 == null) {
                return 0;
            }
            return c1.compareTo(keySelector.apply(p2));
        };
    }

    public <TK> SQuery<T> orderByDescending(Function<T, TK> keySelector) {
        return me(stream().sorted(getComparator(keySelector).reversed()));
    }

    public SQuery<T> orderByMany(Function<T, Object[]> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector)));
    }

    private Comparator<T> getComparatorMany(Function<T, Object[]> keySelector) {
        return (p1, p2) -> {
            Object[] k1s = keySelector.apply(p1);
            Object[] k2s = keySelector.apply(p2);
            for (int i = 0; i < k1s.length; i++) {
                Comparable c1 = as(k1s[i], Comparable.class);
                if (c1 == null) {
                    continue;
                }
                int r = c1.compareTo(k2s[i]);
                if (r == 0) {
                    continue;
                }
                return r;
            }
            return 0;
        };
    }

    public SQuery<T> orderByDescendingMany(Function<T, Object[]> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector).reversed()));
    }

    public SQuery<T> reverse() {
        return me(stream().sorted((Comparator<T>) ReverseOrder));
    }

    public <TK, TR> SQuery<TR> groupBy(Function<T, TK> keySelector, Function<Tuple<TK, SQuery<T>>, TR> resultSelector) {
        Map<TK, List<T>> map = newMap();
        stream().forEach(t -> map.computeIfAbsent(keySelector.apply(t), p -> newList()).add(t));
        List<TR> result = newList();
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.add(resultSelector.apply(Tuple.of(entry.getKey(), of(entry.getValue()))));
        }
        return me(toStream(result));
    }

    public <TR> SQuery<TR> groupByMany(Function<T, Object[]> keySelector,
                                       Function<Tuple<Object[], SQuery<T>>, TR> resultSelector) {
        Map<String, Tuple<Object[], List<T>>> map = newMap();
        stream().forEach(t -> {
            Object[] ks = keySelector.apply(t);
            map.computeIfAbsent(toJSONString(ks), p -> Tuple.of(ks, newList())).right.add(t);
        });
        List<TR> result = newList();
        for (Tuple<Object[], List<T>> entry : map.values()) {
            result.add(resultSelector.apply(Tuple.of(entry.left, of(entry.right))));
        }
        return me(toStream(result));
    }

    public Double average(ToDoubleFunction<T> selector) {
        OptionalDouble q = stream().mapToDouble(selector).average();
        return q.isPresent() ? q.getAsDouble() : null;
    }

    public long count() {
        return stream().count();
    }

    public long count(Predicate<T> predicate) {
        return stream().filter(predicate).count();
    }

    public T max() {
        return max(stream());
    }

    private <TR> TR max(Stream<TR> stream) {
        return stream.max((Comparator<TR>) NaturalOrder).orElse(null);
    }

    public <TR> TR max(Function<T, TR> selector) {
        return max(stream().map(selector));
    }

    public T min() {
        return min(stream());
    }

    private <TR> TR min(Stream<TR> stream) {
        return stream.min((Comparator<TR>) NaturalOrder).orElse(null);
    }

    public <TR> TR min(Function<T, TR> selector) {
        return min(stream().map(selector));
    }

    public double sum(ToDoubleFunction<T> selector) {
        return stream().mapToDouble(selector).sum();
    }

    public T first() {
        return stream().findFirst().get();
    }

    public T first(Predicate<T> predicate) {
        return where(predicate).first();
    }

    public T firstOrDefault() {
        return stream().findFirst().orElse(null);
    }

    public T firstOrDefault(Predicate<T> predicate) {
        return where(predicate).firstOrDefault();
    }

    public T last() {
        return stream().reduce((a, b) -> b).get();
    }

    public T last(Predicate<T> predicate) {
        return where(predicate).last();
    }

    public T lastOrDefault() {
        return stream().reduce((a, b) -> b).orElse(null);
    }

    public T lastOrDefault(Predicate<T> predicate) {
        return where(predicate).lastOrDefault();
    }

    public T single() {
        long count = stream().count();
        if (count != 1) {
            throw new InvalidOperationException("Require 1 element, current is %s elements", count);
        }
        return first();
    }

    public T single(Predicate<T> predicate) {
        return where(predicate).single();
    }

    public T singleOrDefault() {
        long count = stream().count();
        if (count > 1) {
            throw new InvalidOperationException("Require 1 element, current is %s elements", count);
        }
        return firstOrDefault();
    }

    public T singleOrDefault(Predicate<T> predicate) {
        return where(predicate).singleOrDefault();
    }

    public SQuery<T> skip(long count) {
        return me(stream().skip(count));
    }

    public SQuery<T> skipWhile(Predicate<T> predicate) {
        return skipWhile((p, i) -> predicate.test(p));
    }

    public SQuery<T> skipWhile(IndexPredicate<T> predicate) {
        AtomicBoolean doAccept = new AtomicBoolean();
        return me((p, i) -> {
            int flags = EachFunc.None;
            if (doAccept.get()) {
                flags |= EachFunc.Accept;
                return flags;
            }
            if (!predicate.test(p, i)) {
                doAccept.set(true);
                flags |= EachFunc.Accept;
            }
            return flags;
        });
    }

    public SQuery<T> take(long count) {
        return me(stream().limit(count));
    }

    public SQuery<T> takeWhile(Predicate<T> predicate) {
        return takeWhile((p, i) -> predicate.test(p));
    }

    public SQuery<T> takeWhile(IndexPredicate<T> predicate) {
        return me((p, i) -> {
            int flags = EachFunc.None;
            if (!predicate.test(p, i)) {
                flags |= EachFunc.Break;
                return flags;
            }
            flags |= EachFunc.Accept;
            return flags;
        });
    }

    public List<T> toList() {
        boolean isParallel = stream().isParallel();
        List<T> result = stream().collect(Collectors.toList());
        return isParallel ? Collections.synchronizedList(result) : result;
    }

    public Set<T> toSet() {
        boolean isParallel = stream().isParallel();
        Set<T> result = stream().collect(Collectors.toSet());
        if (isParallel) {
            Set<T> set = ConcurrentHashMap.newKeySet();
            set.addAll(result);
            return set;
        }
        return result;
    }

    public <TK> Map<TK, T> toMap(Function<T, TK> keySelector) {
        return toMap(keySelector, p -> p);
    }

    public <TK, TR> Map<TK, TR> toMap(Function<T, TK> keySelector, Function<T, TR> resultSelector) {
        boolean isParallel = stream().isParallel();
        return isParallel ? stream().collect(Collectors.toConcurrentMap(keySelector, resultSelector))
                : stream().collect(Collectors.toMap(keySelector, resultSelector));
    }

    public static void main(String[] args) {
        Set<Person> personSet = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            Person p = new Person();
            p.index = i;
            p.index2 = i % 2 == 0 ? 2 : i;
            p.index3 = i % 2 == 0 ? 3 : 4;
            p.name = App.randomString(5);
            p.age = ThreadLocalRandom.current().nextInt(100);
            personSet.add(p);
        }
        Person px = new Person();
        px.index2 = 2;
        px.index3 = 41;
        personSet.add(px);

        showResult("groupBy(p -> p.index2...", of(personSet).groupBy(p -> p.index2, p -> {
            System.out.println("groupKey: " + p.left);
            List<Person> list = p.right.toList();
            System.out.println("items: " + JSON.toJSONString(list));
            return list.get(0);
        }));
        showResult("groupByMany(p -> new Object[] { p.index2, p.index3 })",
                of(personSet).groupByMany(p -> new Object[] { p.index2, p.index3 }, p -> {
                    System.out.println("groupKey: " + toJSONString(p.left));
                    List<Person> list = p.right.toList();
                    System.out.println("items: " + toJSONString(list));
                    return list.get(0);
                }));

        showResult("orderBy(p->p.index)", of(personSet).orderBy(p -> p.index));
        showResult("orderByDescending(p->p.index)", of(personSet).orderByDescending(p -> p.index));
        showResult("orderByMany(p -> new Object[] { p.index2, p.index })",
                of(personSet).orderByMany(p -> new Object[] { p.index2, p.index }));
        showResult("orderByDescendingMany(p -> new Object[] { p.index2, p.index })",
                of(personSet).orderByDescendingMany(p -> new Object[] { p.index2, p.index }));

        showResult("select(p -> p.index).reverse()",
                of(personSet).orderBy(p -> p.index).select(p -> p.index).reverse());

        showResult(".max(p -> p.index)", of(personSet).<Integer> max(p -> p.index));
        showResult(".min(p -> p.index)", of(personSet).<Integer> min(p -> p.index));

        showResult("take(0).average(p -> p.index)", of(personSet).take(0).average(p -> p.index));
        showResult("average(p -> p.index)", of(personSet).average(p -> p.index));
        showResult("take(0).sum(p -> p.index)", of(personSet).take(0).sum(p -> p.index));
        showResult("sum(p -> p.index)", of(personSet).sum(p -> p.index));

        showResult("firstOrDefault()", of(personSet).orderBy(p -> p.index).firstOrDefault());
        showResult("lastOrDefault()", of(personSet).orderBy(p -> p.index).lastOrDefault());
        showResult("skip(2)", of(personSet).orderBy(p -> p.index).skip(2));
        showResult("take(2)", of(personSet).orderBy(p -> p.index).take(2));

        showResult(".skipWhile((p, i) -> p.index < 3)",
                of(personSet).orderBy(p -> p.index).skipWhile((p, i) -> p.index < 3));

        showResult(".takeWhile((p, i) -> p.index < 3)",
                of(personSet).orderBy(p -> p.index).takeWhile((p, i) -> p.index < 3));
    }

    private static void showResult(String n, Object q) {
        System.out.println();
        System.out.println();
        System.out.println("showResult: " + n);
        System.out.println(JSON.toJSONString(q));
    }

    private static void showResult(String n, SQuery q) {
        System.out.println();
        System.out.println();
        System.out.println("showResult: " + n);
        System.out.println(JSON.toJSONString(q.toList()));
    }

    public static class Person {
        public int    index;
        public int    index2;
        public int    index3;
        public String name;
        public int    age;
    }
}
