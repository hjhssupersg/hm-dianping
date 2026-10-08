package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private ApplicationContext applicationContext;

    //秒杀下单的消息队列名称
    private static final String QUEUE_NAME = "stream.orders";
    //消费者组名称
    private static final String GROUP_NAME = "g1";
    //消费者名称
    private static final String CONSUMER_NAME = "c1";

    //秒杀下单的Lua脚本（原子完成：判断库存、判断一人一单、扣减库存、发送订单消息）
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //单线程线程池，用于异步处理订单（保证订单按顺序落库）
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 初始化：启动订单处理器
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 秒杀下单优惠券（同步判断购买资格，订单由消费线程异步落库）
     * @param voucherId 优惠券id
     * @return 订单id
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //根据id查询秒杀券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("秒杀券不存在！");
        }
        //判断是否未开始秒杀
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还未开始！");
        }
        //判断是否已经结束秒杀
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //生成订单id（利用全局唯一ID生成器）
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本，原子完成：判断库存、判断一人一单、扣减库存、发送订单消息
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        //判断结果是否为0（0：有购买资格，1：库存不足，2：重复下单）
        if (result == null) {
            return Result.fail("秒杀失败，请稍后重试！");
        }
        if (result != 0) {
            //不为0，代表没有购买资格
            return Result.fail(result == 1 ? "库存不足！" : "同一用户不能重复下单！");
        }
        //返回订单id
        return Result.ok(orderId);
    }

    /**
     * 创建优惠券订单（由消费线程通过代理对象调用，保证事务生效）
     * @param voucherOrder 订单信息
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //创建锁对象（基于redis实现的分布式锁，确保分布式/集群模式下的线程安全）
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //若获取锁失败，则说明同一用户正在下单
        if (!isLock) {
            log.error("不允许重复下单！");
            return;
        }
        try {
            //创建订单之前，先查询同一用户是否已经有过订单（确保一人一单）
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //同一用户已经秒杀过该优惠券
            if (count > 0) {
                log.error("用户已经购买过一次！");
                return;
            }
            //扣减库存（"乐观锁"的思想，防止超卖）
            boolean success = seckillVoucherService.update().
                    setSql("stock = stock - 1").//set stock = stock - 1
                    eq("voucher_id", voucherId).
                    gt("stock", 0).//where voucher_id = ? and stock > 0
                    update();
            if (!success) {
                //扣减失败
                log.error("库存不足！");
                return;
            }
            //创建订单
            save(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    /**
     * 订单处理器：从消息队列中取出订单消息，异步创建订单
     */
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            //创建消费组（stream不存在时用MKSTREAM自动创建）
            createGroupWithRetry();
            //先处理pending-list中遗留的异常消息（如上次宕机未确认的消息），保证消息不丢失
            handlePendingList();
            //获取代理对象，通过代理对象调用createVoucherOrder，保证@Transactional生效
            IVoucherOrderService proxy = applicationContext.getBean(IVoucherOrderService.class);
            while (true) {
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                    //2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        //如果没有消息，继续下一次循环
                        continue;
                    }
                    //解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3.创建订单
                    proxy.createVoucherOrder(voucherOrder);
                    //4.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //处理pending-list中的异常消息
                    handlePendingList();
                }
            }
        }

        /**
         * 处理pending-list中未确认的消息，保证消息不丢失
         */
        private void handlePendingList() {
            //获取代理对象，通过代理对象调用createVoucherOrder，保证@Transactional生效
            IVoucherOrderService proxy = applicationContext.getBean(IVoucherOrderService.class);
            while (true) {
                try {
                    //1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                    );
                    //2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        //如果没有异常消息，结束循环
                        break;
                    }
                    //解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3.创建订单
                    proxy.createVoucherOrder(voucherOrder);
                    //4.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    break;
                }
            }
        }

        /**
         * 创建消费组（若不存在），避免启动时报NOGROUP错误
         */
        private void createGroupWithRetry() {
            while (true) {
                try {
                    //XGROUP CREATE stream.orders g1 0 MKSTREAM（从头消费，保证消息不丢失；stream不存在时自动创建）
                    stringRedisTemplate.execute((RedisCallback<String>) connection -> {
                        connection.xGroupCreate(QUEUE_NAME.getBytes(StandardCharsets.UTF_8),
                                GROUP_NAME, ReadOffset.from("0"), true);
                        return null;
                    });
                    return;
                } catch (Exception e) {
                    //消费组已存在（BUSYGROUP），无需重复创建
                    if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                        return;
                    }
                    //Redis未就绪等情况，稍后重试
                    log.warn("创建消费组失败，稍后重试：{}", e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}
