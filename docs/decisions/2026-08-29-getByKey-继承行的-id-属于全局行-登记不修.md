# 登记：`getByKey` 返回的继承行，`id` 属于全局那一行 —— 本轮不修

**日期**: 2026-08-29
**状态**: **登记不修**（不是遗漏）
**相关**: PR #315（`list()` 加继承叠加）、PR #325（`getByKey` 补同一条梯子）

---

## 事实

`CanvasThresholdsController.getByKey` 现在与 `list()` 走**同一条叠加梯子**
（per-factory 优先，缺失时带出全局 `'*'` 行并标 `inherited=true`）：

```java
List<FactoryThreshold> own     = asList(repository.findByFactoryIdAndThresholdKey(factoryId, key));
List<FactoryThreshold> globals = asList(repository.findByFactoryIdAndThresholdKey("*", key));
List<FactoryThreshold> merged  = overlay(factoryId, own, globals);
```

这修的是一个真实的自相矛盾：`list()` 里带 `inherited=true` **显示出来了**，
点进去 `getByKey` **404「阈值不存在」**。

⚠️ **但继承行的 `id` 是全局那一行的 id。**
对它发 `PUT /canvas-thresholds/{id}` 会改掉 **`factory_id = '*'` 的那一行**
⇒ **影响所有工厂的默认值，不只当前这一家**。

---

## 为什么本轮不修

`list()` 早就有同样的性质，而前端已经按 `inherited` 走 `POST`（建本厂 override）而不是 `PUT`
—— 见 `web-admin/src/views/system/business-thresholds/index.vue` 的 `onSaveRow`。

`getByKey` 这条路径**当前没有任何消费方**（2026-08-29 实测）：

```
git grep -n "getThreshold\b" -- web-admin/src frontend
  web-admin/src/api/thresholdsHub.ts:124:export async function getThreshold(   <- 只有定义
git grep -rn "canvas-thresholds" -- frontend
  (空)                                                                          <- RN 侧没有
```

⇒ **只有定义、零调用**。所以这是**潜伏的**，不是在燃的。

⛔ 而「零使用量」在本仓有过明确教训：它意味着「从没接上」，**不等于「不会发生」**。
所以登记而不是忘掉。

---

## 🔻 解冻条件（谁将来接这条路径，谁要先做这两件）

1. **前端接 `getThreshold` 之前**，必须像 `list()` 那样按 `inherited` 分流：
   继承行走 `POST` 建本厂 override，⛔ 不能 `PUT`。
2. **更彻底的修法**（本轮没做）：让继承行的 `id` 不可被直接 `PUT` ——
   例如返回时把 `id` 置空并给一个 `sourceFactoryId` 字段，
   或让 `PUT` 在检测到「路径 factoryId ≠ 该行 factory_id」时拒绝并提示改用 `POST`。

   ⚠️ 第二种是**服务端护栏**，比「要求每个调用方记得分流」可靠 ——
   前者错一次就静默改掉所有工厂的默认值，而**那个错误不会报错**。
   ⇒ 真要接这条路径时，优先做服务端护栏。

---

## 判据（将来验它修没修好）

```
① 对一个 inherited=true 的行发 PUT   -> 必须被拒绝(或只影响本厂), ⛔ 不许改到 '*' 那一行
② 阴性对照: 对本厂自己的行发 PUT      -> 必须照常成功
③ 阴性对照: '*' 路径本身管理全局行     -> PUT 必须仍然能改全局默认(那是它的本职)
```

⚠️ 缺了 ②③ 的话，「修好了」可能只是把 `PUT` 全禁了。
