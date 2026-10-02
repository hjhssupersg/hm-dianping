-- 比较当前线程标识与锁中的线程标识是否一致
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 一致，则释放锁
    return redis.call('del', KEYS[1])
end
return 0