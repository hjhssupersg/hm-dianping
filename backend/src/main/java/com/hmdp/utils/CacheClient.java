package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * Redis 缓存工具类：封装缓存读写，以及穿透（缓存空值）、击穿（互斥锁、逻辑过期）两种查询方案
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 注入 Redis 操作模板
     *
     * @param stringRedisTemplate Redis 字符串操作模板
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 序列化为 JSON 写入缓存，并设置 TTL
     *
     * @param key   缓存键
     * @param value 要缓存的对象
     * @param time  过期时间数值
     * @param unit  过期时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 包装为 {@link RedisData} 写入缓存，只设逻辑过期时间、不设物理 TTL
     * 需先预热缓存，配合 {@link #queryWithLogicalExpire} 使用
     *
     * @param key   缓存键
     * @param value 要缓存的对象
     * @param time  逻辑过期时间数值
     * @param unit  逻辑过期时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存空值方案，解决缓存穿透：数据库不存在的 id 写入短 TTL 空值标记，
     * 后续请求直接返回 null，不再回源数据库
     *
     * @param keyPrefix  缓存键前缀，实际键为 {@code keyPrefix + id}
     * @param id         业务数据主键
     * @param type       返回数据类型，用于 JSON 反序列化
     * @param dbFallback 缓存未命中时的数据库查询函数
     * @param time       缓存过期时间数值
     * @param unit       缓存过期时间单位
     * @param <R>        返回数据类型
     * @param <ID>       主键类型
     * @return 查询到的对象；数据不存在返回 {@code null}
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 互斥锁方案，解决缓存击穿：缓存未命中时用分布式锁保证只有一个线程
     * 回源数据库重建缓存，其余线程休眠后重试
     *
     * @param keyPrefix  缓存键前缀，实际键为 {@code keyPrefix + id}
     * @param id         业务数据主键
     * @param type       返回数据类型，用于 JSON 反序列化
     * @param dbFallback 缓存未命中时的数据库查询函数
     * @param time       缓存过期时间数值
     * @param unit       缓存过期时间单位
     * @param <R>        返回数据类型
     * @param <ID>       主键类型
     * @return 查询到的对象；数据不存在返回 {@code null}
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, type);
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.4.获取锁成功，根据id查询数据库
            r = dbFallback.apply(id);
            // 5.不存在，返回错误
            if (r == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放锁
            unlock(lockKey);
        }
        // 8.返回
        return r;
    }

    /**
     * 逻辑过期方案，解决缓存击穿：发现数据逻辑过期后，抢到锁的线程异步重建缓存，
     * 请求立即返回旧数据，不回源数据库，缓存未命中直接返回 null，需先用
     * {@link #setWithLogicalExpire} 预热缓存
     *
     * @param keyPrefix  缓存键前缀，实际键为 {@code keyPrefix + id}
     * @param id         业务数据主键
     * @param type       返回数据类型，用于 JSON 反序列化
     * @param dbFallback 逻辑过期后异步重建缓存的数据库查询函数
     * @param time       逻辑过期时间数值
     * @param unit       逻辑过期时间单位
     * @param <R>        返回数据类型
     * @param <ID>       主键类型
     * @return 缓存中的对象（可能已过期）；缓存未命中返回 {@code null}
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在，直接返回错误信息
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return r;
    }


    /**
     * 尝试获取分布式锁：{@code SET key value NX EX 10}，10 秒自动过期防死锁
     *
     * @param key 锁的键
     * @return 获取成功 {@code true}，锁被占用 {@code false}
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放 Redis 分布式锁（直接删除锁对应的 key）
     *
     * @param key 锁的键
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
