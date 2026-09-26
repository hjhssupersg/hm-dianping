package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String Name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";

    public SimpleRedisLock(String Name, StringRedisTemplate stringRedisTemplate) {
        this.Name = Name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间，过期自动释放
     * @return true获取成功，false获取失败
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程id作为标识
        long threadId = Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + Name, threadId + "",
                timeoutSec, TimeUnit.SECONDS);
        //等同于“return Boolean.True.equals(success)”，避免因Boolean包装类自动拆箱导致空指针暴露
        return BooleanUtil.isTrue(success);
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        //释放锁
        stringRedisTemplate.delete(KEY_PREFIX + Name);
    }
}
