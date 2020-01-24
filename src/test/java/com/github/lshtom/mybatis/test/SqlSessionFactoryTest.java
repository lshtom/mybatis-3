package com.github.lshtom.mybatis.test;

import com.github.lshtom.mybatis.model.User;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * SqlSessionFactory单元测试类
 */
public class SqlSessionFactoryTest {

    @Test
    public void testSqlSessionFactory() throws Exception {
        String resource = "com/github/lshtom/mybatis/mybatis-config.xml";
        InputStream inputStream = Resources.getResourceAsStream(resource);
        // 加载Mybatis配置文件
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        // 创建SqlSession对象
        SqlSession sqlSession = sqlSessionFactory.openSession();
        Map<String, Integer> param = new HashMap<>();
        param.put("id", 4);
        // 执行查询语句
        User user = sqlSession.selectOne("com.github.lshtom.mybatis.mapper.UserMapper.getUser", param);
        System.out.println(user);
        sqlSession.close();
    }
}
