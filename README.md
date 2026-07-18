# 智游 Agent —— 基于通用智能体的智能旅游助手

一个可直接演示的前后端分离 MVP。前端使用 Vue 3 + Vite，后端使用 Java 21 + Spring Boot，生产数据库使用 MySQL；路线距离与时间支持接入高德地图 Web 服务 API。

## 功能

- 按城市、天数、偏好、节奏、出行日期建立用户画像
- 展示时令标签、开放时间、推荐游玩时长、热度与客流预测
- 自动生成「经典均衡」「深度慢游」「热门打卡」三套行程
- 控制每日游玩时长，并按地理位置减少折返
- 数据源采用合规适配层：景区官网/开放 API/授权平台数据，可替换接入
- 未配置外部服务时使用内置演示数据，保证答辩现场稳定

## 快速运行

后端（默认 H2 演示库）：

```powershell
cd backend
mvn spring-boot:run
```

前端地图使用 JS API Key 和安全密钥，通过 Vite 环境变量配置：

```powershell
$env:VITE_AMAP_KEY='your-web-js-key'
$env:VITE_AMAP_SECURITY_CODE='your-security-code'
cd frontend
npm.cmd run dev
```

生产环境应为 JS Key 配置域名白名单。更严格的部署可按高德安全密钥说明改为服务代理方式，避免将安全配置直接打包进静态资源。

前端：

```powershell
cd frontend
npm.cmd install
npm.cmd run dev
```

访问 `http://localhost:5173`，API 文档入口为 `http://localhost:8080/api/health`。

## MySQL 与高德配置

```powershell
$env:SPRING_PROFILES_ACTIVE='mysql'
$env:DB_URL='jdbc:mysql://localhost:3306/smart_travel?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='your-password'
$env:AMAP_KEY='your-web-service-key'
$env:DEEPSEEK_API_KEY='your-deepseek-key'
$env:BOCHA_API_KEY='your-bocha-web-search-key'
$env:OPENWEATHER_API_KEY='your-openweather-key'
$env:WEATHERAPI_KEY='your-weatherapi-fallback-key'
cd backend
mvn spring-boot:run
```

先执行 [schema.sql](./database/schema.sql)。高德 Key 必须是 Web 服务类型。未配置 Key 时使用经纬度直线距离进行稳定降级。

高德接入行为：当目标城市没有本地数据时，规划接口自动通过 POI 2.0 搜索景点并写入数据库；景点间优先调用步行路径规划获取真实距离和耗时；`GET /api/amap/weather?city=杭州` 获取实时天气；`POST /api/amap/sync?city=苏州` 可手动同步城市景点。高德未返回评论正文时，系统只展示其实际评分字段，不伪造用户评价。

DeepSeek 使用 `deepseek-chat`。后端先生成满足天数、时长和空间约束的路线，再由模型结合自然语言要求生成每日主题、方案建议和逐景点玩法；API 异常时自动降级。密钥只放在 `DEEPSEEK_API_KEY` 环境变量中，禁止提交到仓库。

全国近期网页发现使用博查 Web Search API。申请 Key 后配置 `BOCHA_API_KEY`；系统读取具体结果 URL 和 `datePublished`，再执行 robots 校验与正文抓取。未配置正式搜索 Key 时不会退回到解析搜索网页的非稳定方案，而是返回“近期数据不足”。

## 合规与实时性说明

抖音、小红书不应通过绕过登录、验证码或反爬机制采集。本项目提供 `AttractionDataProvider` 插件接口，实际部署应接入平台开放能力、取得授权的数据服务商、景区官网或文旅局开放数据。所有动态数据记录 `sourceUrl`、`collectedAt` 和可信度；超过 TTL 的数据在产品中标记为待刷新，而不是宣称“绝对实时”。客流是带更新时间与置信度的预测值，不能替代景区官方公告。

更多内容见 [技术方案](./docs/TECHNICAL_SOLUTION.md) 与 [提交材料](./docs/SUBMISSION.md)。
