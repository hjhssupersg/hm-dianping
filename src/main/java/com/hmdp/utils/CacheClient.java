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
 * Redis 通用缓存工具类，封装缓存读写与三种经典缓存问题的查询模式：
 * 缓存穿透（{@link #queryWithPassThrough}）、缓存击穿
 * （{@link #queryWithMutex} 互斥锁方案、{@link #queryWithLogicalExpire} 逻辑过期方案）。
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 构造方法，注入 Redis 操作模板。
     *
     * @param stringRedisTemplate Redis 字符串操作模板
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将对象序列化为 JSON 后写入缓存，并设置物理过期时间（TTL）。
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
     * 将对象包装为 {@link RedisData} 后写入缓存，并设置逻辑过期时间。
     * <p>缓存本身不设置物理过期时间，由查询方通过 {@link RedisData#getExpireTime()}
     * 判断数据是否过期，适用于配合 {@link #queryWithLogicalExpire} 的热点数据预热。</p>
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
     * 基于"缓存空值"策略的查询方法，用于解决缓存穿透问题。
     * <p>命中有效缓存直接返回；命中空值（数据库中不存在的数据的空标记）时
     * 直接返回 {@code null}，不再回源数据库；缓存未命中时回源数据库查询，
     * 查询结果为空则写入短 TTL 的空值标记，否则写入缓存并返回。</p>
     *
     * @param keyPrefix  缓存键前缀，实际键为 {@code keyPrefix + id}
     * @param id         业务数据主键
     * @param type       返回数据的类型，用于 JSON 反序列化
     * @param dbFallback 缓存未命中时的数据库回退查询函数
     * @param time       缓存过期时间数值
     * @param unit       缓存过期时间单位
     * @param <R>        返回数据类型
     * @param <ID>       主键类型
     * @return 查询到的对象；数据不存在时返回 {@code null}
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
     * 基于"互斥锁"策略的查询方法，用于解决缓存击穿问题，同时兼顾缓存穿透防护。
     * <p>缓存未命中时通过 Redis 分布式锁（SET NX EX）保证同一时间只有一个线程
     * 回源数据库并重建缓存，其余线程休眠后重试，直到缓存重建完成。</p>
     *
     * @param keyPrefix  缓存键前缀，实际键为 {@code keyPrefix + id}
     * @param id         业务数据主键
     * @param type       返回数据的类型，用于 JSON 反序列化
     * @param dbFallback 缓存未命中时的数据库回退查询函数
     * @param time       缓存过期时间数值
     * @param unit       缓存过期时间单位
     * @param <R>        返回数据类型
     * @param <ID>       主键类型
     * @return 查询到的对象；数据不存在时返回 {@code null}
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
     * 基于"逻辑过期"策略的查询方法，用于解决缓存击穿问题。
     * <p>缓存数据通过 {@link RedisData#getExpireTime()} 判断是否逻辑过期。
     * 已过期时仅有一个线程获取互斥锁并在独立线程池中异步重建缓存，
     * 所有请求仍立即返回旧数据，以短暂的数据一致性换取高可用与高性能。</p>
     * <p>注意：该方法不会主动回源数据库，缓存未命中时直接返回 {@code null}，
     * 使用前需通过 {@link #setWithLogicalExpire} 提前预热缓存。</p>
     *
     * @param keyPrefix  缓存键前缀，实际键为 {@code keyPrefix + id}
     * @param id         业务数据主键
     * @param type       返回数据的类型，用于 JSON 反序列化
     * @param dbFallback 逻辑过期后异步重建缓存所使用的数据库查询函数
     * @param time       逻辑过期时间数值
     * @param unit       逻辑过期时间单位
     * @param <R>        返回数据类型
     * @param <ID>       主键类型
     * @return 缓存中的对象（可能是已过期的旧数据）；缓存未命中时返回 {@code null}
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
     * 尝试获取 Redis 分布式锁。
     * <p>基于 {@code SET key value NX EX 10} 原子命令实现：key 不存在时设置成功
     * 并返回 {@code true}；锁持有 10 秒后自动过期，防止持锁线程异常时产生死锁。</p>
     *
     * @param key 锁的键
     * @return 获取成功返回 {@code true}，锁已被占用返回 {@code false}
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放 Redis 分布式锁（直接删除锁对应的 key）。
     *
     * @param key 锁的键
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
