package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    StringRedisTemplate stringRedisTemplate;

    /**
     * 秒杀下单优惠券
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //根据id查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断是否未开始秒杀
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还未开始！");
        }
        //判断是否已经结束秒杀
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        //判读库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();

        //创建锁对象（基于redis实现的分布式锁思想）
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取锁
        boolean isLock = lock.tryLock(1200);
        //若获取锁失败，则返回错误信息
        if (!isLock) {
            return Result.fail("同一用户不能重复下单！");
        }
        try {
            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //通过代理对象调用createVoucherOrder方法确保@Transactional生效，返回订单id
            return proxy.createVoucherOrder(voucherId);
        }finally {
            //释放锁
            lock.unlock();
        }
    }

    /**
     * 创建优惠券订单
     * @param voucherId
     * @return
     */
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //创建订单之前，先根据用户id和订单id查询同一用户是否已经有过订单（确保一人一单）
        Long userId = UserHolder.getUser().getId();//获取用户id
        //查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //同一用户已经秒杀过优惠券单
        if (count > 0) {
            return Result.fail("用户已经购买过一次！");
        }
        //减少库存
        boolean success = seckillVoucherService.update().
                setSql("stock = stock - 1").//set stock = stock - 1
                eq("voucher_id", voucherId).
                gt("stock", 0).//where voucher_id = ? and stock > 0（"乐观锁"的思想）
                update();
        if (!success) {
            return Result.fail("库存不足！");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIdWorker.nextId("order");//订单id（利用了全局唯一ID生成器）
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);//用户id
        voucherOrder.setVoucherId(voucherId);//代金券id
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
