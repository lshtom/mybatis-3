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
package org.apache.ibatis.binding;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
// 说明：MapperMethod中封装了Mapper接口中相应方法的信息（比如xxx方法）以及与之对应的SQL语句的信息，
// 也就是Mapper接口中的方法与映射配置文件中的SQL语句的桥梁。
public class MapperMethod {

  // 记录SQL语句的名称和类型
  private final SqlCommand command;
  // 记录Mapper接口中的xxx方法签名
  private final MethodSignature method;

  public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
    this.command = new SqlCommand(config, mapperInterface, method);
    this.method = new MethodSignature(config, mapperInterface, method);
  }

  public Object execute(SqlSession sqlSession, Object[] args) {
    // 说明：该方法的作用是根据SQL语句的类型调用SqlSession中对应的方法

    Object result;
    switch (command.getType()) {
      // 对于DML语句（增删改），由于执行完SQL后JDBC中返回的是影响的行数，
      // 而我们的Mapper接口中的相应方法的返回值类型未必都是int类型，也可能是其他类型或为void类型，
      // 所以下面的增删改操作返回后都要经过rowCountResult方法进行处理。
      case INSERT: {
      Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.insert(command.getName(), param));
        break;
      }
      case UPDATE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.update(command.getName(), param));
        break;
      }
      case DELETE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.delete(command.getName(), param));
        break;
      }
      case SELECT:
        // 对于Select类型的方法还分情况：
        // 处理返回值类型为void且返回的ResultSet是通过ResultHandler来处理的方法（此处的方法应该指的就是当前在调用的Mapper接口的方法）,
        // 这里考虑的是：该Mapper接口方法的返回值为void（也就是不是直接将SQL查询的结果集进行返回），
        // 而是通过其中一个ResultHandler类型的参数来进行结果集的处理。
        if (method.returnsVoid() && method.hasResultHandler()) {
          executeWithResultHandler(sqlSession, args);
          result = null;
        } else if (method.returnsMany()) {
          // 处理返回值为集合或数组的方法
          result = executeForMany(sqlSession, args);
        } else if (method.returnsMap()) {
          // 处理返回值类型为Map的方法
          result = executeForMap(sqlSession, args);
        } else if (method.returnsCursor()) {
          // 处理返回值类型为Cursor的方法
          result = executeForCursor(sqlSession, args);
        } else {
          Object param = method.convertArgsToSqlCommandParam(args);
          result = sqlSession.selectOne(command.getName(), param);
        }
        break;
      case FLUSH:
        result = sqlSession.flushStatements();
        break;
      default:
        throw new BindingException("Unknown execution method for: " + command.getName());
    }
    if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
      throw new BindingException("Mapper method '" + command.getName() 
          + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
    }
    return result;
  }

  private Object rowCountResult(int rowCount) {
    final Object result;
    // 根据当前的Mapper接口方法的返回值类型进行相应的处理
    if (method.returnsVoid()) {
      result = null;
    } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
      result = rowCount;
    } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
      result = (long)rowCount;
    } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
      result = rowCount > 0;
    } else {
      throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
    }
    return result;
  }

  private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
    // 获取SQL与语句对应的MappedStatement对象，该对象中封装了相应的SQL语句信息
    // 下面这command.getName()中获取的其实是XML映射配置文件中定义的SQL语句的id
    MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
    // 此处是校验，因为如果希望使用ResultHandler来处理结果集时，XML配置映射文件中必须使用ResultMap或ResultType
    if (!StatementType.CALLABLE.equals(ms.getStatementType())
        && void.class.equals(ms.getResultMaps().get(0).getType())) {
      throw new BindingException("method " + command.getName()
          + " needs either a @ResultMap annotation, a @ResultType annotation,"
          + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
    }
    // 参数列表转换
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      // extractRowBounds方法和extractResultHandler方法其实是根据之前解析好的下标来从实参列表中获取相应的RowBounds或ResultHandler类型参数值，
      // 要说明的是：RowBounds类型的参数和ResultHandler类型的参数其实不会同时出现的，之前在创建相应的MethodSignature对象时就进行解析和判断了。
      RowBounds rowBounds = method.extractRowBounds(args);
      sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
    } else {
      sqlSession.select(command.getName(), param, method.extractResultHandler(args));
    }
  }

  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
    List<E> result;
    // 实参转换
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.<E>selectList(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.<E>selectList(command.getName(), param);
    }
    // issue #510 Collections & arrays support
    // 将结果集转换为数组或Collection集合
    if (!method.getReturnType().isAssignableFrom(result.getClass())) {
      if (method.getReturnType().isArray()) {
        return convertToArray(result);
      } else {
        return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
      }
    }
    return result;
  }

  private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
    Cursor<T> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.<T>selectCursor(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.<T>selectCursor(command.getName(), param);
    }
    return result;
  }

  private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
    // 利用MyBatis所提供的反射类进行集合对象的创建
    Object collection = config.getObjectFactory().create(method.getReturnType());
    MetaObject metaObject = config.newMetaObject(collection);
    // 将数据添加到集合中
    metaObject.addAll(list);
    return collection;
  }

  @SuppressWarnings("unchecked")
  private <E> Object convertToArray(List<E> list) {
    // 获取数组元素的类型
    Class<?> arrayComponentType = method.getReturnType().getComponentType();
    // 创建数组对象c
    Object array = Array.newInstance(arrayComponentType, list.size());
    // 如果元素对象其实为基本类型（准确说是基本类型的包装类），那么就一项项的设置到数组中
    if (arrayComponentType.isPrimitive()) {
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, list.get(i));
      }
      return array;
    } else {
      // 否则，对于使用toArray转换成相应的特定类型的对象的数组
      return list.toArray((E[])array);
    }
  }

  private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
    Map<K, V> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey(), rowBounds);
    } else {
      result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey());
    }
    return result;
  }

  public static class ParamMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -2212268410512043556L;

    @Override
    public V get(Object key) {
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
      }
      return super.get(key);
    }

  }

  public static class SqlCommand {

    // 记录SQL语句的名称（XML映射文件中相应SQL语句的id，通过这id就可以从configuration中获取相应的SQL信息MappedStatement）
    private final String name;
    // 记录SQL语句的类型
    private final SqlCommandType type;

    public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
      // 获取方法名
      final String methodName = method.getName();
      // 获取方法所属的类名，注意这个并一定等同于mapperInterface，
      // 因为当前调用的method方法可能是在Mapper接口的父接口中定义的
      final Class<?> declaringClass = method.getDeclaringClass();
      MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass,
          configuration);
      if (ms == null) {
        // 处理@Flush注解
        if (method.getAnnotation(Flush.class) != null) {
          name = null;
          type = SqlCommandType.FLUSH;
        } else {
          throw new BindingException("Invalid bound statement (not found): "
              + mapperInterface.getName() + "." + methodName);
        }
      } else {
        name = ms.getId();
        type = ms.getSqlCommandType();
        if (type == SqlCommandType.UNKNOWN) {
          throw new BindingException("Unknown execution method for: " + name);
        }
      }
    }

    public String getName() {
      return name;
    }

    public SqlCommandType getType() {
      return type;
    }

    private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
        Class<?> declaringClass, Configuration configuration) {
      // SQL语句的名称（id）其实为Mapper接口的名称+方法名称，可以理解为Mapper接口方法的全限定名
      String statementId = mapperInterface.getName() + "." + methodName;
      // 步骤1：判断是否有相应的SQL信息，若有则获取
      // configuration对象中持有MyBatis所读取的各种属性配置信息（也包括了XML映射文件），
      // 所以这一步其实就是确认是否有相应的SQL语句
      if (configuration.hasStatement(statementId)) {
        // 获取相应的MappedStatement对象，该对象中封装了相应的SQL语句相关信息
        return configuration.getMappedStatement(statementId);
      }
      // 步骤2：如果当前所调用的method方法所在的接口就是Mapper接口，那就没啥好进一步尝试的了，
      // 因为前面所获取的statementId就是当前Mapper接口名+方法名，结果从configuration中获取不到相应的SQL信息，
      // 那就说明configuration中确实没有相对应的SQL信息，所以只能返回空了。
      else if (mapperInterface.equals(declaringClass)) {
        return null;
      }
      // 步骤3：处理父接口
      // 说明：由于当前所对应的method方法可能是在父接口中的，所以利用前面得到的statementId肯定是无法从configuration中获取到相应的SQL信息的,
      // 所以要针对当前调用的方法是在父接口中情形进行递归处理
      for (Class<?> superInterface : mapperInterface.getInterfaces()) {
        if (declaringClass.isAssignableFrom(superInterface)) {
          // 递归处理父接口
          MappedStatement ms = resolveMappedStatement(superInterface, methodName,
              declaringClass, configuration);
          if (ms != null) {
            return ms;
          }
        }
      }
      return null;
    }
  }

  public static class MethodSignature {

    // 返回值类型是否为Collection类型或数组类型
    private final boolean returnsMany;
    // 返回值类型是否为Map类型
    private final boolean returnsMap;
    // 返回值类型是否为void类型
    private final boolean returnsVoid;
    // 返回值类型是否为Cursor类型
    private final boolean returnsCursor;
    // 返回值类型
    private final Class<?> returnType;
    // 如果返回值类型为Map，则该字段记录了作为Key的列名
    private final String mapKey;
    // 用于标记参数列表中ResultHandler类型参数的位置
    private final Integer resultHandlerIndex;
    // 用于标记参数列表中RowBounds类型参数的位置
    private final Integer rowBoundsIndex;
    // ParamNameResolver用于处理Mapper接口中定义的方法的参数列表
    private final ParamNameResolver paramNameResolver;

    public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
      // 解析返回值类型
      Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
      if (resolvedReturnType instanceof Class<?>) {
        this.returnType = (Class<?>) resolvedReturnType;
      } else if (resolvedReturnType instanceof ParameterizedType) {
        this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
      } else {
        this.returnType = method.getReturnType();
      }
      // 初始化其他字段
      this.returnsVoid = void.class.equals(this.returnType);
      this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
      this.returnsCursor = Cursor.class.equals(this.returnType);
      this.mapKey = getMapKey(method);
      this.returnsMap = this.mapKey != null;
      this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
      this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);
      this.paramNameResolver = new ParamNameResolver(configuration, method);
    }

    public Object convertArgsToSqlCommandParam(Object[] args) {
      // 用于传入实际参数，转换为SQL语句对应的参数列表
      // Tips：之所以可以起作用，是因为ParamNameResolver的names属性中存储的是：参数的位置索引序->参数的名称，
      // 而此处的入参args为数组，也就相当于是：位置下标->参数实参（参数值），
      // 而经过这转换后就变成：参数名称->参数实参（参数值）相当于属性名->属性值。s
      return paramNameResolver.getNamedParams(args);
    }

    public boolean hasRowBounds() {
      return rowBoundsIndex != null;
    }

    public RowBounds extractRowBounds(Object[] args) {
      return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
    }

    public boolean hasResultHandler() {
      return resultHandlerIndex != null;
    }

    public ResultHandler extractResultHandler(Object[] args) {
      return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
    }

    public String getMapKey() {
      return mapKey;
    }

    public Class<?> getReturnType() {
      return returnType;
    }

    public boolean returnsMany() {
      return returnsMany;
    }

    public boolean returnsMap() {
      return returnsMap;
    }

    public boolean returnsVoid() {
      return returnsVoid;
    }

    public boolean returnsCursor() {
      return returnsCursor;
    }

    /**
     * 用于查找指定类型的参数在参数列表中的位置，
     * 说明：这个方法是用于查找RowBounds类型参数或ResultHandler类型参数在参数列表中的位置的，
     * 而不不会用于一般的参数类型的查找
     */
    private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
      Integer index = null;
      final Class<?>[] argTypes = method.getParameterTypes();
      for (int i = 0; i < argTypes.length; i++) {
        if (paramType.isAssignableFrom(argTypes[i])) {
          if (index == null) {
            index = i;
          } else {
            // RowBounds类型参数或ResultHandler类型参数只能在参数列表中出现一个
            throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
          }
        }
      }
      return index;
    }

    private String getMapKey(Method method) {
      String mapKey = null;
      if (Map.class.isAssignableFrom(method.getReturnType())) {
        final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
        if (mapKeyAnnotation != null) {
          mapKey = mapKeyAnnotation.value();
        }
      }
      return mapKey;
    }
  }

}
