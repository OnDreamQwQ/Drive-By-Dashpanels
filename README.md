# Drive By Dashpanels

把 **Dashpanels** 面板上的模块信号，送进 **Drive By Wire** 的线缆网络。

一个方块解决问题：**面板链接中心**（`panel_link_center`）。把它接进面板所在的那条线缆网络，它就能看到网络上所有面板模块，然后每 tick 把每个模块的模拟值（0–15）推到线缆上。

- **频道名 = 模块名**。面板上有多少模块，线缆上就有多少频道，没有数量上限。
- 多路输入的模块会自动展开成 `模块名.子键`。
- 滚轮选中时代显示的就是模块名本身 —— DBW 找不到对应翻译键时会原样显示频道字符串。

## 怎么用

1. 用线缆把「面板链接中心」和面板连到同一个网络。
2. 手持**线缆**右键「面板链接中心」，滚轮选频道（列表里就是你的模块名）。
3. 再右键目标方块，建立连接。
4. 面板上的输入一动，线缆上的信号就跟着动。

右键「面板链接中心」（空手）会在聊天栏列出它当前看到的所有频道，用来确认它到底读到了什么。

**潜行右键**：清理。会删掉改名留下的记录，并移除那些「频道背后已经没有模块」的线缆连接，然后报出清理了多少条。

## 改名怎么办

**不用重连。** 在面板里把模块改名之后：

- 之前已经绑到这根线缆上的连接**继续有效**，信号跟着新名字走。
- 旧名字**仍然留在频道列表里**，所以滚轮依旧能正常切频道，不会卡在一个查不到的名字上。
- 把模块拆掉再放回来、并且改用改名后的名字，连接也会自动恢复（所以拆除时不要用潜行右键清理）。

改名记录存在方块实体里，重新加载区块/重启都不会丢；最多记 128 条，超出后按最旧的先丢。

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

## 实现说明

只有一个 mixin。

DBW 选频道时调用的是 `MultiChannelWireSource#wire$nextChannel`，而它是拿 `Block` 实例调的 —— `Block` 是单例，**没有坐标**，所以它无法知道某个具体的链接中心绑到了哪些模块。整条链路里唯一同时掌握「方块 + 坐标」的地方是 `ClientWireNetworkHandler#changeChannel(Block, boolean)`，它自己读一个静态的 `selectedSource` 字段。所以就在这个方法的 HEAD 处把该坐标的频道列表抄到客户端暂存里，方块再从这个暂存回答。

其余全部走公开 API：

```java
ModulesNetworkMember#getOrCreate()          // 加入 Dashpanels 的网络
ModulesNetwork#members / #getModules()      // 遍历成员自己的模块表
ModuleMap#asEntryList()                     // 遍历模块
IInput#getAnalog() / IMultiInput#getValues  // 读值
WireNetworkManager#trySetSignalAt(...)      // 推到线上
WireNetworkManager#getNetwork() / #removeConnection(...)  // 清理失效的线缆连接
```

模块名是**服务端**算出来的（`ModulesNetworkManager#handleAddingMember` 在客户端直接 return，客户端的网络表是空的），通过方块实体的 update tag 送到客户端。

清理失效连接时要注意：DBW 把连接记成「源坐标 + 频道 → 一堆 sink」，没有「按源整体删除」的接口，只能先用 `getNetwork()` 把 sink 读出来再逐条 `removeConnection`。这件事**故意不自动做** —— 面板所在区块卸载时网络会短暂变空，自动清理会把玩家真实的接线删掉。

## 已知限制

- 一个模块一个频道，目前没有「一个方块同时输出多个模块」的合并方块。
- 方块模型直接借用了 Dashpanels 面板链接器的模型与贴图（`control_link`），只是改了命名空间引用。
- 改名记录是按「同一个 `Module` 实例换了名字」识别的。所以先把模块**拆掉**、再放一个全新的同名模块，会被当成「没有改名」，不会继承旧名字的频道。

## 协议

[MIT](LICENSE)。Dashpanels、Drive By Wire 各自的内容归各自作者所有，MIT 不覆盖它们。