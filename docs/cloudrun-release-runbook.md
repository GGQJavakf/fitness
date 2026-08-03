# CloudBase Run 体验环境部署清单

本项目使用 CloudBase Run **容器模式**承载 Spring Boot。本文只描述部署输入，不包含真实凭据、VPC 或子网编号。

## 服务约束

- 构建上下文为仓库根目录，使用根目录 `Dockerfile`。
- 应用监听平台注入的 `PORT`，本地默认 `8080`。
- 健康检查路径为 `/actuator/health`，只返回脱敏状态。
- 建议入口类型为 `MINIAPP`；除非另有 Web 调用方，不开启公网入口。
- 服务保持无状态，训练与用户数据只写入关系型数据库。

## 创建资源前必须回读

1. CloudBase Run 和数据库位于同一区域。
2. 从数据库资源详情取得真实 VPC、子网和内网连接地址。
3. 子网安全组允许服务访问数据库端口。
4. 通过云端密钥配置注入以下变量，不写入仓库：
   - `SPRING_PROFILES_ACTIVE=staging-experience`
   - `WECHAT_APP_ID`
   - `WECHAT_APP_SECRET`
   - `FITNESS_DB_URL`
   - `FITNESS_DB_USERNAME`
   - `FITNESS_DB_PASSWORD`
   - 小程序构建阶段配置 `TARO_APP_CLOUDBASE_ENV_ID`
   - 小程序构建阶段配置 `TARO_APP_CLOUDBASE_SERVICE_NAME`
5. CloudBase Run 部署参数同时配置 `OpenAccessTypes=["MINIAPP"]` 和真实 `VpcConf`。
6. 小程序通过 `wx.cloud.callContainer` 私有链路调用服务，不依赖公网域名。

## 部署后验证

1. 回读服务详情，确认镜像版本、运行状态、入口类型和 VPC。
2. 检查 `/actuator/health` 为 `UP`。
3. 使用体验版完成微信登录、建档、生成计划、开始训练、记录一组、恢复草稿和数据导出。
4. 不输出数据库密码、微信密钥、访问令牌或完整用户数据。
