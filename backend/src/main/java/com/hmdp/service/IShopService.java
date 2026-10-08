package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    Result queryById(Long id);

    /**
     * 根据id更新商铺信息
     * @param shop
     * @return
     */
    Result update(Shop shop);

    /**
     * 根据商铺类型分页查询商铺信息（传入坐标时按距离排序）
     * @param typeId 商铺类型
     * @param current 页码
     * @param x 经度（可为空）
     * @param y 纬度（可为空）
     * @return 商铺列表
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
