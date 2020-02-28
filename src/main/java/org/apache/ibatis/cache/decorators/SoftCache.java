/**
 *    Copyright 2009-2015 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache.decorators;

import org.apache.ibatis.cache.Cache;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Soft Reference cache decorator
 * Thanks to Dr. Heinz Kabutz for his guidance here.
 *
 * @author Clinton Begin
 */
public class SoftCache implements Cache {
  /**
   * 感觉这个设计的很好，利用强引用的Deque类型队列hardLinksToAvoidGarbageCollection和软引用结合起来，
   * 实现了内存不足进行GC才会进行缓存淘汰的LRU算法，
   * 而LruCache仅仅是超过了我们设置的大小就会进行缓存淘汰，
   * 这样设计的好处是：如果我们要频繁访问某个缓存项，那么即使内存资源不够了也不应该去考虑回收它，
   * 因为它总是会被访问到的，如果不分青红皂白仅仅是因为它是软应用就回收掉了，那导致又得从数据库中读数据，反而造成性能的极大下降，
   * 所以这其实是对简单的使用软引用进行数据缓存的一种改进（应用上了LRU）。
   */

  // 用于保存最近使用的缓存项（这是强引用），所以只要缓存项被添加到了hardLinksToAvoidGarbageCollection中，
  // 那么即使它也被SoftReference包裹了，那么也不会被GC掉。
  private final Deque<Object> hardLinksToAvoidGarbageCollection;
  // 引用队列，用于记录已经被GC回收的缓存项所对应的SoftEntry对象
  private final ReferenceQueue<Object> queueOfGarbageCollectedEntries;
  // 被装饰的cache对象
  private final Cache delegate;
  private int numberOfHardLinks;

  public SoftCache(Cache delegate) {
    this.delegate = delegate;
    this.numberOfHardLinks = 256;
    this.hardLinksToAvoidGarbageCollection = new LinkedList<Object>();
    this.queueOfGarbageCollectedEntries = new ReferenceQueue<Object>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    removeGarbageCollectedItems();
    return delegate.getSize();
  }


  public void setSize(int size) {
    this.numberOfHardLinks = size;
  }

  @Override
  public void putObject(Object key, Object value) {
    // 清除已经被GC回收的缓存项
    removeGarbageCollectedItems();
    delegate.putObject(key, new SoftEntry(key, value, queueOfGarbageCollectedEntries));
  }

  @Override
  public Object getObject(Object key) {
    Object result = null;
    @SuppressWarnings("unchecked") // assumed delegate cache is totally managed by this cache
    SoftReference<Object> softReference = (SoftReference<Object>) delegate.getObject(key);
    if (softReference != null) {
      result = softReference.get();
      // 这里要做一下判空，因为通过软引用所实现的缓存有可能已经被GC掉了
      if (result == null) {
        // 如果软引用缓存真的给GC掉了，那相应的被委托对象中所缓存的SoftEntry对象也没必要留了，直接删除
        delegate.removeObject(key);
      } else {
        // See #586 (and #335) modifications need more than a read lock 
        synchronized (hardLinksToAvoidGarbageCollection) {
          // 从队头添加
          // 说明：同一个对象是可以被多次添加的，所以如果某个对象被经常访问，
          // 那就会被经常添加到hardLinksToAvoidGarbageCollection这个队列中被多次强引用。
          hardLinksToAvoidGarbageCollection.addFirst(result);
          if (hardLinksToAvoidGarbageCollection.size() > numberOfHardLinks) {
            // 从对尾删除
            hardLinksToAvoidGarbageCollection.removeLast();
          }
        }
      }
    }
    return result;
  }

  @Override
  public Object removeObject(Object key) {
    removeGarbageCollectedItems();
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    synchronized (hardLinksToAvoidGarbageCollection) {
      hardLinksToAvoidGarbageCollection.clear();
    }
    removeGarbageCollectedItems();
    delegate.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  private void removeGarbageCollectedItems() {
    SoftEntry sv;
    // 从引用队列中拿到非空对象（SoftEntry类型），表明已经有缓存值被GC掉了，
    // 所以拿到key去相应的删除下一层（被装饰的Cache对象）中所缓存的缓存项（其实就是SoftEntry对象）。
    while ((sv = (SoftEntry) queueOfGarbageCollectedEntries.poll()) != null) {
      delegate.removeObject(sv.key);
    }
  }

  private static class SoftEntry extends SoftReference<Object> {
    private final Object key;

    // SoftEntry中，指向key的引用是强引用，而指向value的引用是软引用，并且关联了引用队列,
    // 这里之所以是Key作为强引用而不是像value那样被SoftReference所包装作为弱引用，
    // 原因是：value作为softCache的缓存值，其目的就是当Java内存不足时将其gc掉，
    // 而Key作为缓存的键，与SoftEntry对象本身构成K-V关系，存到被装饰的Cache对象中，
    // 这里要特别注意的时：SoftEntry对象本身不是软引用，
    // 比如SoftEntry se = new SoftEntry(xxx); 从引用变量se指向SoftEntry对象的这个引用是强引用，
    // 就保存在被装饰的Cache对象中，
    // 所以删除这SoftEntry对象需要靠Key，如果Key都变成了软引用被回收掉了就没法去删除SoftEntry了，
    // 结果就是造成被装饰的Cache对象中缓存的SoftEntry对象越来越多，这就内存泄漏了。
    // （下一层中所缓存的SoftEntry对象不会被GC掉，因为还是有引用指向它的，只是我们不知道这个引用而已，结果就没法从Map中去删除）
    SoftEntry(Object key, Object value, ReferenceQueue<Object> garbageCollectionQueue) {
      super(value, garbageCollectionQueue);
      this.key = key;
    }
  }

}