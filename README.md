# MistHome

Minecraft 玩家家园插件 —— 共享虚空世界分区 + 副本式按需加载/无人超时卸载。

## 特性

- 每位玩家一个独立家园区域（默认每人 1 个）
- 家园共享虚空世界网格化分区，避免服务器内世界泛滥
- 世界副本式生命周期：有人进入才加载，无人超时自动卸载
- 家园档位升级：预留物理槽位，升级仅扩大可用半径，零迁移
- 五档权限：Owner / Operator / Member / Visitor / Banned
- 邀请制 + 可设公开参观（公共家园列表 GUI）
- WorldEdit 模板粘贴（软依赖，降级空平台）
- ProtocolLib 客户端世界边界（软依赖，降级粒子）
- Vault 经济（创建/升级收费，软依赖）
- SQLite 默认存储，可切 MySQL

## 环境

- 服务端：Mohist 1.20.1（兼容 Bukkit/Spigot API 的混合端均可尝试）
- Java：17+

## 构建

```
gradlew build
```

产物：`build/libs/MistHome-<version>.jar`（shadow jar，已内嵌 HikariCP/SQLite/MySQL 驱动）

## 文档

- 架构设计：[DESIGN.md](DESIGN.md)
