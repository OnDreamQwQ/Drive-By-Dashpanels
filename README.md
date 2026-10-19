# Drive By Dashpanels

桥接 **Dashpanels**模组 和 **Drive By Wire** 模组，使面板能够支持线控

## 使用方法

1.使用dashpannels模组的线缆将控制面板连接到面板链接中心

2.使用线控模组的线缆右键面板链接中心，滚轮选择模块

右键面板链接中心：显示有哪些模块

**潜行右键**：清理。会删掉改名留下的记录，并移除那些「频道背后已经没有模块」的线缆连接，然后报出清理了多少条。

## 配置文件

```toml
[common]
    # 改名后把线缆连接迁移到新名字（默认 true）
    migrateConnectionsOnRename = true
    # 模块被拆掉时自动删除它留下的线缆连接（默认 false）
    removeConnectionsOnModuleLoss = false
```

**`migrateConnectionsOnRename = true`（默认）**：改名时直接把线缆的频道改写成新名字。改名不影响任何正在工作的连接。

**`= false`**：不迁移，旧名字作为「别名」保留 —— 连接照样工作，旧名字也仍然出现在滚轮列表里（所以滚轮不会卡住），但**每改名一次频道表就多一条**，直到你潜行右键清理。最多记 128 条，超出后最旧的先丢。

别名记录存在方块实体里，重新加载区块/重启都不会丢。

**`removeConnectionsOnModuleLoss = true`**：模块拆除后立刻删除连接

默认关掉是有原因的：面板所在区块卸载时，Dashpanels 的网络会短暂变空，照着删会把你真实的接线删掉。想清理就**潜行右键**中心方块，那一下会删掉「背后已经没有模块」的连接并报出条数。

## 依赖

| 依赖 | 版本 | 必需 |
| --- | --- | --- |
| Minecraft | 1.21.1 | 是 |
| NeoForge | 21.1+ | 是 |
| Dashpanels | 2.0+ | 是 |
| Drive By Wire | 0.3.0+ | 是 |

## 从源码构建

上面三个 mod 都不在公开 maven 仓库里，所以要先手动把它们放进 `libs/`（这几个 jar 不会进 git，也不该被再分发）：

```
libs/
├── dashpanels-2.1+neoforge1.21.1.jar
└── drivebywire-0.3.0.jar
```

然后：

```bash
./gradlew build
```

产物：`build/libs/Drive-By-Dashpanels-1.21.1-<版本>.jar`

## 协议

[MIT](LICENSE)。Dashpanels、Drive By Wire 各自的内容归各自作者所有，MIT 不覆盖它们。

## 声明

此项目代码由AI生成 ~~大肥鱼真是太好用了你知道吗~~

## ToDo

- 自己做一个模型
- 添加选择ui
- 线控对模块充能
