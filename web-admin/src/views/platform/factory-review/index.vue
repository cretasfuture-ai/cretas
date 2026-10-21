<!--
  段③ 引导式复核 —— 建完厂之后逐屏过一遍。

  设计卡: docs/decisions/2026-08-16-stage3-guided-review-design.md

  ## ⛔ 三列复核**不改业务数据**

  它是一层「导航 + 对账」。最后一步只在用户明确提交后追加不可变 Blueprint
  验收元数据回执；要改业务数据仍然跳到那一类**已有的** CRUD 页去改 ——
  向导自己提供编辑框就是**第二条写入路径**, 那六个 controller 里的校验
  (唯一性/编码格式/单位契约/引用完整性)要么被绕过, 要么被抄一遍。
  本仓刚踩过这个形状: `haccp_checkpoints` 两条写入路径, 而 hazardType 白名单
  只有一条路上有 —— 客户手工建被拦, 模型填反而不拦。

  ## 每一屏三份并排(目标 ③)

      你说要什么   = 那次预览的 planned(快照, ⛔ 不重算 —— 库已被改过)
      我做了什么   = 那次 apply 的 written* / skipped*(跳过的带原因)
      现在库里的   = 这一类自己的列表端点现查

  三个数对不上就**当场说出来**, ⛔ 不许把差额藏起来。

  ## ⚠️ 「去修改」分三档, 照实说

      DIRECT       跳过去能定位到那一条
      LIST_ONLY    页面不读 query, 只能跳到列表 —— **必须明说**, 假装跳准了比不跳更糟
      NO_WEB_PAGE  web 上没有这一类的维护页(产品类型只有 App 端)

  ⛔ 这三档不是我在这里定的, 它们登记在 `scripts/tests/stage3-jump-targets.txt`,
     由 `scripts/tests/check-stage3-jump-targets.py` 双向核。本文件里的
     `REVIEW_CLASSES` 与那份声明**必须一致**, 闸会比 —— 两份各写各的一定会漂。
