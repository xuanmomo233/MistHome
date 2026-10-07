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
- SQLite 默认存储，可切 MySQL（切换时启动自动迁移旧数据）
- BungeeCord 跨服：家园归属子服，任何服可 `/mh home` 自动跳服回家
- SelfHome 旧数据导入器（yml + MySQL 双数据源，自动合并）

## 环境

- 服务端：Mohist 1.20.1（兼容 Bukkit/Spigot API 的混合端均可尝试）
- Java：17+

## 构建

```
gradlew build
```

产物：`build/libs/MistHome-<version>.jar`（shadow jar，已内嵌 HikariCP/SQLite/MySQL 驱动）

## 玩家指令

| 命令 | 说明 |
|---|---|
| `/mh` 或 `/mh help` | 帮助 |
| `/mh create [模板]` | 创建家园 |
| `/mh home` | 回自己的家（跨服时自动跳服） |
| `/mh gui` | 家园主菜单 |
| `/mh visit <玩家>` | 参观对方家园 |
| `/mh list` | 公共家园列表（GUI） |
| `/mh invite <玩家>` / `accept` / `deny` | 邀请/接受/拒绝 |
| `/mh members` | 成员管理菜单 |
| `/mh ban <玩家>` / `unban` | 封禁/解封 |
| `/mh public` / `private` | 公开/私密切换 |
| `/mh setspawn` | 设置家内出生点 |
| `/mh upgrade` | 升级家园档位 |

## 管理员指令

游戏内 `/mh admin ...`；控制台/RCON 去掉 `/` 直接 `mh admin ...`：

| 命令 | 控制台 | 说明 |
|---|---|---|
| `admin info` | ✅ | 存储类型、池世界、槽位、跨服名等概要 |
| `admin reload` | ✅ | 重载配置（storage/cross-server 需重启才生效） |
| `admin unload <worldIndex>` | ✅ | 强制卸载并归档某个池世界 |
| `admin visit <玩家>` | ❌ | 管理员直达任意家园（跨服会自动跳服） |
| `admin import-selfhome <旧服根目录> [数据目录] [cleanup]` | ✅ | SelfHome 批量迁移 |

权限节点：`misthome.use`（玩家）、`misthome.visit`（参观）、`misthome.admin`、`misthome.bypass`。

## 跨服部署（BungeeCord）

语义：**家园世界存档固定在创建它的子服磁盘上**，`homes.server` 列记录归属服。玩家在任何子服 `/mh home`、`/mh visit`、点公共列表都会自动 Connect 跳到归属服，落地后免吟唱直接进家。

### 开启步骤

1. 准备一个 MySQL 库，所有子服的 `storage` 段都指向它：

```yaml
storage:
  type: mysql
  mysql:
    host: 127.0.0.1
    port: 3306
    database: misthome
    user: root
    password: "***"
```

2. 每个子服配置各自的 BungeeCord 服务器名（必须和 Bungee `config.yml` 一致）：

```yaml
cross-server:
  enabled: true
  name: "B_家园"      # 各服分别填自己的名字
```

3. 重启生效（热重载不切换存储/跨服）。

### 存储切换自动迁移

插件用 `plugins/MistHome/storage-type.txt` 记录上次使用的存储后端。启动时检测到 `storage.type` 变化（如 sqlite → mysql），会自动：

- 连上旧库 → 全量复制 homes（保留 id）+ 成员角色 + 封禁名单 → 更新标记文件
- 已有同 id 记录跳过不覆盖；失败会中止启动并在日志给出处置方式
- **归档目录（homes/）不参与迁移**——存档始终属于产生它的那台机器

旧数据归属认领：跨服开启后首次启动，`server` 列为空且归档目录在本机的家会自动认领为本服；归档不在本机的家访问时提示"归属未登记"，等对应服启动新版后自动认领。

## SelfHome 迁移

```
mh admin import-selfhome <旧服根目录> [SelfHomeMain目录] [cleanup]
```

- 自动读 `plugins/SelfHomeMain/config.yml`：`Type: MySQL` 时连旧库读 `SelfHomeMain_Users` 全量数据（MySQL 优先，yml 补充）；连接失败降级为仅 yml
- 按出生点选择 2×2 region 窗口截取旧世界，归一化归档，首次进家自动平移实体/方块实体坐标
- `cleanup`：成功项旧世界目录移入 `<旧服根目录>/_已迁移/`（不做删除，可回滚）
- 已有 MistHome 记录的家跳过；移动失败的旧目录数据不受影响，解锁占用后重跑命令即可补移

## 潜在问题与注意事项

### 跨服相关

- **邀请是本服内存态**：`/mh invite` 的邀请存在发起服内存里，受邀玩家必须在同一服执行 `/mh accept`；跨服邀请先进家再加成员即可绕过
- **封禁踢人是本服动作**：ban 后跨服广播让其他服缓存失效（保护立即生效），但若对方正在别服你家里站着，不会被强制踢回主世界
- **缓存失效广播需要载体**：变更家数据的瞬间若本服无任何玩家在线，广播发不出去；其他服缓存最多残留到下次自然刷新
- **跳服依赖 BungeeCord**：仅 BungeeCord/Velocity 代理环境有效；直连多服（无代理）请勿开启 cross-server
- **`name` 必须与 Bungee config 一致**：写错服名会导致 `Connect` 失败（玩家留在原地，pending 待办下次进服会重试跳转）
- **家建在哪就归哪**：在哪个子服执行 `/mh create`，存档就落在哪个子服磁盘。建议公告引导玩家到指定家园服建家（如需强制限制可加配置，暂未实现）

### 通用

- **server 列为空的旧家**：跨服关闭时创建/导入的家 server 为 `""`；开启跨服后由启动认领逻辑自动归属到"归档所在机"。别把归档目录手动复制到多台机器，会造成多台机都认领同一个家（数据分叉）
- **homes-per-world / slot-size 确定后勿改**：会影响已停放家的槽位映射
- **Multiverse 残留世界会锁目录**：迁移 cleanup 报 AccessDeniedException 时先 `mvlist` + `mv remove <名字>` 释放句柄
- **`admin reload` 不重载存储**：改 storage/cross-server 必须重启

## 文档

- 架构设计：[DESIGN.md](DESIGN.md)
