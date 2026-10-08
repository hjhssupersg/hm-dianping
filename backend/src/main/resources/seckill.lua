-- 秒杀下单脚本：判断库存、判断一人一单、扣减库存、发送订单消息，保证原子性
-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey
local stock = tonumber(redis.call('get', stockKey))
if (stock == nil or stock <= 0) then
    -- 3.2.库存不足，返回1
    return 1
end
-- 3.3.判断用户是否已下单 SISMEMBER orderKey userId
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 3.4.已下单，说明是重复下单，返回2
    return 2
end
-- 3.5.扣减库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.6.记录下单用户 sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.7.发送订单消息到消息队列 XADD stream.orders * userId userId voucherId voucherId id orderId
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
-- 3.8.有购买资格，返回0
return 0
