# K8s Snowflake MachineId Generator

基于 Kubernetes 和 Nacos 的雪花算法机器ID自动分配解决方案。

## 背景
当前微服务是需要上k8s环境，雪花算法中的workerId和dataCenterId目前是手动给每个服务分配的，上k8s后无法继续使用，查了一圈没什么现成解决方案只好自己动手写了一个。

## 核心特性

- 🎯 **自动分配**：自动为服务实例分配唯一的机器ID组合用于生成雪花Id
- 🔒 **分布式安全**：使用 Redis 分布式锁防止ID冲突
- ☁️ **云原生**：专为 Kubernetes + Nacos 环境设计
- 🔄 **容错机制**：包含重试机制和异常处理

## ID 配置范围

- `serverServiceNode` (workerId): 0-127 (共128个)
- `serverRoomNode` (dataCenterId): 0-7 (共8个)
- **最大支持**: 1024 个唯一服务实例

## 依赖要求

nacos
redis

## 工作原理

### 初始化流程
1. **查询已用组合**：从 Nacos 获取所有服务实例的已分配ID组合
2. **寻找可用组合**：按顺序遍历所有可能的组合，找到第一个未使用的
3. **Redis 加锁**：使用 Redis 分布式锁临时占用选中的组合
4. **更新元数据**：将分配的组合写入 Nacos 实例元数据
5. **异步释放锁**：确认元数据更新成功后释放 Redis 锁

## 环境变量

分配完成后自动设置：
- `server_service_node`: workerId (0-127)
- `server_room_node`: dataCenterId (0-7)

## 配置参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `spring.cloud.nacos.config.shared-configs[0].group` | Nacos 配置组 | DEFAULT_GROUP |

## 异常处理

- **无可用组合**：抛出异常提示"雪花算法分配失败"
- **Nacos 异常**：重试机制和详细错误日志
- **Redis 异常**：锁自动过期机制（10分钟）


## 快速开始

- 1.将SERVICE_NAME_LIST中的服务名换成你自己的，并将SnowflakeIdConfig文件放入工程内。
- 2.在初始化雪花算法位置获取环境变量

```java
// dataCenterId
String serverRoomNodeStr = System.getProperty("server_room_node");
// workerId
String serverServiceNodeStr = System.getProperty("server_service_node");
```