-->
<template>
  <div class="review-page">
    <el-card shadow="never" class="head-card">
      <div class="head">
        <div>
          <h2 class="title">建厂复核</h2>
          <p class="sub">
            逐屏看一遍：<b>你说要什么</b> / <b>我做了什么</b> / <b>现在库里是什么</b>。
            三列复核不改业务数据；最后提交验收时只追加带操作者和时间的 Blueprint 回执。
          </p>
        </div>
        <div class="head-right">
          <el-select v-if="canSelectFactory" v-model="factoryId" placeholder="选择工厂" filterable
                     class="factory-select" @change="load">
            <el-option v-for="f in factories" :key="f.id" :label="`${f.name}（${f.id}）`"
                       :value="f.id" />
          </el-select>
          <div v-else class="factory-context">
            <span>{{ authStore.factoryName || '当前工厂' }}</span>
            <code>{{ factoryId || authStore.factoryId }}</code>
          </div>
        </div>
      </div>
    </el-card>

    <el-alert v-if="loadError" type="error" :closable="false" show-icon
              class="block" :title="loadError" />

    <!-- ⚠️ result=null 的两种情况必须分开说 —— 都不是「建厂什么都没写」 -->
    <el-alert v-else-if="loaded && !review?.planned?.length" type="info" :closable="false"
              show-icon class="block"
              title="这个工厂还没有成功建过厂的记录 —— 复核没有可看的内容"
              description="先到「对话建厂」把配置写进去，再回来逐屏复核。" />

    <el-alert v-else-if="loaded && review && review.result === null" type="warning"
              :closable="false" show-icon class="block"
              title="这次建厂只留下了「你说要什么」，没留下「我做了什么」"
              description="要么这份快照是在系统补上回执存档之前存的（老记录），要么那次落库没有成功。
                           ⛔ 这不等于「建厂什么都没写」——库里的行数以每一屏第三列为准。" />

    <FactoryReadiness v-if="canonicalBlueprint" :factory-id="factoryId"
      :blueprint-id="canonicalBlueprint.id" :row-version="canonicalBlueprint.rowVersion"
      :actor-key="String(authStore.user?.id ?? '')" />

    <template v-if="loaded && review?.planned?.length">
      <el-steps :active="idx" align-center finish-status="success" class="steps">
        <el-step v-for="c in REVIEW_CLASSES" :key="c.key" :title="c.title" />
      </el-steps>

      <Transition name="review-panel" mode="out-in">
      <el-card :key="current.key" shadow="never" class="content-card">
        <div class="cls-head">
          <h3 class="cls-title">{{ current.title }}</h3>
          <span class="cls-hint">{{ current.what }}</span>
        </div>

        <!-- ============ 三份并排 ============ -->
        <div class="three">
          <!--
            🔴 列名是「当时**预览**说要写什么」, ⛔ 不是「你说了什么」。
            2026-08-17 真实浏览器实测: 客户点名 3 个存放区, 系统只认出 1 个,
            这一列显示「1 条」—— 写成「你说要什么」就是**替系统把客户的话改小了**。
            没被认出来的那两个在建厂完成页的说明里(aiSummary), 不在这一列。
          -->
          <div class="col col-planned">
            <div class="col-head"><span class="col-index">01</span>当时预览说要写</div>
            <div class="col-num">{{ plannedItems.length }} 条</div>
            <!--
              ⛔ 显示 detail(客户说过的名字), ⛔ 不显示 key —— key 是机器编码
              (`FD_CHICKEN` / `MATERIAL_BATCH`), 对账用的, 客户从没说过它。
              owner 2026-08-17: 建厂过程里不许出现编程语言/字段名/编码。
            -->
            <ul class="col-list">
              <li v-for="p in plannedItems" :key="p.key">
                <span class="k">{{ p.detail || p.key }}</span>
                <el-tag size="small" :type="p.action === '跳过' ? 'info' : 'success'">
                  {{ p.action }}
                </el-tag>
              </li>
              <li v-if="!plannedItems.length" class="none">这一类当时没有内容</li>
            </ul>

            <!--
              🔴 客户说了、而系统**根本没往载荷里放**的那些东西。
              它们在 planned / written / 库里三份中一处都不出现 ——
              三个数完全对得上, 而他说的东西少了两件。**对账对不出来。**
              实测: 他说「原料冷库、车间暂存、成品仓」三个存放区, 系统建 1 个,
              另外两个此前只在建厂完成页那一屏的说明里(关掉就没了)。
            -->
            <div v-if="notBuiltItems.length" class="notbuilt">
              <div class="notbuilt-head">⚠️ 你说了，但这几样没配上（{{ notBuiltItems.length }}）</div>
              <ul class="col-list">
                <li v-for="n in notBuiltItems" :key="n.item">
                  <span class="k">{{ n.item }}</span>
                  <span class="d">{{ n.why }}</span>
                </li>
              </ul>
              <p class="col-note">怎么补：{{ notBuiltItems[0].howToFix }}</p>
            </div>
            <!-- 🔴 反方向, ⛔ 不与上面那块合并: 两者措辞正好相反 -->
            <div v-if="extraBuiltItems.length" class="notbuilt extra">
              <div class="notbuilt-head">
                ⚠️ 这几样是我补的，你没有专门说过（{{ extraBuiltItems.length }}）
              </div>
              <ul class="col-list">
                <li v-for="n in extraBuiltItems" :key="'x-' + n.item">
                  <span class="k">{{ n.item }}</span>
                  <span class="d">{{ n.why }}</span>
                </li>
              </ul>
              <p class="col-note">不要的话：{{ extraBuiltItems[0].howToFix }}</p>
            </div>
            <p v-if="!notBuiltItems.length && !extraBuiltItems.length" class="col-note">
              这一类里你说过的东西都进到上面这份计划里了。
            </p>
          </div>

          <div class="col col-applied">
            <div class="col-head"><span class="col-index">02</span>我做了什么</div>
            <div class="col-num">
              写入 {{ writtenItems.length }} 条<span v-if="hasSkipList">，跳过 {{ skippedItems.length }} 条</span>
            </div>
            <ul class="col-list">
              <!-- ⛔ 回执清单整列都是编码, 显示前查表换成客户说的名字(humanizeKeys) -->
              <li v-for="w in writtenItems" :key="'w' + w">
                <el-tag size="small" type="success">写入</el-tag><span class="k">{{ human(w) }}</span>
              </li>
              <!-- ⛔ 跳过的必须带原因 —— 「跳过了 N 条」不构成交代 -->
              <li v-for="s in skippedItems" :key="'s' + s">
                <el-tag size="small" type="info">跳过</el-tag><span class="k">{{ human(s) }}</span>
              </li>
              <li v-if="!writtenItems.length && !skippedItems.length" class="none">
                这一类这次没有写入记录
              </li>
            </ul>
            <p v-if="!hasSkipList" class="col-note">
              这一类没有「跳过」这一说：它是整条替换，不存在跳过分支。
            </p>
          </div>

          <div class="col col-live">
            <div class="col-head"><span class="col-index">03</span>现在库里的</div>
            <div class="col-num">
              <span v-if="liveCount === null" class="unknown">查不到</span>
              <span v-else>{{ liveCount }} 条</span>
            </div>
            <p class="col-note">
              <template v-if="liveCount === null">
                ⚠️ 这一类的列表接口没读到（不是 0 条）。⛔ 别把「查不到」当成「一条都没有」。
              </template>
              <template v-else>
                这是这家工厂现在<b>全部</b>的 {{ current.title }}，包含这次建厂之前就有的。
              </template>
            </p>
          </div>
        </div>

        <!-- ============ 对账 ============ -->
        <el-alert v-if="reconcile.bad" type="warning" :closable="false" show-icon
                  class="block" :title="reconcile.title" :description="reconcile.detail" />
        <el-alert v-else type="success" :closable="false" show-icon class="block"
                  :title="reconcile.title" :description="reconcile.detail" />

        <!-- ============ 去修改 ============ -->
        <!--
          🔴 2026-08-17 真实浏览器实测: 用 platform_admin 复核 F999, 点「去修改」
          跳到 `/warehouse/material-types` —— 页面在, 路由对, 而**列表是「共 0 条记录」**。
          原因: 那些维护页读的是 `authStore.factoryId`(你自己那家厂), ⛔ 不读 query 里的
          factoryId。跳转档位(DIRECT/LIST_ONLY)判的是「读不读 route.query」, 判不到这一层。
          ⇒ 复核的厂 ≠ 你登录的厂时**当场说清楚**, ⛔ 不让客户跳到一个空列表上自己猜。
        -->
        <el-alert v-if="crossFactory" type="warning" :closable="false" show-icon class="block"
                  title="你登录的不是正在复核的这家工厂 —— 跳过去会是空的"
                  :description="crossFactoryNote" />
        <div class="jump">
          <template v-if="current.jumpKind === 'DIRECT'">
            <el-button type="primary" @click="jump(current)">去修改「{{ current.title }}」</el-button>
            <span class="jump-note">会直接打开这一类的维护页。</span>
          </template>
          <template v-else-if="current.jumpKind === 'LIST_ONLY'">
            <el-button type="primary" @click="jump(current)">打开「{{ current.title }}」列表</el-button>
            <!-- ⛔ 照实说: 这个页面不读 query, 定位不到那一条。假装跳准了比不跳更糟。 -->
            <span class="jump-note">
              这个页面不支持直接定位到某一条，跳过去之后请在列表里自己找上面列出的那几条。
            </span>
          </template>
          <template v-else>
            <el-button disabled>网页端改不了</el-button>
            <span class="jump-note">{{ current.jumpNote }}</span>
          </template>
        </div>

        <div class="acts">
          <!--
            ⛔ 「改完之后要不要重新对账」是设计卡 §6.3 登记的「没想清楚」之一。
            想清楚了: **必须能重新对**, 否则第三列永远停在他改之前那个数,
            而那是一份**假的对账** —— 他改对了也还显示对不上。
            ⚠️ 从维护页用浏览器返回时这一页会重新挂载、自动重读;
               这个按钮是给「在另一个标签页改完」的情况, 以及给他一个明确的抓手。
          -->
          <el-button :loading="!loaded" @click="load">我改完了，重新对一遍</el-button>
          <span class="acts-spacer" />
          <el-button :disabled="idx === 0" @click="idx--">上一类</el-button>
          <el-button v-if="idx < REVIEW_CLASSES.length - 1" type="primary" @click="idx++">
            下一类（{{ idx + 1 }}/{{ REVIEW_CLASSES.length }}）
          </el-button>
          <el-button v-else type="success" @click="finish">复核完了</el-button>
        </div>
      </el-card>
      </Transition>

      <div v-if="idx === REVIEW_CLASSES.length - 1" ref="acceptanceSection" class="acceptance-section">
        <el-alert
          v-if="blueprintLoadError"
          type="error"
          :closable="false"
          show-icon
          title="权威 Blueprint 状态读取失败，不能记录验收"
          :description="blueprintLoadError"
        />
        <FactoryBlueprintAcceptance
          v-else-if="canonicalBlueprint?.lifecycleState === 'APPLIED'"
          :blueprint="canonicalBlueprint"
          :loading="acceptanceLoading"
          @submit="submitCapabilityAcceptance"
        />
        <el-alert
          v-else
          type="warning"
          :closable="false"
          show-icon
          title="当前 Blueprint 尚未完整应用，不能记录人工验收"
          :description="`当前状态：${canonicalBlueprint?.lifecycleState ?? '未找到权威 Blueprint'}。配置应用成功与人工验收必须分开。`"
        />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/store/modules/auth'
