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

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

import java.util.Map;

/**
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

  private final Configuration configuration;
  // 持有待解析的SqlNode树的根结点
  private final SqlNode rootSqlNode;

  public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
    this.configuration = configuration;
    this.rootSqlNode = rootSqlNode;
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    DynamicContext context = new DynamicContext(configuration, parameterObject);
    // 从根节点开始，层层往下，将会依次对整个树形结构中的每个SqlNode的apply方法进行调用，
    // 在每个节点的SqlNode的apply方法被调用时，解析出的SQL语句片段将会被添加到DynamicContext中，
    // 这也是将DynamicContext作为apply方法的入参的精妙之处，这就是【组合模式】的巧妙应用！
    rootSqlNode.apply(context);
    // 创建SqlSourceBuilder对SqlNode树做进一步解析处理，
    // 一方面是对SQL语句中的#{}占位符中定义的属性进行解析，另一方面是将SQL语句中的#{}替换成“?”占位符。
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
    SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
    // 创建BoundSql对象，并将DynamicContext.bindings中的参数信息复制到其additionalParameters中保存
    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
    for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
      boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
    }
    return boundSql;
  }

}
