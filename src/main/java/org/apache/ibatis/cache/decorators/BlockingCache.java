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
import org.apache.ibatis.cache.CacheException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Simple blocking decorator 
 * 
 * Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 * 
 * @author Eduardo Macarron
 *
 */
// 在装饰器模式中扮演了ConcreteDecorator角色
public class BlockingCache implements Cache {

  // 阻塞超时时长
  private long timeout;
  // 持有被装饰的对象
  private final Cache delegate;
  // 每个Key都有对应的ReentrantLock对象
  private final ConcurrentHashMap<Object, ReentrantLock> locks;

  public BlockingCache(Cache delegate) {
    this.delegate = delegate;
    this.locks = new ConcurrentHashMap<Object, ReentrantLock>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  @Override
  public void putObject(Object key, Object value) {
    // 说明：这个方法中看上去怪怪的，都没有加锁的逻辑却有一个解锁的逻辑在，
    // 其实根源是：加锁是在getObject方法中，这getObject方法与putObject方法其实是有逻辑联系的，
    // 不是说独立的两个方法。
    // 正确的使用流程是这样的：getObject和putObject方法配合起来实现数据搞到缓存中，
    // 而且是加了锁的，确保不会多线程情况下，并发的读数据库并都加载到缓存中，
    // 因为同一个Key对应的缓存只有一份就好了，也就是说在多线程的环境下只要有一个线程能从数据库中读取到数据并写到缓存中，
    // 其他线程就不用再去访问数据库了，而是直接去读该缓存返回即可。
    // 所以，正常的调用顺序其实是：首先调用getObject方法，尝试获取该Key所对应的缓存项，
    // 首次访问时，delegate.getObject(key)方法返回的是null值，故此时的锁还是被当前线程所占有的，
    // 接着主调逻辑中会判断如果当前返回的是null，则会进行实际的数据库查询，并调用该putObject方法将数据写入到缓存中，
    // 之后锁释放（finally块的逻辑）；
    // 当后续其他线程再次访问时还是会加锁的，但从缓存中获取到非空值，故就直接解锁了，外面的主调逻辑也就不会再去调用putObject方法。

    try {
      // 从缓存中添加缓存项
      delegate.putObject(key, value);
    } finally {
      releaseLock(key);
    }
  }

  @Override
  public Object getObject(Object key) {
    // 先获取该Key对应的锁
    acquireLock(key);
    // 根据该Key进行缓存数据查询
    Object value = delegate.getObject(key);
    // 若找到Key所对应的缓存项，则释放锁，否则继续持有锁
    if (value != null) {
      releaseLock(key);
    }        
    return value;
  }

  @Override
  public Object removeObject(Object key) {
    // despite of its name, this method is called only to release locks
    releaseLock(key);
    return null;
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }
  
  private ReentrantLock getLockForKey(Object key) {
    ReentrantLock lock = new ReentrantLock();
    ReentrantLock previous = locks.putIfAbsent(key, lock);
    return previous == null ? lock : previous;
  }
  
  private void acquireLock(Object key) {
    // 获取Key对应的锁对象（ReentrantLock类型）
    Lock lock = getLockForKey(key);
    if (timeout > 0) {
      try {
        // 给tryLock方法传参时带上了超时时长，如果在该时间内获取到了锁就返回true，否则一直阻塞等待，直到超时返回false
        boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);
        if (!acquired) {
          // 超时抛出异常
          throw new CacheException("Couldn't get a lock in " + timeout + " for the key " +  key + " at the cache " + delegate.getId());  
        }
      } catch (InterruptedException e) {
        throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
      }
    } else {
      // 执行到此处是，表明已经成功获取到锁，那么接下来就进行加锁
      lock.lock();
    }
  }
  
  private void releaseLock(Object key) {
    ReentrantLock lock = locks.get(key);
    if (lock.isHeldByCurrentThread()) {
      lock.unlock();
    }
  }

  public long getTimeout() {
    return timeout;
  }

  public void setTimeout(long timeout) {
    this.timeout = timeout;
  }  
}