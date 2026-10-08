# hm-dianping 黑马点评

一个仿「大众点评」的前后端完整项目，包含店铺浏览、探店笔记、优惠券秒杀、关注、签到等常见 O2O 业务功能。

项目分为两部分：

- `backend/`：后端服务，基于 Spring Boot + MySQL + Redis + Redisson 实现，对外提供 RESTful 接口
- `frontend/`：前端静态页面，基于 Nginx 部署，通过 `/api` 反向代理调用后端接口

## 目录结构

```
hm-dianping
├── backend/                    # 后端 Spring Boot 工程
│   ├── pom.xml
│   └── src/main
│       ├── java/com/hmdp
│       │   ├── config/         # MVC、MyBatis-Plus、Redisson 等配置
│       │   ├── controller/     # 接口层
│       │   ├── service/        # 业务层
│       │   ├── mapper/         # 数据层（MyBatis-Plus）
│       │   ├── entity/         # 数据库实体
│       │   ├── dto/            # 数据传输对象
│       │   └── utils/          # 工具类（缓存、分布式锁、拦截器、ID 生成等）
│       └── resources
│           ├── application.yaml
│           ├── db/hmdp.sql     # 数据库初始化脚本
│           ├── mapper/         # MyBatis XML 映射文件
│           ├── seckill.lua     # 秒杀预扣库存 Lua 脚本
│           └── unlock.lua      # 释放锁 Lua 脚本
└── frontend/                   # 前端环境（Nginx + 静态页面）
    ├── nginx.exe               # Nginx（Windows）
    ├── conf/nginx.conf         # Nginx 配置（静态资源 + /api 反向代理）
    └── html/hmdp/              # 前端页面、JS、CSS、图片
```

## 技术栈

| 层次 | 技术 |
| ---- | ---- |
| 后端框架 | Spring Boot 2.3.12、Spring MVC |
| 数据层 | MyBatis-Plus 3.4.3、MySQL 5.x |
| 缓存 | Redis（Lettuce + Redisson 3.13.6） |
| 异步消息 | Redis Stream（秒杀订单异步下单） |
| 前端 | 原生 HTML / CSS / JS（静态资源） |
| Web 服务器 | Nginx（静态资源托管 + 反向代理） |
| 其他 | Hutool、Lombok |

## 快速开始

### 1. 准备环境

- JDK 1.8、Maven 3.x
- MySQL 5.x
- Redis（需支持 Lua、BitMap、GEO、Stream 等特性）

### 2. 初始化数据库

创建 `hmdp` 数据库并执行初始化脚本：

```bash
mysql -u root -p -e "CREATE DATABASE hmdp DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p hmdp < backend/src/main/resources/db/hmdp.sql
```

### 3. 启动后端

数据库连接、Redis 地址均支持通过环境变量覆盖（默认连接本机）：

| 环境变量 | 默认值 |
| -------- | ------ |
| `DB_URL` | `jdbc:mysql://localhost:3306/hmdp?...` |
| `DB_USERNAME` | `root` |
| `DB_PASSWORD` | （无） |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |

```bash
cd backend
mvn spring-boot:run
```

后端默认监听 **8081** 端口。

### 4. 启动前端

前端由 Nginx 托管，配置文件为 `frontend/conf/nginx.conf`：

- 静态页面：`html/hmdp`，监听 **8080** 端口
- `/api/**` 的请求会去掉 `/api` 前缀后转发到 `http://127.0.0.1:8081`（即后端服务）

Windows 下启动：

```bash
cd frontend
nginx.exe
```

浏览器访问 <http://localhost:8080/> 即可进入前端页面。

> 若后端端口有调整，请同步修改 `frontend/conf/nginx.conf` 中 `proxy_pass` 的地址。

## 主要功能

- **用户体系**：手机验证码登录、登录校验（拦截器 + Token 双刷新）、用户信息脱敏查询
- **商户**：商户类型列表、商户分页查询、按关键词搜索
- **附近商户**：基于 Redis GEO 按坐标检索 5km 内商户，按距离排序分页
- **探店笔记**：发布、分页查询、按用户查询、点赞排行榜、点赞/取消点赞（一人一单）、关注/取关、好友共同关注
- **优惠券**：普通优惠券下单、秒杀优惠券下单
- **秒杀**：Lua 脚本原子预扣库存 + Redis Stream 异步下单落库，一人一单、库存不超卖、消息不丢失
- **签到**：基于 Redis BitMap 的每日签到与连续签到天数统计
- **其他**：图片上传、店铺详情缓存（互斥锁 / 逻辑过期）、全局异常处理与统一响应
