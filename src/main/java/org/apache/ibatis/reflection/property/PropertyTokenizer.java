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
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * @author Clinton Begin
 */
public class PropertyTokenizer implements Iterator<PropertyTokenizer> {
  private String name;
  private final String indexedName;
  private String index;
  private final String children;

  public PropertyTokenizer(String fullname) {
    // 说明：用于解析属性表达式，比如对于 orders[0].items[0].name 这样的表达式的解析，
    // 阅读MyBatis的源码，有个感受：
    // 比如对于像解析属性表达式这种，我们自己的做法可能就是写个工具类，对传入的表达式（String类型）进行解析，
    // 如果一会要解析获取个xxx信息，那得写一个静态方法，然后又要求解析出另一项信息，那又得写个相应的静态方法，
    // 所有的一切都是分离的，不是一个整体的，这本质上就是面向过程的思想。
    // 而同样是属性表达式解析，MyBatis中的做法是将其封装成一个对象，而不是写一个工具类提供若干静态方法，
    // MyBatis会在构造器中就完成解析，并将解析好的各项内容给持有；
    // 这样做还有个好处是，对于复杂的属性表达式，不用去写一个复杂的工具方法去从头开始解析，
    // 而是每次构建一个PropertyTokenizer时就解析了一个部分，这样子也可以利用一些设计模式（比如组合模式、迭代器模式）。

    int delim = fullname.indexOf('.');
    if (delim > -1) {
      name = fullname.substring(0, delim);
      children = fullname.substring(delim + 1);
    } else {
      name = fullname;
      children = null;
    }
    indexedName = name;
    delim = name.indexOf('[');
    if (delim > -1) {
      index = name.substring(delim + 1, name.length() - 1);
      name = name.substring(0, delim);
    }
    // 对于诸如 orders[0].items[0].name 这样的表达式，
    // 经过上面的逻辑处理后，各属性的值为：
    // indexName：orders[0]
    // name：orders
    // index：0
    // children：items[0].name
  }

  public String getName() {
    return name;
  }

  public String getIndex() {
    return index;
  }

  public String getIndexedName() {
    return indexedName;
  }

  public String getChildren() {
    return children;
  }

  @Override
  public boolean hasNext() {
    return children != null;
  }

  @Override
  public PropertyTokenizer next() {
    // 说明：结合迭代器模式，将本来需要写递归来进行表达式解析的逻辑变简单了
    return new PropertyTokenizer(children);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("Remove is not supported, as it has no meaning in the context of properties.");
  }
}