import {
  getFactoryBuildReview,
  countLiveRows,
  countProductProcessRows,
  humanizeKeys,
  listFactories,
  getCurrentCanonicalFactoryBlueprint,
  acceptCanonicalFactoryBlueprintCapabilities,
  type FactoryBuildReview,
  type FactoryBrief,
  type PlannedChange,
  type ApplyAIInitResult,
  type CanonicalCapabilityAcceptanceDecision,
  type CanonicalFactoryBlueprint,
} from '@/api/factoryOnboardingApi'
import FactoryBlueprintAcceptance from './components/FactoryBlueprintAcceptance.vue'
import FactoryReadiness from './components/FactoryReadiness.vue'

/**
 * 一类主数据在复核里的全部坐标。
 *
 * ⛔ 三列都不是我在这里起的名字, 各有唯一来源, 由闸钉住:
 *   `group` / `writtenKey` ← `scripts/tests/preview-group-to-result.txt`
 *   `jumpKind` / `page`    ← `scripts/tests/stage3-jump-targets.txt`
 * 在这里另写一份就是形态 D(同一个东西两份, 一定会漂)。
 */
interface ReviewClass {
  key: string
  /** 客户话 —— 与两份声明里的类名逐字一致(闸按它对行) */
  title: string
  /** 这一屏在说什么 */
  what: string
  /** 预览分组名 */
  group: string
  /** apply 回执里「写入」的那个清单 */
  writtenKey: keyof ApplyAIInitResult
  /** 「跳过」的清单; 这一类没有跳过分支时为 null(⛔ 不许瞎填一个) */
  skippedKey: keyof ApplyAIInitResult | null
  /**
   * 现查库里行数的列表端点(挂在 /api/mobile/{factoryId}/ 下)。
   *
   * ⚠️ 产品工序链是空串: 它**没有**「列出全厂」的端点, 必须按产品逐个查,
   *    走 `countProductProcessRows`(见那个函数的注释)。
   */
  livePath: string
  jumpKind: 'DIRECT' | 'LIST_ONLY' | 'NO_WEB_PAGE'
  /** 跳去的路由; NO_WEB_PAGE 时为空 */
  jumpTo: string
  /** 与声明里的 page 一致(闸比这个); NO_WEB_PAGE 时为 '-' */
  page: string
  jumpNote?: string
}

