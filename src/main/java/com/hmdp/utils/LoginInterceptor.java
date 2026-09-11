package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * 拦截器（拦截需要用户信息即需要登录的路径，判断ThreadLocal中是否存在用户）
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //ThreadLocal中无用户，拦截
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        //ThreadLocal中有用户，放行
        return true;
    }
}
