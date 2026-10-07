# MistHome 架构设计文档

> 玩家家园插件 · 目标平台 **Mohist 1.20.1**（Forge+Bukkit 混合端）· 纯 Java + Spigot API

## 1. 设计目标

- 每个玩家拥有独立的家园区域，供自由建造生活
- **副本式世界生命周期**：家园世界按需加载、无人超时卸载，避免常驻世界吃资源
- **共享虚空世界分区**：所有家园挤在少数几个虚空世界中（参考 MythicDungeons OneWorldDungeons），避免服务器内创建过多世界造成干扰和管理负担

## 2. 参考项目调研

| 项目 | 状态 | 借鉴点 |
|---|---|---|
| [charming-realm-system](https://github.com/caishangqi/charming-realm-system) | 2024+ 活跃，中文领域插件 | 五档权限体系（Owner/Operator/Member/Visitor/Banned）、货币升级世界范围、模板复制创建走线程池、创建队列 |
| [PlayerWorldManager](https://github.com/Prorickey/PlayerWorldManager) | 2025+ 活跃，Folia/Paper | 空世界自动卸载、世界设置（锁时间/天气）、邀请制访问 |
| [MythicDungeons](https://wiki.mythiccraft.io/mythicdungeons) | 持续维护，副本标杆 | 实例上限+排队、CleanupDelay、5 秒兜底扫描空实例、**WorldUnloadEvent 被其他插件取消导致内存泄漏**的坑、OneWorldDungeons 共享世界分区方案 |
| AdvancedSlimePaper | InfernalSuite 维护 | Slime 格式世界（需换服务端核心，本项目不采用） |

### 已确认的决策

| 决策项 | 结论 |
|---|---|
| 平台 | Mohist 1.20.1，编译依赖 `spigot-api:1.20.1` |
| 世界架构 | 共享虚空世界分区 |
| 世界生命周期 | 无人超时卸载（副本式） |
| 地块形态 | 预设模板粘贴（WorldEdit 软依赖，降级空平台） |
| 尺寸体系 | 分档升级 + 预留空间（槽位固定，软边界扩缩） |
| 边界隔离 | 事件拦截 + ProtocolLib 世界边界包（降级粒子） |
| 权限体系 | 五档 Owner/Operator/Member/Visitor/Banned |
| 访问体系 | 邀请制 + 公开/私密切换 + 公共列表 |
| 家园数量 | 每人 1 个 |
| 玩家状态 | 不隔离（与主世界共享背包/血量/经验） |
| 经济 | Vault 软依赖（创建/升级收费） |
| 存储 | SQLite 默认 + MySQL 可选（HikariCP shade 进 jar） |
| GUI | 原版箱子菜单 |

## 3. 世界分区布局

```
世界 misthome_0 内（homes-per-world=64 → 8x8 网格）：

 pitch = slot-size + gap = 512 + 64 = 576 格

   ┌─────────┐  ┌─────────┐       ┌─────────┐
   │ home 0  │  │ home 1  │  ...  │ home 7  │   每格 = 576x576 槽位
   │(可用128)│  │(可用256)│       │(可用512)│   家园软边界居中，未解锁区为预留
   └─────────┘  └─────────┘       └─────────┘
   ┌─────────┐
   │ home 8  │  ...
   └─────────┘
```

- 全局槽位索引 `slotIndex` 递增分配（取最小未占用值，删除家园后槽位可复用）
- `worldIndex = slotIndex / homesPerWorld`；`innerIndex = slotIndex % homesPerWorld`
- 网格坐标 `gridX = innerIndex % cols`，`gridZ = innerIndex / cols`，网格整体以世界原点为中心对称铺开
- 世界文件夹 `misthome_0`、`misthome_1`... 位于服务端根目录，**按需创建**，不在启动时批量生成
- 实现见 `SlotAllocator` / `HomeRegion`

## 4. 世界生命周期（副本式）

```
玩家请求进入家园
   │
   ├─ 家园所在世界已加载？── 是 ──→ 传送至家园出生点
   │                     │
   │                     └─ 否 → WorldCreator(generator=VoidGenerator).createWorld()
   │                              → 应用世界规则 → 传送
   │
世界内最后一名玩家离开（传送/下线）
   │
   ├─ sweep 周期任务（默认 20s）记录 emptySince 时间戳
   │
   └─ 空载持续超过 unload-delay-seconds（默认 300s）
        → world.save() → Bukkit.unloadWorld(world, true)
        → 卸载失败（被其他插件取消/持有引用）→ WARN 日志 + 下轮重试
```

要点：

- **加载**：`HomeWorldManager.ensureLoaded(worldIndex)` 返回 `CompletableFuture`，并发请求共享同一 Future，主线程执行 WorldCreator。虚空世界加载本身很轻（无区块生成），主要耗时在磁盘读取
- **卸载判定**：不做逐个事件埋点，用周期兜底扫描覆盖所有离开途径（传送、下线、崩溃）
- **卸载失败**：参考 MythicDungeons，其他插件可能取消 `WorldUnloadEvent` 或持有世界引用导致泄漏 —— 失败时告警并持续重试
- **关服**：`shutdown()` 将所有家园世界内玩家送回主世界出生点后逐一卸载保存
- **世界规则**：加载时应用 `DO_MOB_SPAWNING=false`、`DO_FIRE_TICK=false`、`MOB_GRIEFING=false`、`DO_WEATHER_CYCLE=false`、`DO_DAYLIGHT_CYCLE=false` + 锁定时间（可配置化）

## 5. 槽位与升级

- 每个家园的物理槽位**始终按最大档预留**（slot-size 512）
- 当前档位决定**软边界半径**（如 64 → 128 → 256）
- 升级 = 修改档位字段 + 扩大边界，**零数据迁移**
- 事件拦截保护的范围 = 当前档可用半径；预留区内也禁止操作（防止提前侵占）

## 6. 权限体系

| 角色 | 建造 | 邀请 | 封禁 | 访问 | 改设置 |
|---|---|---|---|---|---|
| OWNER 所有者 | ✅ | ✅ | ✅ | ✅ | ✅ |
| OPERATOR 管理者 | ✅ | ✅ | ✅ | ✅ | ✅ |
| MEMBER 成员 | ✅ | ❌ | ❌ | ✅ | ❌ |
| VISITOR 访客 | ❌ | ❌ | ❌ | ✅ | ❌ |
| BANNED 被封禁 | ❌ | ❌ | ❌ | ❌ | ❌ |

- 家园主本人 = OWNER（不入 members 表，代码直接判定）
- 邀请默认给 MEMBER，可在设置面板调整
- BANNED 覆盖一切：即使家园公开也无法进入

## 7. 边界隔离与展示

**事件拦截（保护）**：监听 `BlockPlace/BlockBreak/PlayerInteract/桶类/实体伤害` 等事件，坐标反查家园 → 角色判定 → 无权取消。拦截范围包含未解锁的预留区。

**客户端边界（可视化）**：
- 已装 ProtocolLib：发 `ClientboundInitializeBorder` 等世界边界包，中心=家园中心，直径=可用半径×2，玩家进入家园范围时下发、离开时还原
- 未装：降级为周期粒子线（`particle-interval-ticks` 刷新）
- 升级后重发包刷新边界

## 8. 模板系统

- 模板目录 `plugins/MistHome/templates/*.schem`（WorldEdit 剪贴板格式）
- 创建家园时玩家选模板（`/mh create [模板名]` 或 GUI 选择）
- 已装 WorldEdit：`ClipboardFormat` 读文件 → `EditSession` 粘贴到区域中心（分批提交防卡服）
- 未装/模板不存在：降级生成 `fallback-platform-size` 边长的小平台
- **Mohist 注意**：需安装 WorldEdit 的 **Bukkit 插件版**（非 Forge mod 版）；模组方块能否正常粘贴取决于 WE 对 Forge 注册表的处理，需实测

## 9. 访问体系

- **邀请**：`/mh invite <玩家>` → 内存邀请记录（`InviteManager`，默认 30s 过期）→ `/mh accept|deny`
- **公开**：`/mh public` 切换可见性 → 出现在 `/mh list` 公共列表 GUI → 任何有 `misthome.visit` 权限的玩家可传送参观（BANNED 除外）
- 参观传送复用同一进入流程（ensureLoaded → 传送到家园出生点）

## 10. 数据表结构

```sql
homes (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,  -- sqlite: INTEGER PRIMARY KEY
  owner_uuid    VARCHAR(36) NOT NULL UNIQUE,        -- 每人 1 个家园
  name          VARCHAR(64) NOT NULL,
  slot_index    INT NOT NULL UNIQUE,                -- 全局槽位
  tier_level    INT NOT NULL DEFAULT 0,
  template      VARCHAR(64),
  visibility    VARCHAR(8) NOT NULL DEFAULT 'PRIVATE',
  spawn_x       DOUBLE, spawn_y DOUBLE, spawn_z DOUBLE,
  spawn_yaw     FLOAT,  spawn_pitch FLOAT,
  created_at    BIGINT NOT NULL
)

home_members (
  home_id       BIGINT NOT NULL,
  player_uuid   VARCHAR(36) NOT NULL,
  role          VARCHAR(16) NOT NULL,               -- OPERATOR/MEMBER
  added_at      BIGINT NOT NULL,
  PRIMARY KEY (home_id, player_uuid)
)

home_bans (
  home_id       BIGINT NOT NULL,
  player_uuid   VARCHAR(36) NOT NULL,
  banned_at     BIGINT NOT NULL,
  PRIMARY KEY (home_id, player_uuid)
)
```

- 邀请不落地（纯内存）
- `allocateSlot()` = `SELECT MIN(slot_index)` 未占用值（可用 `slot_index+1 NOT IN` 或 0 起步探测）
- SQLite 与 MySQL 共用 `JdbcStorage` 基类，方言差异仅在建表语句

## 11. 命令表

| 命令 | 说明 | 权限 |
|---|---|---|
| `/mh` / `/mh gui` | 打开家园主菜单 GUI | misthome.use |
| `/mh create [模板]` | 创建家园（扣 create-price） | misthome.use |
| `/mh home` | 吟唱后传送回家 | misthome.use |
| `/mh visit <玩家>` | 参观指定玩家家园 | misthome.visit |
| `/mh invite <玩家>` | 邀请参观/共建 | 家园内 OPERATOR+ |
| `/mh accept` `/mh deny` | 处理邀请 | - |
| `/mh setspawn` | 设置家园出生点 | 家园内 OPERATOR+ |
| `/mh public` `/mh private` | 切换可见性 | 家园内 OPERATOR+ |
| `/mh members` | 成员管理 GUI | 家园内 MEMBER+ 可查看 |
| `/mh ban/unban <玩家>` | 封禁管理 | 家园内 OPERATOR+ |
| `/mh upgrade` | 升级家园档位（扣 Vault 货币） | OWNER |
| `/mh list` | 公共家园列表 GUI | misthome.visit |
| `/mh admin <...>` | 管理员工具（查看/进任意家园/强制卸载世界） | misthome.admin |

## 12. 包结构

```
dev.mist.home
├── MistHomePlugin            主类（生命周期装配）
├── config/MistConfig         配置读取
├── model/                    Home / HomeRole / HomeTier / HomeVisibility
├── world/                    HomeWorldManager / VoidGenerator / SlotAllocator / HomeRegion
├── home/HomeService          家园缓存与坐标反查
├── storage/                  Storage 接口 / JdbcStorage / SqliteStorage / MysqlStorage
├── protect/ProtectionListener 区域保护事件拦截
├── boundary/BorderService    ProtocolLib 边界包 / 粒子降级
├── template/TemplateService  WorldEdit 模板粘贴 / 降级平台
├── economy/EconomyService    Vault 钩子
├── invite/InviteManager      内存邀请
├── teleport/TeleportService  吟唱传送 + 冷却 + 打断
├── gui/                      箱子菜单（主菜单/成员/公共列表/模板选择）
└── command/MistHomeCommand   命令分发
```

## 13. 风险与注意事项

1. **世界卸载泄漏**：Forge mod 或其他插件可能持有世界/区块引用或取消 `WorldUnloadEvent`，导致卸载失败 → 已实现告警+重试；排查时用 MythicDungeons 思路（先确认不是自家代码引用）
2. **WorldEdit on Mohist**：必须安装 Bukkit 版 WE；Forge 端 WE mod 不提供 Bukkit 插件 API；混端下 WE 兼容性需实测，模板含模组方块时行为待验证
3. **ProtocolLib on Mohist**：Mohist 对 Bukkit 插件兼容性较好但仍需实测边界包（`ClientboundInitializeBorder`）；失败自动降级粒子
4. **世界文件夹**：必须位于服务端根目录（Bukkit WorldContainer 限制），命名 `misthome_N`；不要与其他插件世界名冲突
5. **实体/掉落物**：虚空世界 unload 前 save() 保证持久化；挂机农场在卸载后停摆（这正是设计目的）
6. **并发**：所有世界加载/卸载必须在主线程；DB 操作全部异步；HomeService 缓存用 ConcurrentHashMap

## 14. 开发里程碑

- [x] M1 存储层：JdbcStorage CRUD + 建表 SQL + HomeService 缓存与反查索引
- [ ] M2 家园生命周期：create/home 命令 + 传送吟唱 + 世界按需加载联调
- [ ] M3 保护：ProtectionListener 全事件覆盖 + 预留区拦截
- [ ] M4 权限与成员：invite/members/ban + GUI
- [ ] M5 模板：WorldEdit 粘贴 + 模板选择 GUI
- [ ] M6 边界：ProtocolLib 发包 + 粒子降级
- [ ] M7 公共列表/参观 + upgrade 经济闭环
- [ ] M8 管理员工具 + 收尾（消息文件、热重载、文档）
