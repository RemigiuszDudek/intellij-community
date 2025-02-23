// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.containers;

import com.intellij.openapi.util.RecursionGuard;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.util.*;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * a ConcurrentMap which computes the value associated with the key (via {@link #create(Object)} method) on first {@link #get(Object)} access.
 * THREAD SAFE.
 * It's guaranteed that two {@link #get(Object)} method calls with the same key will return the same value (i.e. the created value stored atomically).
 * For not thread-safe (but possible faster and more memory-efficient) alternative please use {@link FactoryMap}
 */
public abstract class ConcurrentFactoryMap<K,V> implements ConcurrentMap<K,V> {
  private final ConcurrentMap<K, V> myMap = createMap();

  /**
   * @deprecated Use {@link #createMap(Function)} instead
   * TODO to remove in IDEA 2018
   */
  @Deprecated
  @ApiStatus.ScheduledForRemoval(inVersion = "2018")
  public ConcurrentFactoryMap() {
    DeprecatedMethodException.report("Use ConcurrentFactoryMap.create() instead");
  }

  private ConcurrentFactoryMap(@SuppressWarnings("unused") boolean internalConstructor) {

  }

  @Nullable
  protected abstract V create(K key);

  @Override
  public V get(Object key) {
    ConcurrentMap<K, V> map = myMap;
    K k = notNull(key);
    V value = map.get(k);
    if (value == null) {
      RecursionGuard.StackStamp stamp = RecursionManager.markStack();
      //noinspection unchecked
      value = create((K)key);
      if (stamp.mayCacheNow()) {
        V v = notNull(value);
        value = ConcurrencyUtil.cacheOrGet(map, k, v);
      }
    }
    return nullize(value);
  }

  @Nullable
  private static <T> T nullize(T value) {
    return value == FAKE_NULL() ? null : value;
  }

  private static <T> T FAKE_NULL() {
    //noinspection unchecked
    return (T)ObjectUtils.NULL;
  }

  private static <T> T notNull(final Object key) {
    //noinspection unchecked
    return key == null ? FAKE_NULL() : (T)key;
  }

  @Override
  public final boolean containsKey(Object key) {
    return myMap.containsKey(notNull(key));
  }

  @Override
  public V put(K key, V value) {
    K k = notNull(key);
    V v = notNull(value);
    v = myMap.put(k, v);
    return nullize(v);
  }

  @Override
  public V remove(Object key) {
    V v = myMap.remove(notNull(key));
    return nullize(v);
  }

  @NotNull
  @Override
  public Set<K> keySet() {
    return new CollectionWrapper.Set<>(myMap.keySet());
  }

  public boolean removeValue(Object value) {
    Object t = notNull(value);
    //noinspection SuspiciousMethodCalls
    return myMap.values().remove(t);
  }

  @Override
  public void clear() {
    myMap.clear();
  }

  @Override
  public int size() {
    return myMap.size();
  }

  @Override
  public boolean isEmpty() {
    return myMap.isEmpty();
  }

  @Override
  public boolean containsValue(final Object value) {
    return myMap.containsValue(notNull(value));
  }

  @Override
  public void putAll(@NotNull final Map<? extends K, ? extends V> m) {
    for (Entry<? extends K, ? extends V> entry : m.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @NotNull
  @Override
  public Collection<V> values() {
    return new CollectionWrapper<>(myMap.values());
  }

  @NotNull
  @Override
  public Set<Entry<K, V>> entrySet() {
    return new CollectionWrapper.Set<Entry<K, V>>(myMap.entrySet()) {
      @Override
      public Object wrap(Object val) {
        //noinspection unchecked
        return val instanceof EntryWrapper ? ((EntryWrapper<K, V>)val).myEntry : val;
      }

      @Override
      public Entry<K, V> unwrap(Entry<K, V> val) {
        return val.getKey() == FAKE_NULL() || val.getValue() == FAKE_NULL() ? new EntryWrapper<>(val) : val;
      }
    };
  }

  @NotNull
  protected ConcurrentMap<K, V> createMap() {
    return ContainerUtil.newConcurrentMap();
  }

  @Override
  public V putIfAbsent(@NotNull K key, V value) {
    return nullize(myMap.putIfAbsent(notNull(key), notNull(value)));
  }

  @Override
  public boolean remove(@NotNull Object key, Object value) {
    return myMap.remove(ConcurrentFactoryMap.<K>notNull(key), ConcurrentFactoryMap.<V>notNull(value));
  }

  @Override
  public boolean replace(@NotNull K key, @NotNull V oldValue, @NotNull V newValue) {
    return myMap.replace(notNull(key), notNull(oldValue), notNull(newValue));
  }

  @Override
  public V replace(@NotNull K key, @NotNull V value) {
    return nullize(myMap.replace(notNull(key), notNull(value)));
  }

  @NotNull
  public static <T, V> ConcurrentMap<T, V> createMap(@NotNull final Function<? super T, ? extends V> computeValue) {
    return new ConcurrentFactoryMap<T, V>(true) {
      @Nullable
      @Override
      protected V create(T key) {
        return computeValue.fun(key);
      }
    };
  }

  /**
   * @deprecated use {@link #create(Function, Supplier)} instead
   */
  @Deprecated
  @NotNull
  public static <K, V> ConcurrentMap<K, V> createMap(@NotNull Function<? super K, ? extends V> computeValue,
                                                     @NotNull Producer<? extends ConcurrentMap<K, V>> mapCreator) {
    DeprecatedMethodException.report("Use ConcurrentFactoryMap.create() instead");
    return new ConcurrentFactoryMap<K, V>(true) {
      @Nullable
      @Override
      protected V create(K key) {
        return computeValue.fun(key);
      }

      @NotNull
      @Override
      protected ConcurrentMap<K, V> createMap() {
        return mapCreator.produce();
      }
    };
  }

  @NotNull
  public static <K, V> ConcurrentMap<K, V> create(@NotNull Function<? super K, ? extends V> computeValue,
                                                  @NotNull Supplier<? extends ConcurrentMap<K, V>> mapCreator) {
    return new ConcurrentFactoryMap<K, V>(true) {
      @Nullable
      @Override
      protected V create(K key) {
        return computeValue.fun(key);
      }

      @NotNull
      @Override
      protected ConcurrentMap<K, V> createMap() {
        return mapCreator.get();
      }
    };
  }

  /**
   * @return Concurrent factory map with weak keys, strong values
   */
  @NotNull
  public static <T, V> ConcurrentMap<T, V> createWeakMap(@NotNull Function<? super T, ? extends V> compute) {
    return create(compute, ContainerUtil::createConcurrentWeakMap);
  }

  /**
   * @deprecated needed for compatibility in case of moronic subclassing
   * TODO to remove in IDEA 2018
   */
  @ApiStatus.ScheduledForRemoval(inVersion = "2018")
  @Deprecated
  public V getOrDefault(Object key, V defaultValue) {
      V v;
      return (v = get(key)) != null ? v : defaultValue;
  }

  private static class CollectionWrapper<K> extends AbstractCollection<K> {
    private final Collection<K> myDelegate;

    CollectionWrapper(Collection<K> delegate) {
      myDelegate = delegate;
    }

    @NotNull
    @Override
    public Iterator<K> iterator() {
      return new Iterator<K>() {
        final Iterator<K> it = myDelegate.iterator();
        @Override
        public boolean hasNext() {
          return it.hasNext();
        }
        @Override
        public K next() {
          return unwrap(it.next());
        }
        @Override
        public void remove() {
          it.remove();
        }
      };
    }

    @Override
    public int size() {
      return myDelegate.size();
    }

    @Override
    public boolean contains(Object o) {
      return myDelegate.contains(wrap(o));
    }

    @Override
    public boolean remove(Object o) {
      return myDelegate.remove(wrap(o));
    }

    protected Object wrap(Object val) {
      return notNull(val);
    }
    protected K unwrap(K val) {
      return nullize(val);
    }

    private static class Set<K> extends CollectionWrapper<K> implements java.util.Set<K> {
      Set(Collection<K> delegate) {
        super(delegate);
      }
    }

    protected static class EntryWrapper<K, V> implements Entry<K, V> {
      final Entry<? extends K, ? extends V> myEntry;
      private EntryWrapper(Entry<? extends K, ? extends V> entry) {
        myEntry = entry;
      }

      @Override
      public K getKey() {
        return nullize(myEntry.getKey());
      }

      @Override
      public V getValue() {
        return nullize(myEntry.getValue());
      }

      @Override
      public V setValue(V value) {
        return myEntry.setValue(notNull(value));
      }

      @Override
      public int hashCode() {
        return myEntry.hashCode();
      }

      @Override
      public boolean equals(Object obj) {
        //noinspection unchecked
        return myEntry.equals(obj instanceof EntryWrapper ? ((EntryWrapper<K,V>)obj).myEntry : obj);
      }
    }
  }
}
