package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 关注/取关其他用户
     * @param followUserId 被关注的用户id
     * @param isFollow 是否关注（true关注，false取关）
     * @return 无
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //redis中关注关系集合的key
        String key = RedisConstants.FOLLOW_KEY + userId;
        //判断到底是关注还是取关
        if (isFollow) {
            //关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                //把被关注用户的id放入redis的set集合，便于后续求共同关注
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //取关，删除数据 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                //把被关注用户的id从redis的set集合移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 判断当前用户是否关注了某个用户
     * @param followUserId 被关注的用户id
     * @return 是否已关注
     */
    @Override
    public Result isFollow(Long followUserId) {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //查询关注关系 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        int count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //判断
        return Result.ok(count > 0);
    }

    /**
     * 查询当前用户与目标用户的共同关注列表
     * @param id 目标用户id
     * @return 共同关注的用户列表
     */
    @Override
    public Result followCommons(Long id) {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //求两个关注集合的交集
        String key = RedisConstants.FOLLOW_KEY + userId;
        String key2 = RedisConstants.FOLLOW_KEY + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            //无共同关注
            return Result.ok(Collections.emptyList());
        }
        //解析出共同关注的用户id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //根据id查询用户并转为UserDTO返回
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
