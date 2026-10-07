# MistHome 架构设计文档

> 玩家家园插件 · 目标平台 **Mohist 1.20.1**（Forge+Bukkit 混合端）· 纯 Java + Spigot API

## 1. 设计目标

- 每个玩家拥有独立的家园区域，供自由建造生活
- **世界池 + 插件存档**：家园数据以 mca 文件组形式存于插件目录（唯一真身），
  少量池世界仅作为运行画布；家园按需停放入空闲槽位、无人超时卸载归档、
  世界目录删除重建 —— 常驻世界数量有界，家园存档数量无界

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
| 世界架构 | 世界池：插件目录存档 + region 对齐槽位 + 一次性使用 + 卸载删除重建 |
| 世界生命周期 | 按需停放加载、无人超时卸载归档（副本式） |
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

## 3. 存储布局与槽位几何

```
plugins/MistHome/homes/<玩家名_uuid8>/        家园存档（唯一真身）
    region/r.0.0.mca  r.1.0.mca  ...        方块+方块实体（归一化本地坐标）
    entities/r.0.0.mca ...                  实体（机械动力装配体在此）
    poi/r.0.0.mca ...                       兴趣点
    meta.json                               文件清单+时间戳

池世界 misthome_0/1/...                     运行画布（可随时删除重建）
    region/r.x.z.mca                        停放时把家园文件写到槽位对应坐标
```

- 槽位边长 `slot-size` 必须是 **512 的倍数**（1024 = 2×2 region），槽位紧密排列无 gap
- 每个槽位独占 `(slotSize/512)²` 组 mca 文件 → 家园与物理位置完全解耦，可自由拼接迁移
- 存档文件名使用槽位**本地坐标**（r.0.0 起），恢复时按目标槽位基坐标重命名
- 槽内可用半径上限 = `slotSize/2 - 模拟距离`（默认 352），保证玩家够不到邻居空槽
- 实现见 `SlotAllocator`（region 对齐数学）/ `HomeArchiveService`（文件搬运）

## 4. 停放生命周期

```
玩家请求进入家园
   │
   └─ ensureParked(home)
        ├─ 已停放 → 确保世界加载 → （恢复补缺）→ 传送
        └─ 未停放 → 分配空闲槽位（已加载世界优先 → 未加载世界 → 自动扩池）
                  → 台账登记 → 世界加载 → 存档文件写入槽位 → 传送

世界内最后一名玩家离开 → sweep 记录 emptySince
   └─ 空载超过 unload-delay-seconds
        → world.save() → unloadWorld
        → 异步：各停放家园归档回插件目录 → 解除停放
        → 全部归档成功 → 删除世界目录（delete-world-on-unload）
```

铁律与兜底：

- **槽位一次性使用**：世界一次生命周期内每个槽位只写一次文件、永不覆写，
  彻底绕开"运行中覆写已打开 mca"和"脏槽位复用"两类损坏
- **污染标记**：玩家模拟距离覆盖到空闲槽位 → 标记作废不再停放
  （正常配置下 margin≥模拟距离，永不触发；自定义小 margin 时兜底）
- **卸载失败**：其他插件可能取消 WorldUnloadEvent → WARN + 下轮重试
- **崩溃恢复**：`pool-state.json` 台账记录停放关系；启动时扫描世界目录残留，
  台账对应文件重新归档、孤儿文件隔离到 `recovery/`
- **关服**：同步执行归档+删目录（不依赖异步调度器）

## 5. 槽位与升级

- 物理槽位由停放台账动态决定；DB 中 `slot_index` 仅作唯一序号
- 出生点存储为**相对槽位中心偏移**（spawn_x/z 偏移 + spawn_y 绝对高度）
- 升级 = 修改档位 + 扩大软边界，零数据迁移、与停放位置无关

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
├── MistHomePlugin            主类（生命周期装配 + 热重载）
├── config/MistConfig         配置读取 + 校验
├── model/                    Home / HomeRole / HomeTier / HomeVisibility
├── world/                    HomeWorldManager(池化) / VoidGenerator / SlotAllocator / HomeRegion
├── archive/HomeArchiveService 家园 mca 归档/恢复/manifest/崩溃核对
├── home/HomeService          懒加载缓存与坐标反查（byOwner/byId/roleCache + 停放表）
├── storage/                  Storage 接口 / JdbcStorage / SqliteStorage / MysqlStorage
│                             / StorageException / DuplicateKeyException
├── protect/ProtectionListener 区域保护全事件拦截
├── boundary/BorderService    ProtocolLib 边界包 / 粒子降级
├── template/TemplateService  WorldEdit 模板粘贴 / 降级平台
├── economy/EconomyService    Vault 钩子
├── invite/InviteManager      内存邀请
├── teleport/TeleportService  吟唱传送 + 冷却 + 打断（管理员 bypass）
├── actions/HomeActions       命令/GUI 共享动作
├── gui/                      Menu 框架 / MainMenu / MembersMenu / PublicHomesMenu / TemplateMenu
└── command/MistHomeCommand   命令分发（全部子命令实装）
```

## 13. 风险与注意事项

1. **文件操作时机**：世界加载期间 region 文件句柄归 Minecraft 所有，插件不得碰；
   所有归档在卸载成功后执行，所有恢复在世界加载前/文件缺失时执行
2. **归档失败**：归档失败的家园不解除停放、不删世界目录（数据安全第一），人工介入
3. **世界卸载泄漏**：Forge mod 可能取消 `WorldUnloadEvent` → 告警+重试
4. **隔离边界**：同池世界中多个活跃家园仍共享该世界 tick 线程；
   `slotSize - 2*maxRadius < 模拟距离` 时贴边家园会互相加载机械 → 启动告警
5. **WorldEdit on Mohist**：必须安装 Bukkit 版 WE；混端兼容性需实测
6. **并发**：世界加载/卸载/停放分配在主线程；归档文件 IO 与 DB 操作异步

## 14. 开发里程碑

- [x] M1 存储层：JdbcStorage CRUD + 建表 SQL + HomeService 缓存与反查索引
- [x] M2 家园生命周期：create/home 命令 + 传送吟唱 + 世界按需加载联调
- [x] M3 保护：ProtectionListener 全事件覆盖 + 预留区拦截
- [x] M4 权限与成员：invite/members/ban + GUI
- [x] M5 模板：WorldEdit 粘贴 + 模板选择 GUI
- [x] M6 边界：ProtocolLib 发包 + 粒子降级
- [x] M7 公共列表/参观 + upgrade 经济闭环
- [x] M8 管理员工具 + 收尾（热重载、文档）
- [x] M9 世界池重构：插件目录 mca 存档 + region 对齐槽位 + 一次性使用
       + 污染标记 + 卸载归档删世界 + 崩溃核对 + spawn 偏移存储
