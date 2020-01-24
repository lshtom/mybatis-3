package com.github.lshtom.mybatis.mapper;

import com.github.lshtom.mybatis.model.User;

/**
 * Mapper接口
 */
public interface UserMapper {
	void insertUser(User user);
	User getUser(Integer id);
}