const REVIEW_CLASSES: ReviewClass[] = [
  {
    key: 'productTypes',
    title: '产品类型',
    what: '你们要做的成品有哪些',
    group: '产品类型',
    writtenKey: 'writtenProductTypes',
    skippedKey: 'skippedProductTypes',
    livePath: 'product-types',
    jumpKind: 'NO_WEB_PAGE',
    jumpTo: '',
    page: '-',
    // ⛔ 这句是 owner 2026-08-17 拍板的做法(只读 + 明说去 App 改)。
    //    它**缩小了**目标② 承诺的「逐屏确认修改」, 是明确批准过的取舍, 不是省事。
    jumpNote: '网页端还没有产品类型的维护页，请到手机 App 的「产品类型管理」里改。',
  },
  {
    key: 'materialTypes',
    title: '原料类型',
    what: '你们要用的原料、辅料、包材',
    group: '原料类型',
    writtenKey: 'writtenMaterialTypes',
    skippedKey: 'skippedMaterialTypes',
    livePath: 'raw-material-types',
    jumpKind: 'DIRECT',
    jumpTo: '/warehouse/material-types',
    page: 'views/warehouse/material-types/list.vue',
  },
  {
    key: 'workProcesses',
    title: '工序',
    what: '一件产品要经过的那几道工序',
    group: '工序',
    writtenKey: 'writtenWorkProcesses',
    skippedKey: 'skippedWorkProcesses',
    livePath: 'work-processes',
    jumpKind: 'LIST_ONLY',
    jumpTo: '/system/work-processes',
    page: 'views/system/work-processes/index.vue',
  },
  {
    key: 'productProcesses',
    title: '产品工序链',
    what: '每个成品各自走哪几道、按什么顺序',
    group: '产品工序链',
    writtenKey: 'writtenProductProcesses',
    // ⛔ 后端**没有** skippedProductProcesses —— 那段代码的注释逐字写着
    //    「整条替换, 不存在'跳过'」。这里填 null, 界面照实说。
    skippedKey: null,
    // ⛔ 空串 —— 这一类走扇出查询, 见 `countProductProcessRows`
    livePath: '',
    jumpKind: 'DIRECT',
    jumpTo: '/system/product-processes',
    page: 'views/system/product-processes/index.vue',
  },
  {
    key: 'conversions',
    title: '转换率',
    what: '一份原料能出多少成品',
    group: '转换率',
    writtenKey: 'writtenConversions',
    skippedKey: 'skippedConversions',
    livePath: 'conversions',
    jumpKind: 'DIRECT',
    // 路由 /production/conversions redirect 到这里, 直接落到换算那个 tab
    jumpTo: '/production/bom?tab=conversion',
    page: 'views/production/bom-unified/index.vue',
  },
  {
    key: 'warehouses',
    title: '存放区',
    what: '原料/半成品/成品分别放在哪',
    group: '存放区',
    writtenKey: 'writtenWarehouses',
    skippedKey: 'skippedWarehouses',
    livePath: 'factory/warehouses',
    jumpKind: 'LIST_ONLY',
    jumpTo: '/inventory/by-warehouse',
    page: 'views/inventory/by-warehouse/index.vue',
  },
]

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
// currentRole normalizes a platform super_admin to platform_admin (store/modules/auth.ts).
const canSelectFactory = computed(() => authStore.hasRole('platform_admin'))

