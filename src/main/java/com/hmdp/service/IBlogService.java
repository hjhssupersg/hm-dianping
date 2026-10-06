package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 查询热门博文列表（分页）
     * @param current 页码
     * @return 热门博文列表
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查询博文详情
     * @param id 博文id
     * @return 博文详情
     */
    Result queryBlogById(Long id);

    /**
     * 点赞/取消点赞博文（一人一赞）
     * @param id 博文id
     * @return 无
     */
    Result likeBlog(Long id);

    /**
     * 查询博文点赞排行榜（Top5）
     * @param id 博文id
     * @return 点赞用户列表
     */
    Result queryBlogLikes(Long id);
}
