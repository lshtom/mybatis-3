/**
 *    Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.mapping;

import org.apache.ibatis.builder.InitializingObject;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;
import org.apache.ibatis.cache.decorators.*;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @author Clinton Begin
 */
public class CacheBuilder {
  // Cache对象的唯一标识，一般情况下就是映射配置文件的namespace
  private final String id;
  // Cache接口的实现类，此处持有的是最底层的那个被装饰的Cache对象，一般默认就是PerpetualCache
  private Class<? extends Cache> implementation;
  // 装饰器集合
  private final List<Class<? extends Cache>> decorators;
  // Cache大小
  private Integer size;
  // 清理时间周期
  private Long clearInterval;
  // 是否可读写
  private boolean readWrite;
  // 其他配置信息
  private Properties properties;
  // 是否阻塞
  private boolean blocking;

  public CacheBuilder(String id) {
    this.id = id;
    this.decorators = new ArrayList<Class<? extends Cache>>();
  }

  public CacheBuilder implementation(Class<? extends Cache> implementation) {
    this.implementation = implementation;
    return this;
  }

  public CacheBuilder addDecorator(Class<? extends Cache> decorator) {
    if (decorator != null) {
      this.decorators.add(decorator);
    }
    return this;
  }

  public CacheBuilder size(Integer size) {
    this.size = size;
    return this;
  }

  public CacheBuilder clearInterval(Long clearInterval) {
    this.clearInterval = clearInterval;
    return this;
  }

  public CacheBuilder readWrite(boolean readWrite) {
    this.readWrite = readWrite;
    return this;
  }

  public CacheBuilder blocking(boolean blocking) {
    this.blocking = blocking;
    return this;
  }
  
  public CacheBuilder properties(Properties properties) {
    this.properties = properties;
    return this;
  }

  public Cache build() {
    // 如果implementation字段和decorators字段为空，则设置默认值
    setDefaultImplementations();
    // 先创建出一个基础的Cache接口实现类对象（也就是装饰器链中最底层的那个被装饰的对象）
    Cache cache = newBaseCacheInstance(implementation, id);
    // 其实就是将<cache>节点下配置的<property>信息，初始化Cache对象
    setCacheProperties(cache);
    // issue #352, do not apply decorators to custom caches
    // 只有当Cache为PerpetualCache时才会使用装饰器链中的装饰器对其进行装饰，
    // 这主要就是考虑了如果当前的Cache为自定义的话，就不自动装饰了。
    if (PerpetualCache.class.equals(cache.getClass())) {
      for (Class<? extends Cache> decorator : decorators) {
        cache = newCacheDecoratorInstance(decorator, cache);
        setCacheProperties(cache);
      }
      // 添加MyBatis中提供的标准装饰器
      cache = setStandardDecorators(cache);
    } else if (!LoggingCache.class.isAssignableFrom(cache.getClass())) {
      cache = new LoggingCache(cache);
    }
    return cache;
  }

  private void setDefaultImplementations() {
    if (implementation == null) {
      implementation = PerpetualCache.class;
      if (decorators.isEmpty()) {
        decorators.add(LruCache.class);
      }
    }
  }

  private Cache setStandardDecorators(Cache cache) {
    // 说明：主要就是根据各种配置值来决定使用相应的装饰器
    try {
      MetaObject metaCache = SystemMetaObject.forObject(cache);
      if (size != null && metaCache.hasSetter("size")) {
        metaCache.setValue("size", size);
      }
      if (clearInterval != null) {
        cache = new ScheduledCache(cache);
        ((ScheduledCache) cache).setClearInterval(clearInterval);
      }
      if (readWrite) {
        cache = new SerializedCache(cache);
      }
      cache = new LoggingCache(cache);
      cache = new SynchronizedCache(cache);
      if (blocking) {
        cache = new BlockingCache(cache);
      }
      return cache;
    } catch (Exception e) {
      throw new CacheException("Error building standard cache decorators.  Cause: " + e, e);
    }
  }

  private void setCacheProperties(Cache cache) {
    if (properties != null) {
      MetaObject metaCache = SystemMetaObject.forObject(cache);
      // 获取各属性配置，并设置到Cache中
      for (Map.Entry<Object, Object> entry : properties.entrySet()) {
        String name = (String) entry.getKey();
        String value = (String) entry.getValue();
        if (metaCache.hasSetter(name)) {
          Class<?> type = metaCache.getSetterType(name);
          if (String.class == type) {
            metaCache.setValue(name, value);
          } else if (int.class == type
              || Integer.class == type) {
            metaCache.setValue(name, Integer.valueOf(value));
          } else if (long.class == type
              || Long.class == type) {
            metaCache.setValue(name, Long.valueOf(value));
          } else if (short.class == type
              || Short.class == type) {
            metaCache.setValue(name, Short.valueOf(value));
          } else if (byte.class == type
              || Byte.class == type) {
            metaCache.setValue(name, Byte.valueOf(value));
          } else if (float.class == type
              || Float.class == type) {
            metaCache.setValue(name, Float.valueOf(value));
          } else if (boolean.class == type
              || Boolean.class == type) {
            metaCache.setValue(name, Boolean.valueOf(value));
          } else if (double.class == type
              || Double.class == type) {
            metaCache.setValue(name, Double.valueOf(value));
          } else {
            throw new CacheException("Unsupported property type for cache: '" + name + "' of type " + type);
          }
        }
      }
    }
    // 如果还实现了InitializingObject接口，则回调InitializingObject的initialize方法
    if (InitializingObject.class.isAssignableFrom(cache.getClass())){
      try {
        ((InitializingObject) cache).initialize();
      } catch (Exception e) {
        throw new CacheException("Failed cache initialization for '" +
            cache.getId() + "' on '" + cache.getClass().getName() + "'", e);
      }
    }
  }

  private Cache newBaseCacheInstance(Class<? extends Cache> cacheClass, String id) {
    Constructor<? extends Cache> cacheConstructor = getBaseCacheConstructor(cacheClass);
    try {
      return cacheConstructor.newInstance(id);
    } catch (Exception e) {
      throw new CacheException("Could not instantiate cache implementation (" + cacheClass + "). Cause: " + e, e);
    }
  }

  private Constructor<? extends Cache> getBaseCacheConstructor(Class<? extends Cache> cacheClass) {
    try {
      return cacheClass.getConstructor(String.class);
    } catch (Exception e) {
      throw new CacheException("Invalid base cache implementation (" + cacheClass + ").  " +
          "Base cache implementations must have a constructor that takes a String id as a parameter.  Cause: " + e, e);
    }
  }

  private Cache newCacheDecoratorInstance(Class<? extends Cache> cacheClass, Cache base) {
    Constructor<? extends Cache> cacheConstructor = getCacheDecoratorConstructor(cacheClass);
    try {
      return cacheConstructor.newInstance(base);
    } catch (Exception e) {
      throw new CacheException("Could not instantiate cache decorator (" + cacheClass + "). Cause: " + e, e);
    }
  }

  private Constructor<? extends Cache> getCacheDecoratorConstructor(Class<? extends Cache> cacheClass) {
    try {
      return cacheClass.getConstructor(Cache.class);
    } catch (Exception e) {
      throw new CacheException("Invalid cache decorator (" + cacheClass + ").  " +
          "Cache decorators must have a constructor that takes a Cache instance as a parameter.  Cause: " + e, e);
    }
  }
}