/**
 * 「你登录的厂」与「你在复核的厂」是不是同一家。
 *
 * ⛔ 不同 ⇒ 那六个维护页会显示**你自己那家厂**的数据(它们读 `authStore.factoryId`),
 *    跳过去是一张空列表。实测见 template 里那段注释。
 * ⚠️ 平台管理员的 `factoryId` 是空的 —— 同样属于「跳过去看不到这家厂」。
 */
const crossFactory = computed(() =>
  Boolean(factoryId.value) && authStore.factoryId !== factoryId.value)

const crossFactoryNote = computed(() =>
  `这些维护页只显示你当前登录账号所属工厂（${authStore.factoryId || '平台账号，没有归属工厂'}）的数据，`
  + `不会因为你在复核 ${factoryId.value} 就切过去。`
  + `要改 ${factoryId.value} 的内容，请用这家工厂自己的管理员账号登录后再进来。`)

const factories = ref<FactoryBrief[]>([])
const factoryId = ref(String(route.query.factoryId ?? ''))
const review = ref<FactoryBuildReview | null>(null)
const canonicalBlueprint = ref<CanonicalFactoryBlueprint | null>(null)
const blueprintLoadError = ref('')
const acceptanceLoading = ref(false)
const acceptanceSection = ref<HTMLElement | null>(null)
const acceptanceAttempt = ref<{ fingerprint: string; key: string } | null>(null)
const loaded = ref(false)
const loadError = ref('')
const idx = ref(0)
const liveCounts = ref<Record<string, number | null>>({})

const current = computed(() => REVIEW_CLASSES[idx.value])

const plannedItems = computed<PlannedChange[]>(() =>
  (review.value?.planned ?? []).filter((p) => p.group === current.value.group))

/**
 * 这一类里「客户说了而系统没配上」的那几条。
 *
 * ⛔ 它不是 `skipped*`（那是「载荷里有、落库跳过」）。这一份是
 * **载荷里根本没有它** —— 所以三份对账全都对得上，而客户说的东西少了两件。
 * ⚠️ `result` 为 null（老快照）时这里是空的 —— 那不是「没有没配上的」，
 *    是那次没存下来。上面 result=null 的那条提示已经说清了这件事。
 */
/**
 * ⚠️ 老快照里的条目**没有 kind** —— 一律按「说了没配上」处理。
 *    ⛔ 不许因为它是 null 就不显示：那会让历史建厂的交代凭空消失。
 */
const isExtra = (n: { kind?: string | null }) => n.kind === 'extra'

const thisGroup = computed(() =>
  (review.value?.result?.notBuilt ?? []).filter((n) => n.group === current.value.group))

const notBuiltItems = computed(() => thisGroup.value.filter((n) => !isExtra(n)))

/**
 * 🔴 反方向：配出来了、而客户没说过的那几条。
 *
 * 2026-08-18 实测（同一套答案连跑 8 遍）：客户报了 2 个成品的名字，有 2 遍配出 4 个。
 * 多出来的在预览里看得见，却没有一句话点出「这几个是我加的」——
 * 而复核这一页的全部意义就是「对不上的地方系统自己指出来」。
 */
const extraBuiltItems = computed(() => thisGroup.value.filter(isExtra))

const listOf = (key: keyof ApplyAIInitResult | null): string[] => {
  if (!key || !review.value?.result) return []
  const v = (review.value.result as Record<string, unknown>)[key as string]
  return Array.isArray(v) ? (v as string[]) : []
}

