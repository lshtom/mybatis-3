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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.session.Configuration;

import java.util.Map;

/**
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {
  public static final String ITEM_PREFIX = "__frch_";

  // 用于判断循环的终止条件
  private final ExpressionEvaluator evaluator;
  // 迭代的集合表达式
  private final String collectionExpression;
  // 记录<forEach>节点的子节点
  private final SqlNode contents;
  // 在循环开始前要添加的字符串
  private final String open;
  // 在循环结束后要添加的字符串
  private final String close;
  // 循环的过程中，每项之间的分隔符
  private final String separator;
  // 本地迭代的元素
  private final String item;
  // 当前迭代的次数，比如对于List类型，那么index就是0，1，2等；
  // 如果为迭代的集合是Map，则index是键，item是值（也就是一个是K，一个是V）。
  private final String index;
  private final Configuration configuration;

  public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index, String item, String open, String close, String separator) {
    this.evaluator = new ExpressionEvaluator();
    this.collectionExpression = collectionExpression;
    this.contents = contents;
    this.open = open;
    this.close = close;
    this.separator = separator;
    this.index = index;
    this.item = item;
    this.configuration = configuration;
  }

  @Override
  public boolean apply(DynamicContext context) {
    Map<String, Object> bindings = context.getBindings();
    final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
    if (!iterable.iterator().hasNext()) {
      return true;
    }
    boolean first = true;
    // 在循环开始前，添加open字段
    applyOpen(context);
    int i = 0;
    // 开始遍历迭代集合
    for (Object o : iterable) {
      DynamicContext oldContext = context;
      // 对于集合中的第一项，不需要添加分隔符
      if (first || separator == null) {
        context = new PrefixedContext(context, "");
      } else {
        context = new PrefixedContext(context, separator);
      }
      // 获取递增的数字序列（可作为下标索引），用于生成新的#{}占位符
      int uniqueNumber = context.getUniqueNumber();
      // Issue #709
      // 区分当前的迭代集合为Map还是非Map类型，
      // 对于Map类型，其index为Key，item为Value；
      // 对于非Map类型，其index为下标，item为Value。
      if (o instanceof Map.Entry) {
        @SuppressWarnings("unchecked") 
        Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
        applyIndex(context, mapEntry.getKey(), uniqueNumber);
        applyItem(context, mapEntry.getValue(), uniqueNumber);
      } else {
        applyIndex(context, i, uniqueNumber);
        applyItem(context, o, uniqueNumber);
      }
      // 处理子节点
      contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
      if (first) {
        first = !((PrefixedContext) context).isPrefixApplied();
      }
      context = oldContext;
      i++;
    }
    // 循环结束后，添加close字段指定的字符串
    applyClose(context);
    context.getBindings().remove(item);
    context.getBindings().remove(index);
    return true;
  }

  private void applyIndex(DynamicContext context, Object o, int i) {
    if (index != null) {
      context.bind(index, o);
      context.bind(itemizeItem(index, i), o);
    }
  }

  private void applyItem(DynamicContext context, Object o, int i) {
    if (item != null) {
      context.bind(item, o);
      context.bind(itemizeItem(item, i), o);
    }
  }

  private void applyOpen(DynamicContext context) {
    if (open != null) {
      context.appendSql(open);
    }
  }

  private void applyClose(DynamicContext context) {
    if (close != null) {
      context.appendSql(close);
    }
  }

  private static String itemizeItem(String item, int i) {
    // 添加前缀和后缀形成新的Key
    return new StringBuilder(ITEM_PREFIX).append(item).append("_").append(i).toString();
  }

  // 此类用于处理<forEach>节点中的#{}占位符
  private static class FilteredDynamicContext extends DynamicContext {
    private final DynamicContext delegate;
    // 对应集合项在集合中的索引位置
    private final int index;
    // 对应ForEachSqlNode的index字段
    private final String itemIndex;
    // 对应ForEachSqlNode的item字段
    private final String item;

    public FilteredDynamicContext(Configuration configuration,DynamicContext delegate, String itemIndex, String item, int i) {
      super(configuration, null);
      this.delegate = delegate;
      this.index = i;
      this.itemIndex = itemIndex;
      this.item = item;
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public void appendSql(String sql) {
      // 注意：这方法中的逻辑并非是解析占位符#{}，而仅仅是处理，
      // 将#{item}转换为#{_frch_item_1}的格式，#{itemIndex}转换为#{_frch_itemIndex_1}的格式，
      // 后续会做进一步的解析。
      // （所以可以理解为：此处仅仅是将#{xxx}的内容转换为含有相应的属性名的占位符，后面在做解析处理）
      GenericTokenParser parser = new GenericTokenParser("#{", "}", new TokenHandler() {
        @Override
        public String handleToken(String content) {
          String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
          if (itemIndex != null && newContent.equals(content)) {
            newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
          }
          return new StringBuilder("#{").append(newContent).append("}").toString();
        }
      });

      delegate.appendSql(parser.parse(sql));
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

  }


  private class PrefixedContext extends DynamicContext {
    private final DynamicContext delegate;
    // 指定的前缀
    private final String prefix;
    // 标识是否已经处理过前缀
    private boolean prefixApplied;

    public PrefixedContext(DynamicContext delegate, String prefix) {
      super(configuration, null);
      this.delegate = delegate;
      this.prefix = prefix;
      this.prefixApplied = false;
    }

    public boolean isPrefixApplied() {
      return prefixApplied;
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public void appendSql(String sql) {
      if (!prefixApplied && sql != null && sql.trim().length() > 0) {
        // 追加前缀
        delegate.appendSql(prefix);
        prefixApplied = true;
      }
      delegate.appendSql(sql);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }
  }

}