/**
 * 回执里的编码换成客户说过的名字 —— 查的是同一份快照里的 `planned`。
 * ⚠️ 查不到就原样显示, ⛔ 不留空(缺一半的话比看不懂的编号更糟)。
 */
function human(text: string): string {
  return humanizeKeys(text, review.value?.planned)
}

const writtenItems = computed(() => listOf(current.value.writtenKey))
const skippedItems = computed(() => listOf(current.value.skippedKey))
const hasSkipList = computed(() => current.value.skippedKey !== null)

/**
 * 这一类的「跳过」条目**带不带原因**。
 *
 * ⛔ 由实际值判, 不写死: 16 类回执里只有个别几类(关键控制点/登录账号)的
 *    skipped 是「键(原因)」的形状, 其余是**光秃秃的键**。
 *    `preview-group-to-result.txt` 里那段「📌 顺带记一个结构性的事实」写得很清楚:
 *    `List<String>` 放不下「键 + 原因」两件事。
 * ⚠️ 这是**代理判据**(看有没有括号), 键本身含括号会判错。
 *    它可接受是因为判错的方向只影响一句话的措辞, 不影响任何数。
 */
const skippedHaveReasons = computed(() =>
  skippedItems.value.some((s) => /[（(]/.test(s)))
const liveCount = computed(() => liveCounts.value[current.value.key] ?? null)

/**
 * 对账 —— 「你说要什么」的条数应当等于「写入 + 跳过」。
 *
 * ⛔ 对不上就说出来, 并且说清差在哪一边。⚠️ 它**不比**「现在库里的」那一列:
 *    库里还有这次建厂之前就存在的行, 数目本来就该更大。
 */
const reconcile = computed(() => {
  const said = plannedItems.value.length
  const did = writtenItems.value.length + skippedItems.value.length
  if (!review.value?.result) {
    return {
      bad: true,
      title: '这次没有留下「我做了什么」，对不了账',
      detail: '只能看到当时说要什么。⛔ 这不等于系统什么都没做。',
    }
  }
  if (said === did) {
    return {
      bad: false,
      title: `对得上：预览说要 ${said} 条，实际处理 ${did} 条`,
      // 🔴 「原因写在上面那一列里」这句我写过一版, 而**真实浏览器里那几条跳过
      //    只有光秃秃的键、一个原因都没有** —— 那是一句 UI 在替数据吹牛。
      //    回执契约(List<String>)放不下「键 + 原因」两件事, 只有个别类带括号后缀。
      //    ⇒ 由实际值判, ⛔ 不写死结论。
      detail: skippedItems.value.length
        ? (skippedHaveReasons.value
          ? `其中 ${skippedItems.value.length} 条跳过了，原因写在上面那一列的括号里。`
          : `其中 ${skippedItems.value.length} 条跳过了 —— ⚠️ 系统只记下了是哪几条，`
            + `没记为什么。最常见的原因是「库里已经有同名的，不重复建」，`
            + `但这一条要你自己去那一类的页面上核对。`)
        : '全部写进去了。',
    }
  }
  return {
    bad: true,
    title: `对不上：说要 ${said} 条，实际处理 ${did} 条（差 ${Math.abs(said - did)} 条）`,
    detail: said > did
      ? '有几条当时说了，而回执里既没说写入、也没说跳过 —— 这是要查的缺口。'
      : '回执里出现了预览没说过的条目 —— 也就是客户没被事先告知的改动。',
  }
})

async function load() {
  if (!factoryId.value) return
  loaded.value = false
  loadError.value = ''
  review.value = null
  canonicalBlueprint.value = null
  blueprintLoadError.value = ''
  liveCounts.value = {}
  try {
    const r = await getFactoryBuildReview(factoryId.value)
    review.value = r.data
    loaded.value = true
  } catch (e) {
    // ⛔ 原样显示后端 message, 不自己拼一个 fallback 文案(防呆 4 位一体 b)
    loadError.value = (e as { message?: string })?.message
      || '读不到这家工厂的建厂复核记录'
    loaded.value = true
    return
  }
  await loadCanonicalBlueprint()
  // 第三份逐类现查 —— 任一类失败只把那一类标成「查不到」, ⛔ 不整页失败
  await Promise.all(REVIEW_CLASSES.map(async (c) => {
    // ⛔ 产品工序链没有「列出全厂」的端点(后端 @RequestParam productTypeId 必填),
    //    走扇出。⚠️ 这是真实浏览器里点出来的, 不是看代码推的。
    const n = c.livePath
      ? await countLiveRows(factoryId.value, c.livePath)
      : await countProductProcessRows(factoryId.value)
    liveCounts.value = { ...liveCounts.value, [c.key]: n }
  }))
}

async function loadCanonicalBlueprint() {
  try {
    const response = await getCurrentCanonicalFactoryBlueprint(factoryId.value)
    canonicalBlueprint.value = response.data
    blueprintLoadError.value = ''
  } catch (error) {
    canonicalBlueprint.value = null
    blueprintLoadError.value = (error as { message?: string })?.message
      || '读不到这家工厂的权威 Blueprint 状态'
  }
}

function jump(c: ReviewClass) {
  if (!c.jumpTo) return
  router.push(c.jumpTo)
}

async function finish() {
  const blueprint = canonicalBlueprint.value
  if (!blueprint || blueprint.lifecycleState !== 'APPLIED') {
    ElMessage.warning('逐屏复核已到最后一类，但当前权威 Blueprint 尚不可验收')
    await nextTick()
    acceptanceSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    return
  }
  if (blueprint.acceptanceState !== 'ACCEPTED') {
    ElMessage.warning(blueprint.acceptanceState === 'REJECTED'
      ? '存在需要调整的能力，请修正来源配置并创建新的 Blueprint 版本'
      : '请在下方逐能力记录人工验收决定')
    await nextTick()
    acceptanceSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    return
  }
  ElMessage.success('逐屏复核与能力验收均已完成；Workflow 仍需单独仿真、核对和发布')
  router.push({ path: '/factory-onboarding' })
}

async function submitCapabilityAcceptance(decisions: CanonicalCapabilityAcceptanceDecision[]) {
  const blueprint = canonicalBlueprint.value
  if (!blueprint || blueprint.lifecycleState !== 'APPLIED' || !decisions.length) return
  const fingerprint = JSON.stringify([...decisions].sort((a, b) => a.capabilityId.localeCompare(b.capabilityId)))
  if (acceptanceAttempt.value?.fingerprint !== fingerprint) {
    acceptanceAttempt.value = {
      fingerprint,
      key: `factory-blueprint-accept-${blueprint.id}-${blueprint.rowVersion}-${Date.now()}`,
    }
  }
  const attempt = acceptanceAttempt.value
  if (!attempt) return
  const accepted = decisions.filter((item) => item.decision === 'ACCEPTED').length
  const rejected = decisions.length - accepted
  try {
    await ElMessageBox.confirm(
      `本次记录 ${decisions.length} 项：验收通过 ${accepted} 项，需要调整 ${rejected} 项。只追加验收回执，不重写配置、不发布 Workflow。`,
      `提交能力验收 — ${blueprint.factoryName}（${blueprint.factoryId}）`,
      { type: rejected ? 'warning' : 'info', confirmButtonText: '确认提交回执', cancelButtonText: '继续核对' },
    )
  } catch {
    return
  }
  acceptanceLoading.value = true
  try {
    const response = await acceptCanonicalFactoryBlueprintCapabilities(
      factoryId.value,
      blueprint.id,
      {
        idempotencyKey: attempt.key,
        expectedRowVersion: blueprint.rowVersion,
        decisions,
      },
    )
    canonicalBlueprint.value = response.data
    acceptanceAttempt.value = null
    ElMessage.success(rejected
      ? '验收回执已记录；需调整项不会被伪装成通过'
      : '验收回执已记录；业务配置写入为 0，Workflow 仍未发布')
  } catch (error) {
    ElMessage.error((error as { message?: string })?.message || '能力验收回执没有提交成功')
    await loadCanonicalBlueprint()
  } finally {
    acceptanceLoading.value = false
  }
}

watch(() => route.query.factoryId, (v) => {
  if (v && String(v) !== factoryId.value) {
    factoryId.value = String(v)
    load()
  }
})

onMounted(async () => {
  if (canSelectFactory.value) {
    try {
      factories.value = await listFactories()
    } catch {
      factories.value = []
    }
  } else if (!factoryId.value && authStore.factoryId) {
    factoryId.value = authStore.factoryId
  }
  if (factoryId.value) await load()
})
</script>

<style scoped>
.review-page {
  --review-ease-out: cubic-bezier(0.23, 1, 0.32, 1);
  padding: 20px;
  background: #f4f6f9;
}
.head-card,
.content-card { border: 1px solid #edf2f7; border-radius: 10px; box-shadow: 0 2px 12px rgba(27, 101, 168, .06); }
.head-card { overflow: hidden; }
.head-card :deep(.el-card__body) { padding: 20px 22px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.head > div:first-child { min-width: 0; }
.head-right { flex: none; }
.factory-select { width: 260px; }
.factory-context { display: flex; min-width: 220px; flex-direction: column; align-items: flex-end; gap: 4px; }
.factory-context span { color: #303133; font-size: 13px; font-weight: 600; }
.factory-context code { color: #7a8599; font-size: 11px; }
.title { margin: 0 0 6px; color: #1a2332; font-size: 22px; letter-spacing: -.02em; }
.sub { max-width: 760px; margin: 0; color: #66758a; font-size: 13px; line-height: 1.7; }
.block { margin: 12px 0; }
.steps { margin: 18px 6px; }
.content-card { margin-top: 8px; overflow: hidden; }
.acceptance-section { scroll-margin-top: 16px; }
.content-card :deep(.el-card__body) { padding: 20px; }
.cls-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 16px; }
.cls-title { margin: 0; color: #1a2332; font-size: 18px; letter-spacing: -.01em; }
.cls-hint { color: #888; font-size: 13px; }
.three { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
@media (max-width: 900px) {
  .three { grid-template-columns: 1fr; }
  /* 逐屏复核是连续任务，浮动客服会压住第三列和对账结论。 */
  :global(.inline-cs__bubble) { display: none !important; }
}
.col { min-width: 0; border: 1px solid #e1e7ee; border-radius: 10px; padding: 14px; background: #fbfcfd; }
.col-planned { border-color: #c8dff1; background: #f5faff; }
.col-applied { border-color: #c5e2d8; background: #f4fbf8; }
.col-live { border-color: #dde3ea; background: #fafbfc; }
.col-head { display: flex; align-items: center; gap: 8px; color: #334155; font-size: 13px; font-weight: 700; }
.col-index { display: inline-grid; width: 25px; height: 25px; place-items: center; border-radius: 7px; background: #e7edf3; color: #607086; font-size: 10px; font-variant-numeric: tabular-nums; }
.col-planned .col-index { background: #dcecf8; color: #1b65a8; }
.col-applied .col-index { background: #dff2ea; color: #21776a; }
.col-num { margin: 9px 0 10px 33px; color: #1a2332; font-size: 20px; font-weight: 700; font-variant-numeric: tabular-nums; }
.col-num .unknown { color: #e6a23c; font-size: 15px; }
.col-list { list-style: none; margin: 0; padding: 0; max-height: 260px; overflow: auto; }
.col-list li { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 6px 0; border-bottom: 1px dashed rgba(122, 133, 153, .18); font-size: 13px; }
.col-list .k { min-width: 0; color: #334155; overflow-wrap: anywhere; }
.col-list .d { color: #888; }
.col-list .none { color: #aaa; }
.col-note { margin: 8px 0 0; font-size: 12px; color: #888; line-height: 1.6; }
.notbuilt { margin-top: 10px; padding: 10px 12px; border-radius: 8px;
            background: #fdf6ec; border: 1px solid #f5dab1; }
.notbuilt-head { font-size: 13px; font-weight: 600; color: #b88230; margin-bottom: 4px; }
.jump { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 8px; padding-top: 2px; }
.jump-note { color: #888; font-size: 12px; }
.acts { margin-top: 18px; padding-top: 16px; border-top: 1px solid #edf2f7; display: flex; gap: 12px; align-items: center; }
.acts-spacer { flex: 1; }
.review-panel-enter-active,
.review-panel-leave-active { transition: opacity 180ms var(--review-ease-out), transform 180ms var(--review-ease-out); }
.review-panel-enter-from { opacity: 0; transform: translateX(8px); }
.review-panel-leave-to { opacity: 0; transform: translateX(-6px); }
.review-page :deep(.el-button):focus-visible,
.review-page :deep(.el-select__wrapper):focus-visible { outline: 2px solid #1b65a8; outline-offset: 2px; }
@media (max-width: 560px) {
  .review-page { padding: 12px; }
  .head-card :deep(.el-card__body) { padding: 16px; }
  .head, .cls-head { align-items: stretch; flex-direction: column; }
  .head-right, .factory-select, .factory-context { width: 100%; min-width: 0; }
  .factory-context { align-items: flex-start; }
  .steps :deep(.el-step__title) { display: none; }
  .content-card :deep(.el-card__body) { padding: 14px; }
  .col { min-width: 0; padding: 10px; }
  .col-num { margin-left: 33px; }
  .col-list .k, .col-list .d { min-width: 0; overflow-wrap: anywhere; }
  .acts { align-items: stretch; flex-direction: column; }
  .acts-spacer { display: none; }
  .acts .el-button { width: 100%; margin-left: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .review-panel-enter-active,
  .review-panel-leave-active { transition: opacity 120ms ease-out; }
  .review-panel-enter-from,
  .review-panel-leave-to { transform: none; }
}
</style>
