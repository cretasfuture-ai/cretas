<!--
  对话建厂 —— 平台管理员/工厂超管 用一段对话把工厂配起来。

  三步, 刻意分开:
    1 对话   多轮问齐 —— 题目**由服务端定义**, 数量会随回答变化
             (答「4 道工序」就多出 4×8 项; 答「2 个成品」再多出 2×4 项)
             ⛔ 前端不存题目表: 曾经写死 5 个 key, 其中 3 个后端早就不问了,
                界面一直在展示不存在的题而没有任何一层报错
    2 预览   自然语言 → 结构化配置; **显示「现值 → 确认后的值」**
    3 落库   人工确认后才写库

  ⛔ 2 与 3 不得合成一个按钮。合了就等于取消预览这道防呆 —— AI 生成的配置
     必须先给人看过、可改, 才允许写进工厂。

  防呆规范 (.claude/rules/fool-proof-design.md) 落地位置:
    Rule 1 预先显示边界 → 差异面板逐项标 [新建]/[覆盖]/[跳过], 现值从既有接口读
    Rule 2 上下文带身份 → 页头与确认弹窗都带 工厂名 + 工厂ID
    Rule 3 约束选择     → 覆盖行为用 el-switch 二选一, 不让用户填自由文本决定
    Rule 4 幂等         → 后端按 entityType/code 幂等; 默认不覆盖, 已存在的如实标"跳过"
    Rule 5 空态给去路   → 未选工厂/会话过期 都给出下一步按钮, 不留死路
    四位一体            → 错误原样显示后端 message(sticky 由 request.ts 统一处理)

  ── 给下一个来验这个页面的人 ──────────────────────────────────────────
  2026-08-11 浏览器实测走通(本地 vite 5199 + 本地 API 10010):
    打字答「做卤味熟食」→ 还剩 7→6 项
    → 行业归类这一题**直接出 7 个按钮**(不再让客户打字后交给模型猜)
    → 点「卤味熟食」→ 右侧出现平台推荐的 6 道可编辑工序骨架
    → 下一题「工序道数」带出建议「6」和「就按这个」按钮

  ⚠️ 这一轮又栽了一个只有真点浏览器才照得出来的: 我在 script setup 里写 JSDoc
     漏了 `*/`, 后面所有 const 声明全被吞进注释 → 模板里的绑定退化成 _ctx 查找 →
     **整页白屏**。而 `vue-tsc --noEmit` 0 错、`vite build` 0 错、产物也照常生成。
     ⛔ 判据: 前端改完, type-check 与 build 全绿**不构成**页面能打开。

  ⚠️ 验证过程中我误诊过一次, 判据记在这里免得重演:

  ⚠️ 验证过程中我误诊过一次, 判据记在这里免得重演:

    现象: 点发送没反应, 换元素引用点、换回车, 三条路都不触发请求, 控制台也不报错。
    我的错误推论: 「send() 没绑上」。
    真因: 自动化的 type 有时不落进 el-input 的 textarea → draft 为空
          → 按钮 :disabled 为 true → 点击本来就该无效。

    ⛔ 判据: **「点了没反应」要先确认按钮当时是不是启用的。**
       「输入没进去」与「事件没绑上」的现象一模一样, 不先分清就会一路查错方向。
       分辨方法: 放大看按钮颜色 —— Element Plus 禁用态 #a0cfff / 启用态 #409eff,
       在整屏截图里这两种蓝看不出差别。
-->
<template>
  <div class="page-container">
    <!-- Rule 2: 上下文必带身份 -->
    <div class="page-header">
      <div>
        <h2>工厂装配台</h2>
        <p class="sub">
          先向 Agent 讲一个代表性产品怎么生产；Agent 会在对话里穿插选择题和小表格，再把这个样例推演成整厂流程。<b>你不是在逐表配置系统。</b>
        </p>
      </div>
      <div class="ctx" v-if="factoryId">
        <span class="ctx-name">{{ factoryName || '未命名工厂' }}</span>
        <code class="ctx-id">{{ factoryId }}</code>
      </div>
    </div>

    <FactoryInterviewArchive v-if="progress && factoryId" :progress="progress" />

    <!-- Rule 5: 没有工厂时不是死路 —— 页面自带选择器。
         ⚠️ web-admin 目前没有工厂列表页, 若这里只写「去列表选择」就会跳到 404,
            那是把死路换了个地方, 不是解决。 -->
    <el-card v-if="!factoryId" shadow="never" class="content-card">
      <div class="picker">
        <div class="picker-title">先选一个要配置的工厂</div>
        <el-select
          v-model="pickedId"
          filterable
          :loading="factoriesLoading"
          placeholder="搜索工厂名称或编号"
          class="picker-select"
        >
          <el-option
            v-for="f in factories"
            :key="f.id"
            :label="`${f.name}（${f.id}）`"
            :value="f.id"
          />
        </el-select>
        <el-button type="primary" :disabled="!pickedId" @click="chooseFactory">开始</el-button>
      </div>
      <el-empty v-if="!factoriesLoading && factories.length === 0"
                description="平台下还没有工厂，需要先创建工厂再来配置" />
    </el-card>

    <template v-else>
      <el-steps :active="step" align-center finish-status="success" class="steps" :class="{ 'steps--conversation': step === 0 }">
        <el-step title="对话理解样例" :description="stepOneDesc" />
        <el-step title="推演整厂流程" description="看图确认 AI 理解" />
        <el-step title="写入与复核" description="写入后逐屏验收" />
      </el-steps>

      <div v-if="operationStage" class="operation-stage" role="status" aria-live="polite">
        <span class="operation-spinner" aria-hidden="true" />
        <div>
          <strong>{{ operationStage.title }}</strong>
          <p>{{ operationStage.detail }}</p>
        </div>
        <el-tag :type="operationStage.writes ? 'warning' : 'info'" effect="plain">
          {{ operationStage.writes ? '正在写入 · 请勿刷新' : '尚未写入' }}
        </el-tag>
      </div>

      <div v-if="flowIssue" class="flow-issue" role="alert">
        <div>
          <strong>{{ flowIssue.title }}</strong>
          <p>{{ flowIssue.detail }}</p>
          <small>{{ flowIssue.sideEffect }}</small>
        </div>
        <el-button type="danger" plain :loading="flowRetrying" @click="retryFlowIssue">
          {{ flowIssue.retryLabel }}
        </el-button>
      </div>

      <!-- ============ 步骤 1: 对话 ============ -->
      <div v-show="step === 0" class="stage-strip" aria-label="Agent 工厂装配阶段">
        <div class="stage-chip" :class="{ active: questionnaire.length > 0 }">
          <span>01</span><div><strong>讲一个代表产品</strong><small>用日常语言说明怎么生产</small></div>
        </div>
        <div class="stage-line" />
        <div class="stage-chip active">
          <span>02</span><div><strong>对话中补信息</strong><small>选择题和小表格随问随答</small></div>
        </div>
        <div class="stage-line" />
        <div class="stage-chip" :class="{ active: reconciliationIssues.length > 0 || progress?.readyForGenerate }">
          <span>03</span><div><strong>推演整厂并对账</strong><small>推断和矛盾先确认再生成</small></div>
        </div>
      </div>

      <div v-show="step === 0" class="split">
        <el-card shadow="never" class="content-card talk-card">
          <div class="conversation-context" aria-label="本轮对话范围">
            <div>
              <span class="context-kicker">本轮任务</span>
              <strong>用一个代表性产品，推演 {{ factoryName || factoryId }} 的整厂生产流程</strong>
            </div>
            <el-tag type="info" effect="plain">对话阶段不会写入</el-tag>
          </div>
          <section v-if="hasSeparateSavedCanonical" class="saved-canonical-callout" aria-labelledby="saved-canonical-title">
            <div>
              <span class="structured-kicker">已保存的 Factory Blueprint</span>
              <strong id="saved-canonical-title">当前访谈之外，还有一份已保存蓝图</strong>
              <p>先回读蓝图来源并校验绑定的服务端访谈身份，再开放编辑；当前访谈答案不会挂到另一份蓝图上。</p>
            </div>
            <el-button type="primary" plain :loading="canonicalViewLoading" @click="viewSavedCanonical">
              查看已保存的蓝图
            </el-button>
          </section>
          <div ref="logRef" class="log">
            <div v-for="(m, i) in messages" :key="i" class="turn" :class="m.role">
              <div class="av">{{ m.role === 'ai' ? 'AI' : '我' }}</div>
              <div v-if="m.text" class="bubble">{{ m.text }}</div>
              <FactoryConversationReceipt v-else-if="m.receipt" :receipt="m.receipt" />
            </div>
            <div v-if="suggestedPrefillCount" class="turn ai structured-turn suggestion-turn">
              <div class="av">AI</div>
              <div class="suggestion-prefill" role="status" aria-live="polite">
                <div>
                  <span class="structured-kicker">Agent 已准备一组平台建议</span>
                  <strong>已准备 {{ suggestedPrefillCount }} 项可编辑建议</strong>
                  <small>这些内容尚未保存。可以逐项修改或清空，确认无误后再按组交给 Agent。</small>
                </div>
                <el-tag type="warning" effect="plain">待你确认</el-tag>
              </div>
            </div>
            <div v-if="hasUserExample && processRows.length" class="turn ai structured-turn">
              <div class="av">AI</div>
              <FactoryConversationProcessTable
                :rows="processRows"
                :suggestions="progress?.suggestions ?? {}"
                :collected="progress?.collected ?? {}"
                :sending="sending"
                :confirmed="processTableConfirmed"
                @update-row="updateProcessRow"
                @submit="submitProcessTable"
              />
            </div>
            <div v-if="activeProcessDetails" class="turn ai structured-turn">
              <div class="av">AI</div>
              <FactoryConversationProcessDetails
                :group="activeProcessDetails"
                :answers="processDetailAnswers"
                :suggestions="progress?.suggestions ?? {}"
                :sending="sending"
                @update-answer="setProcessDetailAnswer"
                @submit="submitProcessDetails"
              />
            </div>
            <div v-if="hasUserExample && processTableConfirmed && !activeProcessDetails && productRows.length" class="turn ai structured-turn">
              <div class="av">AI</div>
              <FactoryConversationProductTable
                :rows="productRows"
                :packaging="productPackaging"
                :packaging-hint="activeProductPackagingItem?.hint"
                :suggestions="progress?.suggestions ?? {}"
                :collected="progress?.collected ?? {}"
                :items="progress?.pendingItems ?? []"
                :sending="sending"
                :confirmed="productTableConfirmed"
                @update-row="updateProductRow"
                @update-packaging="updateProductPackaging"
                @submit="submitProductTable"
              />
            </div>
            <div v-if="productTableConfirmed && (progress?.routePreviews?.length ?? 0) > 0" class="turn ai structured-turn">
              <div class="av">AI</div>
              <FactoryConversationRoutePreview :previews="progress?.routePreviews ?? []" />
            </div>
            <div v-if="routeTopologyDraft" class="turn ai structured-turn">
              <div class="av">AI</div>
              <FactoryConversationTopologyCard
                :draft="routeTopologyDraft"
                :sending="sending"
                @change="updateRouteTopologyDraft"
                @submit="submitRouteTopology"
              />
            </div>
            <div v-if="activeDomainConfig" class="turn ai structured-turn">
              <div class="av">AI</div>
              <FactoryConversationDomainCard
                :group="activeDomainConfig"
                :answers="domainAnswers"
                :suggestions="progress?.suggestions ?? {}"
                :sending="sending"
                @update-answer="setDomainAnswer"
                @submit="submitDomainCard"
              />
            </div>
            <div v-if="hasUserExample && !productTableActive && !activeDomainConfig && (questionnaire.length || alertRows.length || metricRows.length)" class="turn ai structured-turn">
              <div class="av">AI</div>
              <section class="structured-bubble" aria-labelledby="structured-question-title">
                <div class="structured-head">
                  <div>
                    <span class="structured-kicker">对话中的信息卡</span>
                    <h3 id="structured-question-title">我从你的样例里提炼了几项需要确认的信息</h3>
                    <p>只确认样例没说清的地方；不确定的可以留空，我会继续追问，不需要你去填写整套配置表。</p>
                  </div>
                  <el-tag v-if="questionnaire.length" type="info" effect="plain">已答 {{ questionnaireAnswerCount }}/{{ questionnaire.length }}</el-tag>
                </div>
                <div v-if="questionnaire.length" class="questionnaire-grid">
                  <template v-for="item in questionnaire" :key="item.name">
                  <div v-if="item.name === 'stepCount'" class="question-field" role="group" aria-label="工序道数">
                    <span>{{ item.label }}</span>
                    <small v-if="item.hint">{{ item.hint }}</small>
                    <el-tag v-if="questionnaireSuggestionMatches(item)" size="small" type="warning" effect="plain">参考值待确认</el-tag>
                    <div class="count-quick-options">
                      <el-button
                        v-for="option in (item.options ?? []).filter(option => option !== STEP_COUNT_MORE_OPTION)"
                        :key="option"
                        size="small"
                        :type="questionnaireAnswers[item.name] === option ? 'primary' : 'default'"
                        @click="setQuestionnaireAnswer(item, option)"
                      >{{ option }}</el-button>
                      <el-button size="small" :type="stepCountMoreOpen ? 'primary' : 'default'" @click="openStepCountMore">
                        更多（1-30）
                      </el-button>
                    </div>
                    <div v-if="stepCountMoreOpen" class="step-count-more">
                      <el-input-number
                        v-model="stepCountMoreValue"
                        :min="1"
                        :max="30"
                        :step="1"
                        step-strictly
                        controls-position="right"
                        aria-label="准确工序数，1到30"
                      />
                      <el-button type="primary" plain :disabled="!stepCountMoreValue" @click="useStepCountMoreInQuestionnaire(item)">
                        加入信息卡
                      </el-button>
                    </div>
                  </div>
                  <label v-else class="question-field">
                    <span>{{ item.label }}</span>
                    <small v-if="item.hint">{{ item.hint }}</small>
                    <el-tag v-if="questionnaireSuggestionMatches(item)" size="small" type="warning" effect="plain">参考值待确认</el-tag>
                    <el-select :model-value="questionnaireAnswers[item.name]" :multiple="item.multiSelect" clearable collapse-tags placeholder="请选择" @update:model-value="setQuestionnaireAnswer(item, $event)">
                      <el-option v-for="option in item.options ?? []" :key="option" :label="option" :value="option" />
                    </el-select>
                  </label>
                  </template>
                </div>
                <details v-if="alertRows.length" class="preference-disclosure">
                  <summary>
                    <span><strong>这个工厂需要哪些提醒？</strong><small>{{ alertsNote }}</small></span>
                    <el-tag size="small" type="info" effect="plain">按需展开</el-tag>
                  </summary>
                  <div class="alerts-block inline-preference-block">
                    <div v-for="r in alertRows" :key="r.alertType" class="alert-row">
                    <el-checkbox v-model="r.checked" :disabled="r.alreadyConfigured" class="alert-check">{{ r.label }}</el-checkbox>
                    <span class="alert-detail">{{ r.detail }}</span>
                    <el-tag v-if="r.alreadyConfigured" size="small" type="info">本厂已配</el-tag>
                    <template v-else-if="r.needsAmount && r.checked">
                      <el-input-number v-model="r.amountThreshold" :min="1" :step="1000" :controls="false" size="small" placeholder="金额" class="alert-amount" />
                      <span class="alert-unit">元以上提醒</span><span v-if="!r.amountThreshold" class="alert-warn">需要一个明确金额</span>
                    </template>
                    </div>
                  </div>
                </details>
                <details v-if="metricRows.length" class="preference-disclosure">
                  <summary>
                    <span><strong>平时最想盯哪些生产指标？</strong><small>{{ metricsNote }}</small></span>
                    <el-tag size="small" type="info" effect="plain">按需展开</el-tag>
                  </summary>
                  <div class="alerts-block inline-preference-block">
                    <div v-for="r in metricRows" :key="r.metricName" class="alert-row">
                    <el-checkbox v-model="r.checked" :disabled="r.alreadyConfigured" class="alert-check">{{ r.label }}</el-checkbox>
                    <span class="alert-detail">{{ r.detail }}</span>
                    <el-tag v-if="r.alreadyConfigured" size="small" type="info">本厂已配</el-tag>
                    <template v-else-if="r.checked"><span class="alert-unit">{{ r.direction }}</span><el-input-number v-model="r.threshold" :min="0" :controls="false" size="small" placeholder="阈值" class="alert-amount" /><span class="alert-unit">{{ r.unit }}就提醒</span></template>
                    </div>
                  </div>
                </details>
                <div v-if="questionnaireCanSubmit" class="questionnaire-actions">
                  <span>这些答案会作为这段对话的一部分交给 Agent。</span>
                  <el-button type="primary" :disabled="questionnaireAnswerCount === 0" :loading="sending" @click="submitQuestionnaire">发送这张信息卡</el-button>
                </div>
              </section>
            </div>
            <div v-if="sending" class="turn ai">
              <div class="av">AI</div>
              <div class="bubble thinking">正在理解这个样例，并判断还需要追问什么…</div>
            </div>
          </div>
          <div v-if="sessionId" class="active-question-turn">
            <div class="av">AI</div>
            <div class="active-question-body">
          <FactoryConversationDecisionPacket
            v-if="currentDecision && !currentDecisionInStructuredCard"
            :packet="currentDecision"
            :selected-values="pickedOptions"
            :sending="sending"
            @select="pickOption"
            @use-example="useDecisionExample"
            @use-recommendation="useDecisionRecommendation"
            @confirm-multi="confirmMultiDecision"
            @defer="deferCurrentDecision"
          />
          <!-- 当前这一题 —— 题型/选项/提示/建议全部来自服务端 pendingItems。
               ⛔ 前端不再自己维护题目表: 此前写死 5 个 key, 其中 3 个后端早就不问了。 -->
          <div v-if="currentItem && !currentItemInStructuredCard && !currentDecision" class="asking">
            <div class="asking-head">
              <span class="asking-label">{{ currentItem.label }}</span>
              <el-tag v-if="currentItem.repeatIndex" size="small" type="info">
                第 {{ currentItem.repeatIndex }} 项
              </el-tag>
              <el-tag v-if="currentItem.allowNone" size="small">没有也行</el-tag>
            </div>
            <div v-if="currentItem.hint" class="asking-hint">{{ currentItem.hint }}</div>

            <!-- 平台建议：只按当前工厂确认；点了才算答，不点不写。 -->
            <div v-if="currentItem.suggestion" class="suggest">
              <div class="suggest-text">
                <span class="suggest-tag">建议</span>{{ currentItem.suggestion }}
              </div>
              <el-button size="small" :loading="sending" @click="adopt(currentItem)">
                就按这个
              </el-button>
            </div>
          </div>

          <!-- 枚举题: 直接给按钮, ⛔ 不让客户打字后再让模型去猜他选了哪个 -->
          <div v-if="isChoice && !currentItemInStructuredCard && !currentDecision" class="options">
            <el-button
              v-for="opt in currentItem?.options ?? []"
              :key="opt"
              :type="pickedOptions.includes(opt) ? 'primary' : 'default'"
              :loading="sending"
              @click="pickOption(opt)"
            >
              {{ opt }}
            </el-button>
            <el-button
              v-if="currentItem?.multiSelect && pickedOptions.length"
              type="primary"
              :loading="sending"
              @click="submitPicked"
            >
              确定（选了 {{ pickedOptions.length }} 项）
            </el-button>
          </div>

          <div v-if="stepCountMoreOpen && currentItem?.name === 'stepCount' && !currentItemInStructuredCard" class="step-count-more direct-count-more" role="group" aria-label="填写准确工序数">
            <el-input-number
              v-model="stepCountMoreValue"
              :min="1"
              :max="30"
              :step="1"
              step-strictly
              controls-position="right"
              aria-label="准确工序数，1到30"
            />
            <el-button type="primary" :loading="sending" :disabled="!stepCountMoreValue" @click="submitStepCountMore">
              保存并继续
            </el-button>
          </div>

          <div v-if="currentItemInStructuredCard" class="questionnaire-handoff">
            <strong>{{ currentItemInProcessDetails
              ? '当前这题已经放在上方工序细节卡里'
              : (currentItemInProcessTable
                ? '当前这题已经放在上方流程表里'
                : (currentItemInProductTable
                  ? '当前这题已经放在上方成品与工序链表里'
                  : (currentItemInRouteTopology
                    ? '当前这题已经放在上方路线拓扑确认卡里'
                    : (currentItemInDomainConfig ? '当前这题已经放在上方专项配置卡里' : '当前这题已经放在上方对话信息卡里')))) }}</strong>
            <span>可以和同一组信息一起回复 Agent；组件不好表达时，再用下面一句话补充例外。</span>
          </div>

          <!--
            🔴 2026-08-18 在**真实浏览器**里走完一遍才发现的:
            枚举题此前**只有按钮、没有输入框** —— 而「存放区」那道题的提示语写着
            「仓名跟下面这几个不一样时，请在名字后面用括号带一句它属于哪类，
            像『原料冷库（原料）』这样说」。**客户在网页上根本打不了这句话。**
            (API 探针能打, 所以前几轮全程没暴露 —— 探针不是界面。)
            ⇒ 选项照旧是主入口, 下面再给一行「都不是就自己说」。
            ⛔ 这不是回到「让客户打字再让模型猜他选了哪个」: 逐字命中选项的那条路
               在服务端, 打的字正好等于某个选项时照样走确定性匹配。
          -->
          <div v-if="isChoice" class="composer choice-composer">
            <span class="choice-composer-tip">
              {{ currentItemInStructuredCard ? '组件里不好表达？直接补充：' : '都不是？直接说也行：' }}
            </span>
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              :maxlength="10000"
              :disabled="sending || !sessionId"
              placeholder="比如「原料冷库（原料）、车间暂存（暂存）」"
              @keydown.enter.exact.prevent="send"
            />
            <el-button
              type="primary"
              :loading="sending"
              :disabled="!draft.trim() || !sessionId"
              @click="send"
            >
              发送
            </el-button>
          </div>

          <!-- 自由题: 打字 -->
          <div v-else class="composer">
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              :maxlength="10000"
              :disabled="sending || !sessionId"
              :placeholder="currentItemInProcessDetails ? '细节卡里不好表达？只补充这个样例的例外' : (currentItem ? '像跟厂长介绍一样回答就行' : '还有补充或例外？继续告诉 Agent')"
              @keydown.enter.exact.prevent="send"
            />
            <el-button
              type="primary"
              :loading="sending"
              :disabled="!draft.trim() || !sessionId"
              @click="send"
            >
              发送
            </el-button>
          </div>
            </div>
          </div>
        </el-card>

        <el-card shadow="never" class="content-card">
          <template #header>
            <div class="card-head">
              <span>还差什么</span>
              <el-tag v-if="progress" :type="progress.remaining === 0 ? 'success' : 'info'" size="small">
                还剩 {{ progress.remaining }} 项
              </el-tag>
            </div>
          </template>

          <el-progress
            v-if="progress"
            :percentage="collectPercent"
            :status="progress.remaining === 0 ? 'success' : undefined"
            class="mb16"
          />
          <div v-if="progressChangeNotice" class="progress-change-notice" role="status" aria-live="polite">
            <strong>{{ progressChangeNotice.title }}</strong>
            <span>{{ progressChangeNotice.detail }}</span>
          </div>

          <details
            v-if="progress"
            class="progress-disclosure"
            :open="!compactProgressByDefault"
          >
            <summary>
              <span>
                <strong>查看平台建议和全部进度</strong>
                <small>已确认 {{ answeredKeys.length }} 项 · 待确认 {{ progress.pendingItems?.length ?? 0 }} 项</small>
              </span>
              <el-tag size="small" type="info" effect="plain">建议与进度</el-tag>
            </summary>
            <div class="progress-disclosure-body">
          <!-- 平台拥有的可编辑建议；公开响应和界面都不携带来源客户。 -->
          <div v-if="progress.platformRecommendation" class="caseref">
            <div class="caseref-head">
              平台建议流程
              <span class="caseref-src">请按当前工厂实际情况确认</span>
            </div>
            <ol class="caseref-steps">
              <li v-for="(st, i) in progress.platformRecommendation.steps ?? []" :key="i">
                <b>{{ (st as any).name }}</b>
                <span class="caseref-io">{{ (st as any).input }} → {{ (st as any).output }}</span>
              </li>
            </ol>
          </div>

          <!-- ⛔ 这条不许静默：没有平台建议或道数与建议不一致都要让客户看见。 -->
          <el-alert
            v-if="progress?.recommendationNote"
            type="warning"
            :closable="false"
            show-icon
            class="mb16"
            :title="progress.recommendationNote"
          />

          <!-- S2-01/02: 沿用上一版已确认的资料只列出来让客户核对, 有不同再改; 与已确认值冲突的逐条选来源。 -->
          <el-alert
            v-if="Object.keys(progress?.reusedFacts ?? {}).length"
            type="success"
            :closable="false"
            show-icon
            class="mb16"
            data-testid="reused-facts"
            :title="`已沿用上一版确认的 ${Object.keys(progress?.reusedFacts ?? {}).length} 项资料，下面只需核对有差异的部分`"
          >
            <ul class="reused-list">
              <li v-for="item in progress?.reusedFactItems ?? []" :key="item.field">{{ item.label || '其他事项' }}：{{ item.value }}</li>
            </ul>
          </el-alert>
          <el-alert
            v-if="(progress?.carriedFacts ?? []).length"
            type="info"
            :closable="false"
            show-icon
            class="mb16"
            data-testid="carried-facts"
            :title="`上一版已填写 ${(progress?.carriedFacts ?? []).length} 项，请确认（这些还不算已确认）`"
          >
            <ul class="reused-list">
              <li v-for="item in progress?.carriedFacts" :key="item.field">
                {{ item.label || '其他事项' }}：{{ item.value }}
                <el-button link type="primary" :disabled="sending" @click="confirmCarried([item.field])">确认这一项</el-button>
              </li>
            </ul>
            <el-button type="primary" size="small" :loading="sending" data-testid="confirm-all-carried" @click="confirmCarried()">全部确认</el-button>
            <span class="hint">有不同的直接回答对应问题即可修改。</span>
          </el-alert>
          <el-alert
            v-if="(progress?.factConflicts ?? []).length"
            type="warning"
            :closable="false"
            show-icon
            class="mb16"
            data-testid="fact-conflicts"
            :title="`有 ${(progress?.factConflicts ?? []).length} 项新回答与已确认资料不同，需要选择采用哪个来源`"
          >
            <ul class="reused-list">
              <li v-for="item in progress?.factConflicts" :key="item.pendingId">{{ item.label || '其他事项' }}：已确认「{{ item.confirmedValue }}」，新回答「{{ item.newValue }}」</li>
            </ul>
            <span>请由本厂管理员在「建厂就绪 → 已确认资料与版本历史」逐条选择采用哪个来源。</span>
          </el-alert>

          <!-- 已答事实与来源逐条来自服务端 typed receipts；pending/skipped 继续留在清单。 -->
          <FactoryConversationFactLedger :facts="progress.factEvidence ?? []" @edit="startEditingFact" />
          <div class="checklist">
            <div v-for="it in (progress.pendingItems ?? [])" :key="it.name" class="ck">
              <el-icon class="pending"><Clock /></el-icon>
              <div class="ck-body">
                <div class="ck-label">{{ it.label }}</div>
                <div class="ck-empty">待确认</div>
              </div>
            </div>
            <!-- 问过没拿到、已经放弃的 —— ⛔ 不许静默消失, 那是「被系统悄悄放弃」 -->
            <div v-for="it in (progress.skippedItems ?? [])" :key="'sk-' + it.name" class="ck">
              <el-icon class="skipped"><WarningFilled /></el-icon>
              <div class="ck-body">
                <div class="ck-label">{{ it.label }}</div>
                <div class="ck-skipped">{{ it.why || '问过没拿到，先跳过了' }}，可以现在补一句</div>
              </div>
            </div>
            <div v-for="it in (progress.unresolvedDecisions ?? [])" :key="'unresolved-' + it.field" class="ck unresolved-decision">
              <el-icon class="skipped"><WarningFilled /></el-icon>
              <div class="ck-body">
                <div class="ck-label">{{ it.label }}</div>
                <div class="ck-skipped">已明确标记为暂时不确定；完成方案确认前必须回补</div>
              </div>
            </div>
          </div>
            </div>
          </details>

          <el-alert
            v-if="maxRoundsReached"
            type="warning"
            :closable="false"
            show-icon
            class="mt16"
            title="对话已达轮次上限"
            description="剩余项没能问齐。可以重新开始一轮，把没答上的几项一次说清楚。"
          />

          <!--
            没有分类的物料 —— ⛔ 只显示、不自动补。

            按名字猜分类是错的: 实测有真实物料的两家工厂**根本没建过 L1 分类树**,
            猜也没有地方可写。真正的缺口是「这家厂还没定义分类」, 所以这里给的是去路。
          -->
          <el-alert
            v-if="unclassified.count > 0"
            type="warning"
            :closable="false"
            show-icon
            class="mt16"
            :title="`有 ${unclassified.count} 条物料没有分类`"
          >
            <div class="unclass-note">{{ unclassified.note }}</div>
            <div v-if="unclassified.rows.length" class="unclass-list">
              <span v-for="r in unclassified.rows" :key="r.code" class="unclass-item">
                {{ r.name }}<em>{{ r.code }}</em>
              </span>
            </div>
          </el-alert>

          <section v-if="progress?.readyForGenerate || reconciliationIssues.length" class="reconciliation" aria-labelledby="reconciliation-title">
            <div class="reconciliation-head">
              <div>
                <span class="reconciliation-kicker">Agent 反问</span>
                <h3 id="reconciliation-title">我先帮你反问一遍</h3>
                <p>名称重复、数量对不上会阻止生成；没拿准的答案会一直带到预览和复核。</p>
              </div>
              <el-tag :type="blockingIssues.length ? 'danger' : 'success'" effect="dark">
                {{ blockingIssues.length ? `${blockingIssues.length} 项必须修正` : '可以进入装配预览' }}
              </el-tag>
            </div>
            <div v-if="reconciliationIssues.length" class="issue-list">
              <article v-for="issue in reconciliationIssues" :key="issue.id" class="issue-card" :class="issue.severity">
                <div class="issue-copy">
                  <strong>{{ issue.title }}</strong>
                  <p>{{ issue.detail }}</p>
                </div>
                <div v-if="issue.targetKey" class="issue-fix">
                  <el-input
                    v-model="correctionDrafts[issue.id]"
                    :placeholder="`修正${issue.targetLabel ?? '这项'}，例如直接给出正确答案`"
                    @keydown.enter.exact.prevent="submitCorrection(issue)"
                  />
                  <el-button :type="issue.severity === 'blocking' ? 'danger' : 'primary'" plain :disabled="!correctionDrafts[issue.id]?.trim()" @click="submitCorrection(issue)">
                    提交修正
                  </el-button>
                </div>
              </article>
            </div>
            <el-empty v-else :image-size="52" description="没有发现明显矛盾；生成后仍需核对图表和逐项差异。" />
          </section>

          <div class="acts">
            <el-button v-if="sessionId" @click="restart">重新开始</el-button>
            <el-button
              type="primary"
              :disabled="!progress?.readyForGenerate || blockingIssues.length > 0"
              :loading="generating"
              @click="generate"
            >
              {{ progress?.readyForGenerate ? (blockingIssues.length ? `先修正 ${blockingIssues.length} 项矛盾` : '生成装配预览') : `还差 ${progress?.remaining ?? '…'} 项` }}
            </el-button>
          </div>
        </el-card>
      </div>

      <!-- ============ 步骤 2: 预览差异 ============ -->
      <el-card v-show="step === 1 && (!canonicalViewMode || canonicalViewReady)" shadow="never" class="content-card">
        <template #header>
          <div class="card-head">
            <span>预览 —— 确认后会写入这些</span>
            <div class="head-right">
              <span class="ow-label">已存在的同名配置</span>
              <!-- Rule 3: 用开关二选一, 不让用户自由文本决定覆盖与否 -->
              <el-switch
                v-model="overwrite"
                inline-prompt
                active-text="覆盖"
                inactive-text="跳过"
                @change="markPreviewDirty"
              />
            </div>
          </div>
        </template>

        <section v-if="canonicalViewMode" class="canonical-view-callout" aria-labelledby="canonical-view-title">
          <div>
            <span class="structured-kicker">服务端权威蓝图</span>
            <strong id="canonical-view-title">正在查看已保存的蓝图</strong>
            <p>当前编辑器只使用已保存蓝图的来源和当前工厂身份；原来的访谈可随时单独返回。</p>
          </div>
          <el-button plain :loading="canonicalViewLoading" @click="returnToRecoveredConversation">
            返回原访谈
          </el-button>
        </section>

        <div class="inference-summary">
          <div>
            <span>Agent 推演结果</span>
            <strong>下面不是你填写的配置表，而是 Agent 根据代表性样例生成的整厂建议</strong>
            <p>先看流程图和覆盖范围；只有发现理解偏差时，才展开专业复核修改细节。</p>
          </div>
          <el-tag type="warning" effect="plain">推断结果 · 写入前需确认</el-tag>
        </div>

        <section v-if="draftCapabilityIntentDefinitions.length" class="draft-intent-card" aria-labelledby="draft-intent-title">
          <div class="draft-intent-head">
            <div>
              <span class="structured-kicker">可选能力意图</span>
              <h3 id="draft-intent-title">这家工厂还需要哪些能力方向？</h3>
              <p>这里只记录你希望纳入草稿的方向，不会代填事实、创建正式配置或直接写入业务数据。</p>
            </div>
            <el-tag :type="previewDirty ? 'warning' : 'info'" effect="plain">
              {{ previewDirty ? '选择尚未保存' : '已从权威草稿回读' }}
            </el-tag>
          </div>
          <el-checkbox-group
            v-model="draftCapabilityIntentSelection"
            class="draft-intent-grid"
            :disabled="previewing || generating"
            @change="markPreviewDirty"
          >
            <el-checkbox
              v-for="definition in draftCapabilityIntentDefinitions"
              :key="definition.id"
              :label="definition.id"
              class="draft-intent-option"
            >
              <span>{{ definition.label }}</span>
              <small>{{ definition.description }}</small>
            </el-checkbox>
          </el-checkbox-group>
          <div class="draft-intent-actions">
            <span>清空选择也是有效草稿；保存后会由服务端重新计算依赖和校验。</span>
              <el-button
                type="primary"
                plain
                :loading="previewing"
                :disabled="!previewDirty"
                @click="refreshPreview"
              >
              保存能力选择并刷新计划
              </el-button>
          </div>
        </section>
        <el-alert
          v-else-if="capabilityCatalogError"
          type="error"
          :closable="false"
          show-icon
          class="mb16"
          title="可选能力目录读取失败"
          :description="capabilityCatalogError"
        >
          <el-button size="small" @click="hydrateCapabilityCatalog(canonicalBlueprint?.capabilityCatalogVersion)">
            重试读取能力目录
          </el-button>
        </el-alert>

        <FactoryAssemblyPreview
          v-if="canonicalBlueprint"
          :blueprint="canonicalBlueprint"
          :formal-executions="formalExecutions"
          :catalog="capabilityCatalog"
          :catalog-error="capabilityCatalogError"
        />
        <el-alert
          v-else
          type="error"
          :closable="false"
          show-icon
          title="权威 Factory Blueprint 尚未回读成功"
          description="不会用浏览器本地摘要替代服务端真值。对话和修改仍保留，请重试保存。"
        />

        <el-collapse v-if="generated?.suggestedData && generated.schemas" class="expert-review">
          <el-collapse-item name="expert-details">
            <template #title>
              <div class="expert-review-title">
                <strong>专业复核（可选）</strong>
                <span>只有需要逐字段纠正时才展开；日常流程不要求填写这些表。</span>
              </div>
            </template>
            <FactoryAssemblyDetailEditor
              :suggested-data="generated.suggestedData"
              :schemas="generated.schemas"
              @changed="markPreviewDirty"
              @replace="replaceGeneratedPayload"
            />
          </el-collapse-item>
        </el-collapse>

        <div class="preview-validation" :class="previewValidationState.tone" role="status" aria-live="polite">
          <div>
            <strong>{{ previewValidationState.title }}</strong>
            <p>{{ previewValidationState.detail }}</p>
          </div>
          <el-button :type="previewDirty ? 'primary' : 'default'" :loading="previewing" @click="refreshPreview">
            {{ previewValidationState.action }}
          </el-button>
        </div>

        <section v-if="serverBlockingIssues.length" class="validation-blockers" aria-labelledby="validation-blockers-title">
          <div>
            <strong id="validation-blockers-title">必须修正后才能继续</strong>
            <span>来自本次服务端业务一致性验证</span>
          </div>
          <ul>
            <li v-for="issue in serverBlockingIssues" :key="`${issue.code}:${issue.capabilityId}:${issue.message}`">
              <strong>{{ issue.message }}</strong>
              <span>{{ issue.recovery }}</span>
            </li>
          </ul>
        </section>

        <el-alert v-if="generated?.aiSummary" type="info" :closable="false" show-icon class="mb16"
                  :title="generated.aiSummary" />

        <el-alert
          v-if="currentLoadFailed"
          type="warning" :closable="false" show-icon class="mb16"
          title="读不到该工厂的现有配置"
          description="下面只能显示「将写入什么」，无法标出哪些是覆盖。建议确认后再核对一次工厂配置。"
        />

        <!-- Rule 1: 逐项显示 现值 → 结果 -->
        <el-table :data="diffRows" size="small" border class="diff">
          <el-table-column prop="group" label="类别" width="110" />
          <el-table-column prop="key" label="标识" width="220">
            <template #default="{ row }"><code>{{ row.key }}</code></template>
          </el-table-column>
          <el-table-column prop="detail" label="内容" min-width="220" />
          <el-table-column label="现值" width="110">
            <template #default="{ row }">
              <span :class="row.exists ? 'was' : 'none'">{{ row.exists ? '已存在' : '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="确认后" width="130">
            <template #default="{ row }">
              <el-tag :type="actionTagType(row.action)" size="small" disable-transitions>
                {{ row.action }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>

        <div class="summary">
          将新建 <b>{{ createCount }}</b> 项<template v-if="overwriteCount">，覆盖 <b class="warn">{{ overwriteCount }}</b> 项</template>
          <template v-if="skipCount">，跳过 <b>{{ skipCount }}</b> 项（已存在且未选覆盖）</template>
          <template v-if="pendingDecisionCount">，待人工确认 <b class="warn">{{ pendingDecisionCount }}</b> 项</template>
        </div>

        <FactoryReadiness v-if="canonicalBlueprint" :factory-id="factoryId"
          :blueprint-id="canonicalBlueprint.id" :row-version="canonicalBlueprint.rowVersion"
          :refresh-key="readinessRefresh" />

        <FactoryBlueprintDraftsPanel
          v-if="canonicalBlueprint && ['VALIDATED', 'PUBLISHED', 'APPLIED'].includes(canonicalBlueprint.lifecycleState)"
          :factory-id="factoryId"
          :factory-name="factoryName || canonicalBlueprint.factoryName"
          :blueprint="canonicalBlueprint"
          @formal-state="onFormalState"
        />

        <div class="acts">
          <el-button @click="returnToConversation">{{ canonicalViewMode && recoveredConversation ? '返回原访谈' : (canResumeConversation ? '返回修改对话' : '开始新的访谈版本') }}</el-button>
          <el-button
            v-if="canonicalBlueprint?.lifecycleState === 'DRAFT'"
            type="primary"
            :loading="blueprintOperation === 'VALIDATE'"
            :disabled="previewDirty"
            @click="validateBlueprint"
          >服务端验证</el-button>
          <el-button
            v-if="canonicalBlueprint?.lifecycleState === 'VALIDATED' && !canonicalBlueprint.simulation?.safe"
            type="primary"
            :loading="blueprintOperation === 'SIMULATE'"
            @click="simulateBlueprint"
          >零写模拟</el-button>
          <el-button
            v-if="canonicalBlueprint?.lifecycleState === 'VALIDATED' && canonicalBlueprint.simulation?.safe"
            type="warning"
            plain
            :loading="blueprintOperation === 'PUBLISH'"
            @click="publishBlueprint"
          >发布蓝图版本</el-button>
          <el-button
            v-if="canonicalBlueprint?.lifecycleState === 'PUBLISHED'"
            type="primary"
            :loading="applying"
            @click="confirmApply"
          >确认应用配置</el-button>
          <el-tag v-if="canonicalBlueprint?.lifecycleState === 'APPLIED'" type="success" effect="plain">配置已应用 · Workflow 仍待单独发布</el-tag>
        </div>
        <FactoryBasicsHandoff v-if="canonicalBlueprint?.lifecycleState === 'APPLIED'" />
      </el-card>

      <el-card v-if="step === 0 && proposalNeedsCorrection && generated?.suggestedData && generated.schemas"
               shadow="never" class="content-card" data-testid="rejected-proposal-editor">
        <template #header>修正尚未保存的配置建议</template>
        <FactoryAssemblyDetailEditor
          :suggested-data="generated.suggestedData"
          :schemas="generated.schemas"
          @changed="markPreviewDirty"
          @replace="replaceGeneratedPayload"
        />
      </el-card>

      <!-- ============ 步骤 3: 结果 ============ -->
      <el-card v-show="step === 2" shadow="never" class="content-card">
        <el-result
          :icon="applied?.success ? 'success' : 'warning'"
          :title="applied?.success ? '工厂配置已写入' : '部分模块需要复核'"
          :sub-title="applied?.message"
        >
          <template #extra>
            <FactoryBasicsHandoff v-if="applied?.success && canonicalBlueprint?.lifecycleState !== 'APPLIED'" />
            <div class="closeout-rail" aria-label="建厂收尾顺序">
              <div class="closeout-step" :class="applied?.success ? 'done-step' : 'attention-step'">
                <span>1</span><div><strong>{{ applied?.success ? '配置写入' : '部分写入' }}</strong><small>已取得服务端结构化回执</small></div>
              </div>
              <div class="closeout-step current-step">
                <span>2</span><div><strong>逐屏复核</strong><small>下一步：对照说法、计划和库中现值</small></div>
              </div>
              <div class="closeout-step" :class="formalWorkflowReadbackCount ? 'done-step' : ''">
                <span>3</span><div><strong>Workflow 发布</strong><small>{{ formalWorkflowReadbackCount ? `本次已发布并回读 ${formalWorkflowReadbackCount} 条；其余逐项核对` : `待人工核对 ${generatedWorkflowLanes.length} 条产品工艺` }}</small></div>
              </div>
            </div>
            <div class="done">
              <section v-if="recoverySteps.length" class="result-recovery" aria-labelledby="recovery-title">
                <div>
                  <span>人工恢复计划</span>
                  <strong id="recovery-title">不会伪装成自动回滚</strong>
                  <p v-if="recoveryReason">{{ recoveryReason }}</p>
                </div>
                <ol>
                  <li v-for="item in recoverySteps" :key="item">{{ item }}</li>
                </ol>
              </section>
              <!--
                🔴 「你说了而没配上」的那几条要在**这一屏**也说清楚, ⛔ 不能只在复核页。
                这是客户刚建完看到的第一屏, 也是他唯一一次「趁还记得就去补」的时机。
                ⚠️ 那段话此前只混在 aiSummary 那一大段文字里(靠他自己读出来),
                   现在按类列成条目 + 每条带「怎么补」——与复核向导同一份数据(notBuilt)。
              -->
              <div v-if="notBuiltGroups.length" class="notbuilt">
                <div class="notbuilt-head">
                  ⚠️ 你说了，但这几样没配上（{{ notBuiltCount }}）
                </div>
                <div v-for="g in notBuiltGroups" :key="g.group" class="notbuilt-group">
                  <span class="done-label">{{ g.group }}</span>
                  <span class="notbuilt-items">
                    {{ g.items.map((i) => `${i.item}（${i.why}）`).join('、') }}
                  </span>
                  <div class="notbuilt-fix">怎么补：{{ g.items[0].howToFix }}</div>
                </div>
              </div>
              <!--
                🔴 反方向: 配出来了、而客户没说过的。⛔ 不与上面那块合并 ——
                   两者的措辞正好相反, 混在一起客户会以为「没配上」的东西又配上了。
              -->
              <div v-if="extraBuiltItems.length" class="notbuilt extra">
                <div class="notbuilt-head">
                  ⚠️ 这几样是我补的，你没有专门说过（{{ extraBuiltItems.length }}）
                </div>
                <div class="notbuilt-group">
                  <span class="done-label">{{ extraBuiltItems[0].group }}</span>
                  <span class="notbuilt-items">
                    {{ extraBuiltItems.map((i) => i.item).join('、') }}
                  </span>
                  <div class="notbuilt-fix">不要的话：{{ extraBuiltItems[0].howToFix }}</div>
                </div>
              </div>
              <!--
                ⛔ 显示的是**名字**不是编码: 回执每一类都是编码清单
                (`writtenProductTypes: ["FD_CHICKEN"]`), 而客户说的是「冻干鸡胸粒」。
                查表用的是同一次响应里的 planned(见 humanizeKeys)。
              -->
              <div v-for="g in doneGroups" :key="g.label" class="done-row">
                <span class="done-label">{{ g.label }}</span>
                <span v-if="g.written.length" class="done-written">{{ human(g.written) }}</span>
                <span v-else class="done-none">—</span>
                <span v-if="g.skipped.length" class="done-skip">跳过：{{ human(g.skipped) }}</span>
              </div>
              <!--
                折叠层:偏系统内部的 5 类。⛔ 不能不给 —— 否则就成了「后端写了而没人说过」;
                也不默认摊开 —— 产品目标写着「不是把所有内容一次性丢给客户消化」。
              -->
              <el-collapse v-if="doneGroupsMore.length" class="done-more">
                <el-collapse-item name="more">
                  <template #title>
                    <span class="done-more-title">还写入了系统配置 {{ doneGroupsMore.length }} 类（点开查看）</span>
                  </template>
                  <div v-for="g in doneGroupsMore" :key="g.label" class="done-row">
                    <span class="done-label">{{ g.label }}</span>
                    <span v-if="g.written.length" class="done-written">{{ human(g.written) }}</span>
                    <span v-else class="done-none">—</span>
                    <span v-if="g.skipped.length" class="done-skip">跳过：{{ human(g.skipped) }}</span>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
            <div class="next-best-action">
              <div>
                <span>推荐下一步</span>
                <strong>先逐屏复核这次配了什么</strong>
                <p>按“当时怎么说 → 计划写什么 → 实际写了什么 → 现在库里是什么”逐屏核对。复核页只读，不会再次写库。</p>
              </div>
              <el-button type="primary" @click="goReview">开始逐屏复核</el-button>
            </div>
            <div v-if="generatedWorkflowLanes.length" class="workflow-next">
              <div class="workflow-next-head">
                <div>
                  <strong>复核之后：逐个核对并发布产品 Workflow</strong>
                  <p>工厂配置写入不等于 Workflow 已发布。建议先完成上面的逐屏复核，再进入画布核对节点、连线和 BOM。</p>
                </div>
                <el-tag :type="formalWorkflowReadbackCount ? 'success' : 'warning'" effect="plain">{{ formalWorkflowReadbackCount ? `本次已发布并回读 ${formalWorkflowReadbackCount} 条` : `待人工发布 ${generatedWorkflowLanes.length} 条` }}</el-tag>
              </div>
              <div class="workflow-next-list">
                <el-button
                  v-for="lane in generatedWorkflowLanes"
                  :key="lane.productCode"
                  plain
                  @click="goWorkflow(lane.productCode)"
                >
                  核对并发布 {{ lane.productName }} Workflow
                </el-button>
              </div>
            </div>
            <div class="acts center">
              <el-button @click="restart">再配一个</el-button>
              <el-button @click="backToPicker">换一个工厂</el-button>
            </div>
          </template>
        </el-result>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, WarningFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/store/modules/auth'
import {
  startFactoryBuildConversation,
  replyFactoryBuildConversation,
  getFactoryBuildProgress,
  getRecoverableFactoryBuildConversation,
  aiInitializeFactory,
  getInterviewDefaults,
  saveCanonicalFactoryBlueprintDraft,
  getCurrentCanonicalFactoryBlueprint,
  getCanonicalFactoryCapabilityCatalog,
  validateCanonicalFactoryBlueprint,
  simulateCanonicalFactoryBlueprint,
  publishCanonicalFactoryBlueprint,
  applyCanonicalFactoryBlueprint,
  fetchCurrentFactoryConfig,
  resolveProductTypeIdByCode,
  type FactoryBuildProgress,
  type AIFactoryInitResult,
  type ApplyAIInitResult,
  type PlannedChange,
  type CurrentFactoryConfig,
  submitStructuredAnswers,
  confirmCarriedFacts,
  listFactories,
  humanizeKeys,
  type FactoryBrief,
  type PendingItem,
  type FactoryDecisionPacket,
  type EntitySchema,
  type SuggestedData,
  type CanonicalFactoryBlueprint,
  type CanonicalCapabilityCatalog,
} from '@/api/factoryOnboardingApi'
import FactoryAssemblyPreview from './components/FactoryAssemblyPreview.vue'
import FactoryAssemblyDetailEditor from './components/FactoryAssemblyDetailEditor.vue'
import FactoryConversationProcessTable from './components/FactoryConversationProcessTable.vue'
import FactoryConversationProcessDetails from './components/FactoryConversationProcessDetails.vue'
import FactoryConversationProductTable from './components/FactoryConversationProductTable.vue'
import FactoryConversationDomainCard from './components/FactoryConversationDomainCard.vue'
import FactoryConversationReceipt from './components/FactoryConversationReceipt.vue'
import FactoryConversationDecisionPacket from './components/FactoryConversationDecisionPacket.vue'
import FactoryConversationFactLedger from './components/FactoryConversationFactLedger.vue'
import FactoryInterviewArchive from './components/FactoryInterviewArchive.vue'
import FactoryConversationRoutePreview from './components/FactoryConversationRoutePreview.vue'
import FactoryConversationTopologyCard from './components/FactoryConversationTopologyCard.vue'
import FactoryBlueprintDraftsPanel from './components/FactoryBlueprintDraftsPanel.vue'
import FactoryReadiness from '../factory-review/components/FactoryReadiness.vue'
import FactoryBasicsHandoff from './components/FactoryBasicsHandoff.vue'
import type { FormalCapability, FormalExecution } from '@/api/factoryBlueprintActivationApi'
import { formalRuntimeCounts } from './factoryBlueprintFormalState'
import { selectedMetricInterviewAnswer } from './metricInterviewAnswer'
import { factoryPreviewRecovery, saveAndEnterFactoryPreview } from './factoryPreviewRecovery'
import {
  draftCapabilityIntentDefinitions as getDraftCapabilityIntentDefinitions,
  normalizeDraftCapabilityIntents,
  questionnaireCardIsVisible,
} from './factoryCapabilityIntentSelection'
import {
  hydrateCanonicalRecoveryView,
  restoreRecoveredConversationView,
} from './factoryCanonicalRecoveryView'
import {
  deriveReconciliationIssues,
  questionnaireItems,
  workflowLanes,
  type ReconciliationIssue,
} from './factoryAssemblyPresentation'
import { conserveFactoryAssemblyIntent } from './factoryAssemblyIntentConservation'
import {
  activeProcessDetailGroup,
  activeFactoryDomainGroup,
  buildProcessSkeletonRows,
  buildProductFlowRows,
  buildRouteTopologyDraft,
  choiceReceiptEntries,
  extractionReceiptEntries,
  isSpecializedDomainField,
  mergeSuggestedAnswers,
  productConfigurationPending,
  productFlowAnswers,
  productFlowPersisted,
  productFlowSignature,
  productPackagingItem,
  routeTopologyAnswers,
  processSkeletonAnswers,
  processSkeletonPersisted,
  processSkeletonSignature,
  restoreConversationMessages,
  suggestedAnswersForItems,
  type FactoryConversationMessage,
  type ProductFlowRow,
  type ProcessSkeletonRow,
  type RouteTopologyDraft,
} from './factoryConversationBlocks'
import {
  conversationOutcomeIssue,
  createConversationRequestId,
  generationFailureDetail,
} from './factoryConversationOutcome'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

/**
 * ⛔ 这里原先写死了 5 个题目 key 和一份中文标签表。
 *
 * 2026-08-10 实测: 其中 3 个(processChain / keyProcessParams / qualityChecks)
 * **后端早就不问了** —— 访谈换成 v2 之后题目改了名、还多了按工序/按产品动态展开的几十项,
 * 而界面一直在展示三道不存在的题, 每一层都不报错。
 *
 * 判据: **题目是服务端定义的, 就不要在前端再存一份。**
 * 现在全部读 progress.pendingItems(名字/标签/题型/选项/提示/建议一次给全)。
 */

const factoryId = ref<string>(String(route.query.factoryId ?? route.params.factoryId ?? ''))
const factoryName = ref<string>(String(route.query.factoryName ?? ''))
const factories = ref<FactoryBrief[]>([])
const factoriesLoading = ref(false)
const pickedId = ref('')

const step = ref(0)
const sessionId = ref('')
const messages = ref<FactoryConversationMessage[]>([])
const draft = ref('')
const starting = ref(false)
const sending = ref(false)
const generating = ref(false)
const previewing = ref(false)
const applying = ref(false)
const blueprintOperation = ref<'' | 'VALIDATE' | 'SIMULATE' | 'PUBLISH'>('')
const flowRetrying = ref(false)
const logRef = ref<HTMLElement | null>(null)

interface FlowIssue {
  title: string
  detail: string
  sideEffect: string
  retryLabel: string
}

const flowIssue = ref<FlowIssue | null>(null)
const retryTask = ref<null | (() => Promise<void> | void)>(null)

const progress = ref<FactoryBuildProgress | null>(null)
const progressChangeNotice = ref<{ title: string; detail: string } | null>(null)
const generated = ref<AIFactoryInitResult | null>(null)
const proposalNeedsCorrection = ref(false)
const canonicalBlueprint = ref<CanonicalFactoryBlueprint | null>(null)
const formalExecutions = ref<Partial<Record<FormalCapability, FormalExecution>>>({})
const readinessRefresh = ref(0)
const formalWorkflowReadbackCount = computed(() => formalRuntimeCounts(formalExecutions.value).workflow)
function onFormalState(capability: FormalCapability, result: FormalExecution | null) {
  readinessRefresh.value++
  if (result) formalExecutions.value[capability] = result
  else delete formalExecutions.value[capability]
}
watch(() => [factoryId.value, canonicalBlueprint.value?.id], () => { formalExecutions.value = {} })
const capabilityCatalog = ref<CanonicalCapabilityCatalog | null>(null)
const capabilityCatalogError = ref('')
const draftCapabilityIntentSelection = ref<string[]>([])
const draftCapabilityIntentDefinitions = computed(() =>
  getDraftCapabilityIntentDefinitions(capabilityCatalog.value))
const canonicalViewMode = ref(false)
const canonicalViewReady = ref(false)
const canonicalViewLoading = ref(false)
const recoveredConversation = ref<{
  sessionId: string
  status: string
  draft: string
} | null>(null)
const applied = ref<ApplyAIInitResult | null>(null)
const recoverySteps = computed<string[]>(() => {
  const steps = canonicalBlueprint.value?.latestReceipt?.recoveryPlan?.steps
  return Array.isArray(steps)
    ? steps.filter((step): step is string => typeof step === 'string')
    : []
})
const recoveryReason = computed(() => {
  const reason = canonicalBlueprint.value?.latestReceipt?.recoveryPlan?.reason
  return typeof reason === 'string' ? reason : ''
})
const current = ref<CurrentFactoryConfig | null>(null)
const currentLoadFailed = ref(false)
const overwrite = ref(false)
const previewDirty = ref(false)
const questionnaireAnswers = ref<Record<string, string | string[]>>({})
const correctionDrafts = ref<Record<string, string>>({})
const processRows = ref<ProcessSkeletonRow[]>([])
const confirmedProcessSignature = ref('')
const processDetailAnswers = ref<Record<string, string | string[]>>({})
const productRows = ref<ProductFlowRow[]>([])
const productPackaging = ref('')
const confirmedProductSignature = ref('')
const routeTopologyDraft = ref<RouteTopologyDraft | null>(null)
const domainAnswers = ref<Record<string, string | string[]>>({})
const userEditedSuggestedFields = new Set<string>()
const compactProgressByDefault = typeof window !== 'undefined'
  && window.matchMedia('(max-width: 760px)').matches

function draftStorageKey(id = factoryId.value): string {
  return `factory-blueprint:draft:${id}`
}

function readStoredDraft(): string {
  if (!factoryId.value) return ''
  try {
    return sessionStorage.getItem(draftStorageKey()) ?? ''
  } catch {
    return ''
  }
}

function clearStoredDraft() {
  if (!factoryId.value) return
  try {
    sessionStorage.removeItem(draftStorageKey())
  } catch {
    // Storage may be disabled by browser policy; server-owned session recovery still works.
  }
}

watch(draft, (value) => {
  if (!factoryId.value) return
  try {
    if (value) sessionStorage.setItem(draftStorageKey(), value.slice(0, 10_000))
    else sessionStorage.removeItem(draftStorageKey())
  } catch {
    // Draft persistence is a convenience only; never replace the server-owned session with it.
  }
})

/** 当前要答的那一题 —— 服务端 pending 的第一项 */
const currentDecision = computed<FactoryDecisionPacket | null>(
  () => progress.value?.currentDecision ?? null)
const currentItem = computed<PendingItem | null>(() => {
  const pending = progress.value?.pendingItems?.[0]
  if (pending) return pending
  const decision = currentDecision.value
  if (!decision) return null
  return {
    name: decision.field,
    label: decision.label,
    inputType: decision.inputType,
    options: decision.options,
    multiSelect: decision.multiSelect,
    allowNone: decision.allowNone,
    hint: decision.question,
    suggestion: decision.recommendation,
  }
})
const STEP_COUNT_MORE_OPTION = '更多（1-30）'
const stepCountMoreOpen = ref(false)
const stepCountMoreValue = ref<number>()
const isChoice = computed(
  () => (currentItem.value?.options?.length ?? 0) > 0)
/** 多选题的暂存 */
const pickedOptions = ref<string[]>([])
watch(() => currentItem.value?.name, (name) => {
  if (name !== 'stepCount') {
    stepCountMoreOpen.value = false
    stepCountMoreValue.value = undefined
  }
})
const answeredKeys = computed(() => Object.keys(progress.value?.collected ?? {}))
const questionnaire = computed(() => questionnaireItems(progress.value)
  .filter((item) => !isSpecializedDomainField(item.name)))
const hasUserExample = computed(() => messages.value.some((message) => message.role === 'me'))
const questionnaireNames = computed(() => new Set(questionnaire.value.map((item) => item.name)))
const questionnaireCanSubmit = computed(() => questionnaire.value.length > 0
  || (progress.value?.pending ?? []).includes('metricWatches'))
const questionnaireSubmissionAnswers = computed<Record<string, string | string[]>>(() => {
  const allowed = new Set(questionnaire.value.map((item) => item.name))
  const answers = Object.fromEntries(Object.entries(questionnaireAnswers.value)
    .filter(([name, value]) => allowed.has(name)
      && (Array.isArray(value) ? value.length > 0 : value.trim().length > 0)))
  if ((progress.value?.pending ?? []).includes('metricWatches')) {
    const metricAnswer = selectedMetricInterviewAnswer(metricRows.value)
    if (metricAnswer !== undefined) answers.metricWatches = metricAnswer
  }
  return answers
})
const questionnaireAnswerCount = computed(() => Object.keys(questionnaireSubmissionAnswers.value).length)
const processTableConfirmed = computed(() => processRows.value.length > 0
  && confirmedProcessSignature.value === processSkeletonSignature(processRows.value))
const activeProcessDetails = computed(() => activeProcessDetailGroup(progress.value, processTableConfirmed.value))
const productTableConfirmed = computed(() => productRows.value.length > 0
  && confirmedProductSignature.value === productFlowSignature(productRows.value, productPackaging.value))
const productTableActive = computed(() => processTableConfirmed.value
  && !activeProcessDetails.value
  && productRows.value.length > 0
  && (!productTableConfirmed.value || productConfigurationPending(progress.value)))
const activeProductPackagingItem = computed(() => productPackagingItem(progress.value))
const activeDomainConfig = computed(() => activeFactoryDomainGroup(
  progress.value, productTableConfirmed.value && !routeTopologyDraft.value))
const questionnaireCardVisible = computed(() => questionnaireCardIsVisible({
  hasUserExample: hasUserExample.value,
  questionnaireCount: questionnaire.value.length,
  productTableActive: productTableActive.value,
  activeDomainConfig: !!activeDomainConfig.value,
}))
const currentItemInQuestionnaire = computed(() =>
  questionnaireCardVisible.value && !!currentItem.value && questionnaireNames.value.has(currentItem.value.name))
const currentItemInProcessDetails = computed(() => !!currentItem.value
  && !!activeProcessDetails.value?.items.some((item) => item.name === currentItem.value?.name))
const currentItemInProcessTable = computed(() => !!currentItem.value
  && processRows.value.length > 0
  && /^step\d+\.(name|inputMaterial|output)$/.test(currentItem.value.name))
const currentItemInProductTable = computed(() => !!currentItem.value
  && productRows.value.length > 0
  && (currentItem.value.name === 'productSkus'
    || /^product\d+\.(name|steps|routeShape|routeDetails)$/.test(currentItem.value.name)))
const currentItemInRouteTopology = computed(() => !!currentItem.value
  && !!routeTopologyDraft.value
  && new RegExp(`^product${routeTopologyDraft.value.productIndex}\\.(routeEntry|routeTerminal|routeTopology)$`)
    .test(currentItem.value.name))
const currentItemInDomainConfig = computed(() => !!currentItem.value
  && !!activeDomainConfig.value?.items.some((item) => item.name === currentItem.value?.name))
const currentItemInStructuredCard = computed(() =>
  currentItemInQuestionnaire.value || currentItemInProcessDetails.value || currentItemInProcessTable.value
  || currentItemInProductTable.value || currentItemInRouteTopology.value || currentItemInDomainConfig.value)
const currentDecisionInStructuredCard = computed(() => !!currentDecision.value
  && currentDecision.value.field === currentItem.value?.name
  && currentItemInStructuredCard.value)
const suggestedPrefillAnswers = computed(() => suggestedAnswersForItems(
  progress.value?.pendingItems ?? [],
  progress.value?.suggestions ?? {},
))
const suggestedPrefillCount = computed(() => Object.keys(suggestedPrefillAnswers.value).length)
const reconciliationIssues = computed(() => deriveReconciliationIssues(progress.value))
const blockingIssues = computed(() => reconciliationIssues.value.filter((issue) => issue.severity === 'blocking'))
const serverBlockingIssues = computed(() => canonicalBlueprint.value?.validation?.blockingIssues ?? [])
const canResumeConversation = computed(() => progress.value?.status === 'ACTIVE')
const hasSeparateSavedCanonical = computed(() => {
  const recovered = recoveredConversation.value
  const blueprint = canonicalBlueprint.value
  return !!recovered && !!blueprint?.sessionId
    && blueprint.sessionId !== recovered.sessionId
    && sessionId.value === recovered.sessionId
    && !canonicalViewMode.value
})
const previewValidationState = computed(() => {
  if (previewDirty.value) {
    return {
      tone: 'dirty',
      title: '细节已修改，权威草稿还没有保存这份新配置',
      detail: '先保存并刷新计划；保存草稿不会应用工厂配置，也不等于业务一致性验证通过。',
      action: '保存草稿并刷新计划',
    }
  }
  if (!canonicalBlueprint.value?.validation) {
    return {
      tone: 'draft',
      title: '权威草稿已保存，尚未执行业务一致性验证',
      detail: '下方差异来自同一份服务端载荷；继续点击“服务端验证”才能检查转换来源、单位和模块逻辑。',
      action: '重新保存草稿',
    }
  }
  if (!canonicalBlueprint.value.validation.valid) {
    return {
      tone: 'blocked',
      title: `服务端验证发现 ${serverBlockingIssues.value.length} 个阻塞项`,
      detail: '请按下方修复建议调整专业配置，再保存草稿并重新验证；业务配置写入为 0。',
      action: '重新保存草稿',
    }
  }
  return {
    tone: 'valid',
    title: '服务端业务一致性验证通过',
    detail: '验证与草稿使用同一份服务端载荷；此时仍未发布蓝图、未应用配置、未发布 Workflow。',
    action: '重新保存草稿',
  }
})
const generatedWorkflowLanes = computed(() => canonicalBlueprint.value?.workflowLanes
  ?? workflowLanes(generated.value?.suggestedData))
const operationStage = computed(() => {
  if (canonicalViewLoading.value) {
    return {
      title: '正在读取已保存的 Factory Blueprint',
      detail: '正在回读蓝图来源和绑定的服务端访谈身份；身份一致前不会开放编辑或保存。',
      writes: false,
    }
  }
  if (starting.value) {
    return {
      title: `正在打开 ${factoryName.value || factoryId.value} 的建厂访谈`,
      detail: `目标是工厂 ${factoryId.value}。当前只创建访谈会话，不生成配置，也不写入业务数据。`,
      writes: false,
    }
  }
  if (applying.value) {
    return {
      title: `正在写入 ${factoryName.value || factoryId.value} 的工厂配置`,
      detail: `目标是工厂 ${factoryId.value}。已进入写入阶段，当前请求不支持中途取消，请勿刷新或重复提交。`,
      writes: true,
    }
  }
  if (blueprintOperation.value) {
    const labels = {
      VALIDATE: ['正在验证蓝图依赖', '服务端重新核对能力依赖和来源；业务配置写入为 0。'],
      SIMULATE: ['正在执行零写模拟', '服务端计算影响模块、逐项差异与 Workflow 边界；业务配置写入为 0。'],
      PUBLISH: ['正在发布不可变蓝图版本', '只发布蓝图快照，不应用工厂配置，也不发布 Workflow。'],
    } as const
    return { title: labels[blueprintOperation.value][0], detail: labels[blueprintOperation.value][1], writes: false }
  }
  if (previewing.value) {
    return {
      title: '正在校验你修改后的全部配置细节',
      detail: '服务端会用与最终写入相同的校验器和计划器重新计算图表与逐项差异；此时仍不会写入工厂。',
      writes: false,
    }
  }
  if (generating.value) {
    return {
      title: '正在生成装配建议与差异计划',
      detail: '只生成建议、读取现值并计算预览，不会写入工厂；当前请求暂不支持中途取消。',
      writes: false,
    }
  }
  if (sending.value) {
    return {
      title: '正在保存回答并确定下一问',
      detail: '当前只更新访谈会话，不生成配置，也不写入工厂业务数据。',
      writes: false,
    }
  }
  return null
})

const collectPercent = computed(() => {
  // ⛔ 分母用「已答 + 待答」现算, 不用常数。
  //    题数会随展开变化(答 4 道工序就多出 32 项), 写死 5 的话进度条会一路显示 >100%。
  const p = progress.value
  if (!p) return 0
  const total = (p.answered?.length ?? 0) + (p.pending?.length ?? 0)
  if (total <= 0) return 0
  return Math.round(((p.answered?.length ?? 0) / total) * 100)
})
const stepOneDesc = computed(() =>
  progress.value ? `还需确认 ${progress.value.remaining} 项` : '先讲一个生产样例')
const maxRoundsReached = computed(() =>
  progress.value?.status === 'MAX_ROUNDS_REACHED' && progress.value.remaining > 0)

/**
 * 差异行 —— Rule 1 的载体, **由服务端算**。
 *
 * ⛔ 这里原先是前端自己算的一份, 与落库那份必然漂:
 *   · 转换率写死 exists=false → 预览说「新建 2 项」, 实际写入 0 项;
 *   · 只列 模板/产品/原料/转换率 四类, 而落库已经会写工序、辅料包材配方、
 *     存放区、产品工序链、模块开关、预警阈值 ——
 *     **客户确认的东西里有一半没给他看过**, 而界面上完全看不出少了什么。
 * 现在预览与落库调同一个 planChanges(), 后端还把计划随落库结果一起回传,
 * 闸据此逐条比对(scripts/tests/test-preview-matches-apply.sh)。
 */
const diffRows = ref<PlannedChange[]>([])

function actionTagType(action: string): 'success' | 'warning' | 'info' | 'danger' {
  if (action === '新建') return 'success'
  if (action === '覆盖') return 'warning'
  if (action === '待确认') return 'danger'
  return 'info'
}

const createCount = computed(() => diffRows.value.filter(r => r.action === '新建').length)
const overwriteCount = computed(() => diffRows.value.filter(r => r.action === '覆盖').length)
const skipCount = computed(() => diffRows.value.filter(r => r.action === '跳过').length)
const pendingDecisionCount = computed(() => diffRows.value.filter(r => r.action === '待确认').length)

/**
 * apply 之后常显的那几类。
 *
 * 🔴 2026-08-15 之前这里只有 4 组,而后端逐类带回 **16 类**、预览里更是
 * 告知了 15 类 —— **客户被告知「将要发生 15 类」,做完只被复述 4 类**。
 * 产品目标原话是「apply 之后能看到实际发生了什么,**两者对得上**」。
 *
 * ⛔ 但也不是全摊开 —— 同一段还写着「**不是把所有内容一次性丢给客户消化**」。
 * ⇒ 按「不显示的代价」分两层:代价高的常显,偏系统内部的折叠(见 doneGroupsMore)。
 *
 * 由 `scripts/tests/check-after-apply-visible.py` 钉住:后端新增一类而这里没跟 ⇒ 闸红。
 */
/**
 * 回执清单显示成客户说过的名字 —— ⛔ 不把编码甩给他。
 *
 * 查表用的是这一次响应自带的 `planned`(键 → 那条的 detail 里的名字)。
 * ⚠️ 没有 planned(老响应)时原样显示编码 —— ⛔ 不留空, 也不猜。
 */
function human(list: string[]): string {
  return list.map((x) => humanizeKeys(x, applied.value?.planned)).join('、')
}

/**
 * 「你说了而没配上」按类归一下 —— 这一屏和复核向导用的是**同一份** `notBuilt`。
 *
 * ⛔ 不在这里另算一遍「哪些没配上」: 那是生成端算好的(它才知道客户说过什么),
 *    前端再推一遍就是形态 D(同一个东西两份, 一定会漂)。
 */
/**
 * ⚠️ 老快照里的条目**没有 kind** —— 一律按「说了没配上」处理。
 *    ⛔ 不许因为它是 null 就不显示: 那会让历史建厂的交代凭空消失。
 */
const isExtra = (r: { kind?: string | null }) => r.kind === 'extra'

const notBuiltGroups = computed(() => {
  const rows = (applied.value?.notBuilt ?? []).filter((r) => !isExtra(r))
  const byGroup = new Map<string, typeof rows>()
  rows.forEach((r) => {
    const g = r.group || '其它'
    byGroup.set(g, [...(byGroup.get(g) ?? []), r])
  })
  return [...byGroup.entries()].map(([group, items]) => ({ group, items }))
})

const notBuiltCount = computed(() => (applied.value?.notBuilt ?? []).filter((r) => !isExtra(r)).length)

/**
 * 🔴 「配出来了、而你没说过」——反方向的那一半。
 *
 * 2026-08-18 实测(同一套答案连跑 8 遍): 客户报了 2 个成品的名字, 有 2 遍配出 4 个。
 * 多出来的在预览里看得见, 却**没有一句话点出「这几个是我加的」**;
 * 而 aiSummary 那句话只活在建厂那一屏, 客户第二天来复核时它早关了。
 * ⇒ 与「没配上」共用同一条结构化通道(notBuilt + kind), 渲染成两块。
 */
const extraBuiltItems = computed(() => (applied.value?.notBuilt ?? []).filter(isExtra))

const doneGroups = computed(() => {
  const a = applied.value
  if (!a) return []
  return [
    { label: '表单模板', written: a.writtenTemplates ?? [], skipped: a.skippedTemplates ?? [] },
    { label: '产品类型', written: a.writtenProductTypes ?? [], skipped: a.skippedProductTypes ?? [] },
    { label: '原料类型', written: a.writtenMaterialTypes ?? [], skipped: a.skippedMaterialTypes ?? [] },
    { label: '转换率', written: a.writtenConversions ?? [], skipped: a.skippedConversions ?? [] },
    // ── 以下 7 类 2026-08-15 补上, 按「不显示的代价」排在常显层 ──────────
    // ⚠️ 账号涉及「谁能进系统」—— 做完不知道建了哪些账号是实际安全风险
    { label: '默认账号', written: a.writtenUsers ?? [], skipped: [] },
    // ⚠️ prompt 里专门警告过「写反是 400 倍的错」(1 箱被算成 0.05 斤),
    //    而预览里**也没有**这一组 —— 不显示的话客户没有任何一屏能当场发现写反了
    { label: '包装换算', written: a.writtenPackagingConversions ?? [], skipped: [] },
    // ⚠️ 模块开关决定客户看得见哪些功能, 不告知会让人以为「系统少了东西」
    { label: '模块开关', written: a.writtenModules ?? [], skipped: [] },
    // 以下四类预览里**有**, 做完不复述就违反「两者对得上」
    { label: '工序', written: a.writtenWorkProcesses ?? [], skipped: a.skippedWorkProcesses ?? [] },
    { label: '存放区', written: a.writtenWarehouses ?? [], skipped: a.skippedWarehouses ?? [] },
    // S4: 产线与每小时产能 (插单可行性只读这里); 跳过 = 同名产线已存在, 复用
    { label: '产线与设备产能', written: a.writtenProductionLines ?? [], skipped: a.skippedProductionLines ?? [] },
    { label: '产品工序链', written: a.writtenProductProcesses ?? [], skipped: [] },
    { label: '编码分段', written: a.writtenCodeSegments ?? [], skipped: a.skippedCodeSegments ?? [] },
    // ⚠️ 常显层: 缺临界限值的控制点 apply **整条不写**(库里三列非空, 且不许补默认值),
    //    skipped 那半就是「你说了但没写进去」—— 不显示的话客户永远不知道。
    { label: '关键控制点', written: a.writtenHaccpCheckpoints ?? [], skipped: a.skippedHaccpCheckpoints ?? [] },
  ]
})

/**
 * 折叠层:偏系统内部,客户当场未必看得懂,但**不能不给**——
 * 否则就成了「后端写了而没人说过」。默认收起,点开可看。
 */
const doneGroupsMore = computed(() => {
  const a = applied.value
  if (!a) return []
  return [
    { label: '告警规则', written: a.writtenAlerts ?? [], skipped: a.skippedAlerts ?? [] },
    { label: '盯指标', written: a.writtenMetricWatches ?? [], skipped: a.skippedMetricWatches ?? [] },
    { label: '预警阈值', written: a.writtenThresholds ?? [], skipped: [] },
    { label: '模块配置', written: a.writtenModuleConfigs ?? [], skipped: a.skippedModuleConfigs ?? [] },
    { label: '模块门禁', written: a.writtenModuleGates ?? [], skipped: a.skippedModuleGates ?? [] },
  ]
})

function errorMessage(error: unknown): string {
  const candidate = error as {
    message?: unknown
    response?: { data?: { message?: unknown } }
  }
  return String(candidate.response?.data?.message ?? candidate.message ?? '请求没有完成')
}

function clearFlowIssue() {
  flowIssue.value = null
  retryTask.value = null
}

function showFlowIssue(
  issue: FlowIssue,
  retry: () => Promise<void> | void,
) {
  flowIssue.value = issue
  retryTask.value = retry
}

async function retryFlowIssue() {
  const retry = retryTask.value
  if (!retry || flowRetrying.value) return
  flowRetrying.value = true
  clearFlowIssue()
  try {
    await retry()
  } finally {
    flowRetrying.value = false
  }
}

/**
 * ⚠️ 不能 push('/platform/factories') —— 那个路由**不存在**(2026-08-10 实测),
 *    跳过去是 404。回到本页的选择器才是有效去处。
 */
function backToPicker() {
  factoryId.value = ''
  factoryName.value = ''
  pickedId.value = ''
  sessionId.value = ''
  messages.value = []
  progress.value = null
  generated.value = null
  canonicalBlueprint.value = null
  capabilityCatalog.value = null
  capabilityCatalogError.value = ''
  applied.value = null
  previewDirty.value = false
  questionnaireAnswers.value = {}
  correctionDrafts.value = {}
  processRows.value = []
  confirmedProcessSignature.value = ''
  processDetailAnswers.value = {}
  productRows.value = []
  productPackaging.value = ''
  confirmedProductSignature.value = ''
  domainAnswers.value = {}
  userEditedSuggestedFields.clear()
  clearFlowIssue()
  step.value = 0
  loadFactories()
}

/**
 * 去段③ 复核。
 *
 * ⛔ 路由名/路径是**现查**的(`router/index.ts` 里 `path: 'factory-review'`,
 *    父级是 `/`)—— 本页上方那条注释记着同一个坑: 曾经 push 到一个
 *    **不存在**的路由, 跳过去是 404 而没有任何一层报错。
 */
function goReview() {
  router.push({ path: '/factory-review', query: { factoryId: factoryId.value } })
}

async function goWorkflow(productCode: string) {
  clearFlowIssue()
  if (authStore.isPlatform) {
    // Platform publication stays in its signed assembly namespace. The ordinary
    // factory canvas deliberately requires a factory account.
    step.value = 1
    await nextTick()
    document.querySelector('[data-testid="blueprint-drafts-panel"]')?.scrollIntoView({ behavior: 'smooth' })
    return
  }
  try {
    const productTypeId = await resolveProductTypeIdByCode(factoryId.value, productCode)
    if (!productTypeId) {
      const message = `找不到产品 ${productCode} 的真实档案，已停止打开 Workflow，避免进入错误画布。`
      ElMessage.error(message)
      showFlowIssue({
        title: '没有进入 Workflow 画布',
        detail: message,
        sideEffect: '没有发布 Workflow，也没有修改任何产品或工厂配置。请先确认产品档案已写入。',
        retryLabel: '重新定位产品 Workflow',
      }, () => goWorkflow(productCode))
      return
    }
    await router.push({
      name: 'ProductProcesses',
      query: { productTypeId, factoryId: factoryId.value },
    })
  } catch (error) {
    showFlowIssue({
      title: 'Workflow 入口暂时打不开',
      detail: errorMessage(error),
      sideEffect: '没有发布或修改 Workflow；当前写入回执仍保留。',
      retryLabel: '重试打开 Workflow',
    }, () => goWorkflow(productCode))
  }
}

async function loadFactories() {
  factoriesLoading.value = true
  try {
    factories.value = await listFactories()
  } finally {
    factoriesLoading.value = false
  }
}

async function hydrateSelectedFactoryName() {
  if (!factoryId.value || factoryName.value) return
  // 工厂管理员打开自己的直链时，登录上下文已经是名称真值。不要为了一个
  // 展示字段再请求平台工厂列表，否则权限正确的工厂账号也会看到平台权限报错。
  if (authStore.factoryId === factoryId.value && authStore.factoryName) {
    factoryName.value = authStore.factoryName
    return
  }
  try {
    factories.value = await listFactories()
    const selected = factories.value.find((factory) => factory.id === factoryId.value)
    if (selected?.name) factoryName.value = selected.name
  } catch {
    // 工厂 ID 仍是权威范围；名称回填失败只影响展示，不阻断访谈。
  }
}

async function chooseFactory() {
  const f = factories.value.find((x) => x.id === pickedId.value)
  if (!f) return
  factoryId.value = f.id
  factoryName.value = f.name
  await hydrateFactoryWorkspace()
}

async function scrollLog() {
  await nextTick()
  if (logRef.value) logRef.value.scrollTop = logRef.value.scrollHeight
}

async function refreshProgress() {
  if (!sessionId.value) return
  const res = await getFactoryBuildProgress(factoryId.value, sessionId.value)
  applyProgressSnapshot(res.data)
}

function applyProgressSnapshot(snapshot: FactoryBuildProgress) {
  const previousTotal = progress.value
    ? (progress.value.answered?.length ?? 0) + (progress.value.pending?.length ?? 0)
    : 0
  const nextTotal = (snapshot.answered?.length ?? 0) + (snapshot.pending?.length ?? 0)
  if (previousTotal > 0 && nextTotal !== previousTotal) {
    const delta = Math.abs(nextTotal - previousTotal)
    progressChangeNotice.value = nextTotal > previousTotal
      ? {
          title: `已按数量展开 ${delta} 个细节项`,
          detail: '进度百分比可能暂时下降，因为系统现在展示了刚刚确认数量后新增的真实问题；已答内容没有丢失。',
        }
      : {
          title: `已按更正收起 ${delta} 个细节项`,
          detail: '超出新数量范围的问题及答案已一并移除，范围内已经确认的内容继续保留。',
        }
  }
  progress.value = snapshot
  const previousProcessRows = new Map(processRows.value.map(row => [row.sequence, row]))
  const nextProcessRows = buildProcessSkeletonRows(snapshot)
  for (const row of nextProcessRows) {
    const previous = previousProcessRows.get(row.sequence)
    for (const field of ['name', 'inputMaterial', 'output'] as const) {
      const name = `step${row.sequence}.${field}`
      if (previous && userEditedSuggestedFields.has(name)
        && !Object.prototype.hasOwnProperty.call(snapshot.collected ?? {}, name)) {
        row[field] = previous[field]
      }
    }
  }
  processRows.value = nextProcessRows

  const previousProductRows = new Map(productRows.value.map(row => [row.sequence, row]))
  const nextProductRows = buildProductFlowRows(snapshot)
  for (const row of nextProductRows) {
    const previous = previousProductRows.get(row.sequence)
    for (const field of ['name', 'steps', 'routeDetails'] as const) {
      const name = `product${row.sequence}.${field}`
      if (previous && userEditedSuggestedFields.has(name)
        && !Object.prototype.hasOwnProperty.call(snapshot.collected ?? {}, name)) {
        row[field] = previous[field]
      }
    }
    const routeShapeName = `product${row.sequence}.routeShape`
    if (previous && userEditedSuggestedFields.has(routeShapeName)
      && !Object.prototype.hasOwnProperty.call(snapshot.collected ?? {}, routeShapeName)) {
      row.routeShape = [...previous.routeShape]
    }
  }
  productRows.value = nextProductRows
  routeTopologyDraft.value = buildRouteTopologyDraft(snapshot, routeTopologyDraft.value)

  if (Object.prototype.hasOwnProperty.call(snapshot.collected ?? {}, 'productSkus')) {
    productPackaging.value = snapshot.collected?.productSkus ?? ''
  } else if (!userEditedSuggestedFields.has('productSkus')) {
    productPackaging.value = snapshot.suggestions?.productSkus?.trim() ?? ''
  }
  const suggested = suggestedAnswersForItems(
    snapshot.pendingItems ?? [],
    snapshot.suggestions ?? {},
  )
  const processDetails: Record<string, string | string[]> = {}
  const domains: Record<string, string | string[]> = {}
  const questionnaireValues: Record<string, string | string[]> = {}
  for (const [name, value] of Object.entries(suggested)) {
    if (/^step\d+\.(?!name$|inputMaterial$|output$)/.test(name)) processDetails[name] = value
    else if (isSpecializedDomainField(name)) domains[name] = value
    else if (questionnaireNames.value.has(name)) questionnaireValues[name] = value
  }
  processDetailAnswers.value = mergeSuggestedAnswers(processDetailAnswers.value, processDetails)
  domainAnswers.value = mergeSuggestedAnswers(domainAnswers.value, domains)
  questionnaireAnswers.value = mergeSuggestedAnswers(questionnaireAnswers.value, questionnaireValues)
  confirmedProcessSignature.value = processSkeletonPersisted(snapshot)
    ? processSkeletonSignature(processRows.value)
    : ''
  confirmedProductSignature.value = productFlowPersisted(snapshot)
    ? productFlowSignature(productRows.value, productPackaging.value)
    : ''
}

function updateProcessRow(sequence: number, field: 'name' | 'inputMaterial' | 'output', value: string) {
  const row = processRows.value.find((item) => item.sequence === sequence)
  if (row) {
    row[field] = value
    userEditedSuggestedFields.add(`step${sequence}.${field}`)
  }
}

function setProcessDetailAnswer(name: string, value: string | string[]) {
  processDetailAnswers.value[name] = value
}

function updateProductRow(
  sequence: number,
  field: 'name' | 'steps' | 'routeShape' | 'routeDetails',
  value: string | string[],
) {
  const row = productRows.value.find((item) => item.sequence === sequence)
  if (row) {
    if (field === 'routeShape') row.routeShape = Array.isArray(value) ? [...value] : [value]
    else row[field] = Array.isArray(value) ? value.join('、') : value
    userEditedSuggestedFields.add(`product${sequence}.${field}`)
  }
}

function updateProductPackaging(value: string) {
  productPackaging.value = value
  userEditedSuggestedFields.add('productSkus')
}

function updateRouteTopologyDraft(value: RouteTopologyDraft) {
  routeTopologyDraft.value = value
}

function setDomainAnswer(name: string, value: string | string[]) {
  domainAnswers.value[name] = value
}

async function submitProductTable() {
  if (sending.value || !sessionId.value || productRows.value.length === 0) return
  const answers = productFlowAnswers(productRows.value, productPackaging.value)
  if (Object.keys(answers).length === 0) return
  const submittedRows = productRows.value.map((row) => ({ ...row }))
  const submittedPackaging = productPackaging.value.trim()
  clearFlowIssue()
  sending.value = true
  try {
    await submitStructuredAnswers(factoryId.value, sessionId.value, answers)
    messages.value.push({
      role: 'me',
      receipt: {
        kind: 'product-table',
        title: '我确认的成品、规格、工序链与路线事实',
        rows: submittedRows,
        packaging: submittedPackaging,
      },
    })
    await refreshProgress()
    confirmedProductSignature.value = productFlowSignature(productRows.value, productPackaging.value)
    const nextGroup = activeDomainConfig.value
    const next = progress.value?.pendingItems?.[0]
    messages.value.push({
      role: 'ai',
      text: nextGroup
        ? `成品、工序链与路线事实已经进入上下文。接下来用一张${nextGroup.title}卡继续确认。`
        : (next ? `成品表已经收好。接下来确认：${next.label}。${next.hint ?? ''}` : '成品、工序链和路线事实已经齐了，可以进入整厂推演和对账。'),
    })
  } catch (error) {
    showFlowIssue({
      title: '成品与工序链没有送达，表格内容仍然保留',
      detail: errorMessage(error),
      sideEffect: 'Agent 上下文没有变化，也没有生成或写入工厂配置。可以直接重试。',
      retryLabel: '重新发送成品与路线事实',
    }, submitProductTable)
  } finally {
    sending.value = false
    await scrollLog()
  }
}

async function submitRouteTopology() {
  const topology = routeTopologyDraft.value
  if (sending.value || !sessionId.value || !topology) return
  const answers = routeTopologyAnswers(topology)
  clearFlowIssue()
  sending.value = true
  try {
    await submitStructuredAnswers(factoryId.value, sessionId.value, answers)
    messages.value.push({
      role: 'me',
      receipt: {
        kind: 'field-card',
        title: `我确认的 ${topology.productName} 路线拓扑`,
        entries: [
          { label: '路线起点', value: topology.entry },
          { label: '路线终点', value: topology.terminal },
          { label: '逐条连线', value: `${topology.rows.length} 条已确认` },
        ],
      },
    })
    await refreshProgress()
    const nextTopology = routeTopologyDraft.value
    const next = progress.value?.pendingItems?.[0]
    messages.value.push({
      role: 'ai',
      text: nextTopology
        ? `${topology.productName} 的拓扑已经通过整体校验。接下来继续确认 ${nextTopology.productName}。`
        : (next ? `${topology.productName} 的复杂路线已经确定性编译为未发布预览。接下来确认：${next.label}。${next.hint ?? ''}`
          : `${topology.productName} 的复杂路线已经确定性编译为未发布预览；发布写入仍为 0。`),
    })
  } catch (error) {
    showFlowIssue({
      title: '路线拓扑没有通过整体校验，卡片内容仍然保留',
      detail: errorMessage(error),
      sideEffect: '这一批拓扑事实一项都没有保存，也没有生成、发布或写入 Workflow。请按提示修正后重试。',
      retryLabel: '重新校验路线拓扑',
    }, submitRouteTopology)
  } finally {
    sending.value = false
    await scrollLog()
  }
}

async function submitDomainCard() {
  const group = activeDomainConfig.value
  if (sending.value || !sessionId.value || !group) return
  const allowed = new Set(group.items.map((item) => item.name))
  const answers = Object.fromEntries(Object.entries(domainAnswers.value)
    .filter(([name, value]) => allowed.has(name)
      && (Array.isArray(value) ? value.length > 0 : value.trim().length > 0)))
  if (Object.keys(answers).length !== group.items.length) return
  clearFlowIssue()
  sending.value = true
  try {
    await submitStructuredAnswers(factoryId.value, sessionId.value, answers)
    messages.value.push({
      role: 'me',
      receipt: {
        kind: 'field-card',
        title: group.title,
        entries: choiceReceiptEntries(group.items, answers),
      },
    })
    for (const name of Object.keys(answers)) delete domainAnswers.value[name]
    await refreshProgress()
    const nextGroup = activeDomainConfig.value
    const next = progress.value?.pendingItems?.[0]
    messages.value.push({
      role: 'ai',
      text: nextGroup
        ? `${group.title}已经收好。接下来继续确认${nextGroup.title}。`
        : (next ? `${group.title}已经收好。接下来确认：${next.label}。${next.hint ?? ''}` : '这些专项配置已经齐了，可以进入整厂推演和对账。'),
    })
  } catch (error) {
    showFlowIssue({
      title: `${group.title}没有送达，刚才的选择仍然保留`,
      detail: errorMessage(error),
      sideEffect: 'Agent 上下文没有变化，也没有生成或写入工厂配置。可以直接重试。',
      retryLabel: `重新发送${group.title}`,
    }, submitDomainCard)
  } finally {
    sending.value = false
    await scrollLog()
  }
}

async function submitProcessDetails() {
  const group = activeProcessDetails.value
  if (sending.value || !sessionId.value || !group) return
  const allowed = new Set(group.items.map((item) => item.name))
  const answers = Object.fromEntries(Object.entries(processDetailAnswers.value)
    .filter(([name, value]) => allowed.has(name)
      && (Array.isArray(value) ? value.length > 0 : value.trim().length > 0)))
  if (Object.keys(answers).length === 0) return
  clearFlowIssue()
  sending.value = true
  try {
    await submitStructuredAnswers(factoryId.value, sessionId.value, answers)
    messages.value.push({
      role: 'me',
      receipt: {
        kind: 'field-card',
        title: `第 ${group.sequence} 道 · ${group.processName}细节`,
        entries: choiceReceiptEntries(group.items, answers),
      },
    })
    for (const name of Object.keys(answers)) delete processDetailAnswers.value[name]
    await refreshProgress()
    const nextGroup = activeProcessDetails.value
    const next = progress.value?.pendingItems?.[0]
    messages.value.push({
      role: 'ai',
      text: nextGroup
        ? `${group.processName}的这些细节已经收好。现在继续确认${nextGroup.processName}，仍然只展示这一道。`
        : (next ? `工序细节卡已经收好。接下来确认：${next.label}。${next.hint ?? ''}` : '工序细节已经齐了，可以进入整厂推演和对账。'),
    })
  } catch (error) {
    showFlowIssue({
      title: '工序细节卡没有送达，刚才的内容仍然保留',
      detail: errorMessage(error),
      sideEffect: 'Agent 上下文没有变化，也没有生成或写入工厂配置。可以直接重试。',
      retryLabel: '重新发送这张工序细节卡',
    }, submitProcessDetails)
  } finally {
    sending.value = false
    await scrollLog()
  }
}

async function submitProcessTable() {
  if (sending.value || !sessionId.value || processRows.value.length === 0) return
  const answers = processSkeletonAnswers(processRows.value)
  if (Object.keys(answers).length === 0) return
  const submittedRows = processRows.value.map((row) => ({ ...row }))
  clearFlowIssue()
  sending.value = true
  try {
    await submitStructuredAnswers(factoryId.value, sessionId.value, answers)
    messages.value.push({
      role: 'me',
      receipt: { kind: 'process-table', title: '我确认的样例生产流程', rows: submittedRows },
    })
    await refreshProgress()
    confirmedProcessSignature.value = processSkeletonSignature(processRows.value)
    const next = progress.value?.pendingItems?.[0]
    messages.value.push({
      role: 'ai',
      text: next
        ? `流程表已经进入上下文。接下来只确认表里没有覆盖的细节：${next.label}。${next.hint ?? ''}`
        : '这张流程表已经确认。骨架和细节都齐了，我会先反问对账，再让你看装配图。',
    })
  } catch (error) {
    showFlowIssue({
      title: '流程表没有送达，刚才的修改仍然保留',
      detail: errorMessage(error),
      sideEffect: 'Agent 上下文没有变化，也没有生成或写入工厂配置。可以直接重试。',
      retryLabel: '重新发送这张流程表',
    }, submitProcessTable)
  } finally {
    sending.value = false
    await scrollLog()
  }
}

function setQuestionnaireAnswer(item: PendingItem, value: unknown) {
  if (Array.isArray(value)) {
    questionnaireAnswers.value[item.name] = value.map(String)
    return
  }
  questionnaireAnswers.value[item.name] = String(value ?? '')
  if (item.name === 'stepCount') stepCountMoreOpen.value = false
}

function questionnaireSuggestionMatches(item: PendingItem) {
  const suggested = suggestedPrefillAnswers.value[item.name]
  const answer = questionnaireAnswers.value[item.name]
  if (suggested === undefined || answer === undefined) return false
  if (Array.isArray(suggested) || Array.isArray(answer)) {
    return JSON.stringify(Array.isArray(answer) ? answer : [answer])
      === JSON.stringify(Array.isArray(suggested) ? suggested : [suggested])
  }
  return answer.trim() === suggested.trim()
}

function openStepCountMore() {
  stepCountMoreOpen.value = true
  delete questionnaireAnswers.value.stepCount
}

function useStepCountMoreInQuestionnaire(item: PendingItem) {
  if (!stepCountMoreValue.value) return
  setQuestionnaireAnswer(item, String(stepCountMoreValue.value))
  stepCountMoreOpen.value = true
}

async function submitQuestionnaire() {
  if (sending.value || !sessionId.value) return
  const answers = questionnaireSubmissionAnswers.value
  if (Object.keys(answers).length === 0) return
  clearFlowIssue()
  sending.value = true
  try {
    await submitStructuredAnswers(factoryId.value, sessionId.value, answers)
    messages.value.push({
      role: 'me',
      receipt: {
        kind: 'choice-card',
        title: '我提交的基础信息卡',
        entries: choiceReceiptEntries(progress.value?.pendingItems ?? [], answers),
      },
    })
    questionnaireAnswers.value = {}
    await refreshProgress()
    const next = progress.value?.pendingItems?.[0]
    messages.value.push({
      role: 'ai',
      text: next
        ? `骨架已经收好。接下来我只追问细节：${next.label}。${next.hint ?? ''}`
        : '骨架和细节都齐了。我会先反问对账，再让你看装配图。',
    })
  } catch (error) {
    showFlowIssue({
      title: '信息卡没有送达，已选答案仍然保留',
      detail: errorMessage(error),
      sideEffect: '没有生成配置，也没有写入工厂。可以直接重试，或先修改已选答案。',
      retryLabel: '重新发送信息卡',
    }, submitQuestionnaire)
  } finally {
    sending.value = false
    await scrollLog()
  }
}

async function submitCorrection(issue: ReconciliationIssue) {
  if (!issue.targetKey) return
  const value = correctionDrafts.value[issue.id]?.trim()
  if (!value) return
  const saved = await answerStructured(issue.targetKey, value, `修正${issue.targetLabel ?? '这项'}：${value}`)
  if (saved) delete correctionDrafts.value[issue.id]
}

/**
 * 告警勾选行。
 *
 * ⚠️ 2026-08-11: `getInterviewDefaults` 此前**一个消费点都没有** —— 端点和 api
 * 函数都建好了, 界面从来没调过。所以那四段(模块/阈值/指标/权限)的现值一直没人看见。
 * 这次接的是 `alerts` 段, 其余几段的界面另算。
 */
interface AlertRow {
  alertType: string
  label: string
  detail: string
  needsAmount: boolean
  alreadyConfigured: boolean
  checked: boolean
  amountThreshold?: number
}

interface MetricRow {
  metricName: string
  label: string
  detail: string
  unit: string
  /** 「高于」/「低于」—— 服务端按指标本身给, 前端不自己判 */
  direction: string
  alreadyConfigured: boolean
  checked: boolean
  threshold?: number
}

const metricRows = ref<MetricRow[]>([])
const metricsNote = ref('')

const alertRows = ref<AlertRow[]>([])

/**
 * 没有分类的物料。⛔ 只显示, 不提供「一键补全」——
 * 按名字猜分类会把猜错的值一路写进台账筛选和 BOM 替代族, 而那两处都是静默失败。
 */
const unclassified = ref<{ count: number; note: string; rows: Array<{ code: string; name: string }> }>({
  count: 0,
  note: '',
  rows: [],
})
const alertsNote = ref('')

async function loadAlertCatalog() {
  if (!factoryId.value) return
  try {
    const res = await getInterviewDefaults(factoryId.value)
    // ⛔ 未分类物料要在 alerts 那段的 early-return **之前**读。
    //    我第一版写在了 return 后面 —— 后端没给 alerts 段时, 未分类就永远不显示,
    //    而它恰恰是「静默缺口」本身。兜底不能装在它该开的那扇门后面。
    const mw = (res.data ?? []).find((x) => x.key === 'metricWatches')
    if (mw) {
      metricsNote.value = mw.defaultsNote ?? ''
      metricRows.value = mw.rows.map((r): MetricRow => ({
        metricName: String(r.metricName ?? ''),
        label: String(r.label ?? ''),
        detail: String(r.detail ?? ''),
        unit: String(r.unit ?? ''),
        direction: String(r.direction ?? ''),
        alreadyConfigured: r.alreadyConfigured === true,
        // ⛔ 一律默认不勾: 勾了没填阈值是无效状态, 不替用户做这个决定
        checked: false,
        threshold: undefined,
      }))
    }
    const mc = (res.data ?? []).find((x) => x.key === 'materialClassification')
    if (mc) {
      unclassified.value = {
        count: mc.currentRows ?? 0,
        note: mc.defaultsNote ?? '',
        rows: mc.rows.map((r) => ({ code: String(r.code ?? ''), name: String(r.name ?? '') })),
      }
    }
    const sec = (res.data ?? []).find((x) => x.key === 'alerts')
    if (!sec) {
      // 后端没给这一段就不显示 —— ⛔ 不在前端编一份目录顶上,
      //    那样前后端两份清单会各自漂移。
      alertRows.value = []
      return
    }
    alertsNote.value = sec.defaultsNote ?? ''
    alertRows.value = sec.rows.map((r): AlertRow => {
      const already = r.alreadyConfigured === true
      return {
        alertType: String(r.alertType ?? ''),
        label: String(r.label ?? ''),
        detail: String(r.detail ?? ''),
        needsAmount: r.needsAmount === true,
        alreadyConfigured: already,
        // 金额类默认不勾: 勾了没填金额是无效状态, 不替用户做这个决定
        checked: false,
        amountThreshold: undefined,
      }
    })
  } catch {
    // 取不到目录就不显示这一段 —— 建厂其余部分照常可用
    alertRows.value = []
  }
}

/**
 * 勾选 → 落载荷。
 *
 * ⛔ 金额类没填金额的一律不下发: 后端会拒绝(校验里明确报错), 但更重要的是
 *    不让「勾了但没生效」这种状态悄悄溜过去。
 */
/**
 * ⛔ 没填阈值的一律不下发 —— 后端会拒(校验里明确报错), 但更重要的是
 *    不让「勾了但没生效」这种状态悄悄溜过去。与 collectAlerts 同一条判据。
 */
function collectMetricWatches() {
  return metricRows.value
    .filter((r) => r.checked && !r.alreadyConfigured)
    .filter((r) => r.threshold !== undefined && r.threshold !== null)
    .map((r) => ({ metricName: r.metricName, enabled: true, threshold: r.threshold }))
}

function collectAlerts() {
  return alertRows.value
    .filter((r) => r.checked && !r.alreadyConfigured)
    .filter((r) => !r.needsAmount || !!r.amountThreshold)
    .map((r) => ({
      alertType: r.alertType,
      enabled: true,
      ...(r.needsAmount ? { amountThreshold: r.amountThreshold } : {}),
    }))
}

async function start(forceRestart = false) {
  if (starting.value) return
  clearFlowIssue()
  processRows.value = []
  confirmedProcessSignature.value = ''
  processDetailAnswers.value = {}
  productRows.value = []
  productPackaging.value = ''
  confirmedProductSignature.value = ''
  domainAnswers.value = {}
  userEditedSuggestedFields.clear()
  starting.value = true
  try {
    // Every entry path (including choosing a factory in this page) must load the
    // server-owned defaults before the conversation starts. Otherwise the final
    // assembly silently omits modules, thresholds, alerts and metric watches.
    await loadAlertCatalog()
    const res = await startFactoryBuildConversation(factoryId.value, forceRestart)
    sessionId.value = res.data.sessionId
    if (res.data.resumed) draft.value = readStoredDraft()
    else {
      draft.value = ''
      clearStoredDraft()
    }
    await refreshProgress()
    const restored = restoreConversationMessages(
      res.data.history ?? [],
      progress.value?.pendingItems ?? [],
      progress.value?.answeredLabels ?? {},
    )
    messages.value = restored.length > 0 ? restored : [{ role: 'ai', text: res.data.message }]
    await scrollLog()
  } catch (error) {
    showFlowIssue({
      title: '还没有开始访谈',
      detail: errorMessage(error),
      sideEffect: '没有保存答案、生成配置或写入工厂。',
      retryLabel: '重新开始访谈',
    }, start)
  } finally {
    starting.value = false
  }
}

async function send() {
  const text = draft.value.trim()
  if (!text || sending.value) return
  await sendReply(text, createConversationRequestId())
}

async function sendReply(text: string, requestId: string) {
  if (!text || sending.value) return
  clearFlowIssue()
  const optimisticIndex = messages.value.length
  messages.value.push({ role: 'me', text })
  draft.value = ''
  sending.value = true
  await scrollLog()
  try {
    const res = await replyFactoryBuildConversation(
      factoryId.value, sessionId.value, text, requestId)
    const receiptEntries = extractionReceiptEntries(
      progress.value?.pendingItems ?? [],
      progress.value?.answeredLabels ?? {},
      res.data.fieldReceipts ?? [])
    if (receiptEntries.length > 0) {
      messages.value.push({
        role: 'ai',
        receipt: {
          kind: 'field-card',
          title: res.data.extractionOutcome === 'PARTIAL_SUCCESS'
            ? '已分别处理这句话里的每一项'
            : '这句话的字段处理结果',
          entries: receiptEntries,
        },
      })
    }
    const typedIssue = conversationOutcomeIssue(res.data)
    if (typedIssue) {
      if (messages.value[optimisticIndex]?.role === 'me'
        && messages.value[optimisticIndex]?.text === text) {
        messages.value.splice(optimisticIndex, 1)
      }
      draft.value = text
      showFlowIssue({
        title: typedIssue.title,
        detail: typedIssue.detail,
        sideEffect: typedIssue.sideEffect,
        retryLabel: typedIssue.retryLabel,
      }, typedIssue.retrySameRequest
        ? async () => { await sendReply(text, requestId) }
        : async () => {
          if (res.data.extractionOutcome === 'CONFLICT') await refreshProgress()
        })
      return
    }
    messages.value.push({ role: 'ai', text: res.data.message })
    await refreshProgress()
  } catch (error) {
    if (messages.value[optimisticIndex]?.role === 'me'
      && messages.value[optimisticIndex]?.text === text) {
      messages.value.splice(optimisticIndex, 1)
    }
    draft.value = text
    showFlowIssue({
      title: '这句话没有送达，输入已经放回编辑框',
      detail: errorMessage(error),
      sideEffect: '访谈答案未更新，也没有生成或写入任何工厂配置。',
      retryLabel: '重试发送这句话',
    }, async () => { await sendReply(text, requestId) })
  } finally {
    sending.value = false
    await scrollLog()
  }
}

/**
 * 结构化答题 —— 枚举题与「就按这个」都走这条。
 *
 * ⛔ 不走自由文本回复端点: 那条要过 LLM 抽取, 而对一个**已经选定的选项**跑抽取
 * 是纯负作用 —— 它可能把「有(部分)」归一成「有」, 而客户明明点的是前者。
 * 本端点先全量校验再写入, 任何一项不合规 → 400 且一项都不写。
 */
async function answerStructured(name: string, value: string | string[], echo: string): Promise<boolean> {
  if (sending.value || !sessionId.value) return false
  clearFlowIssue()
  const optimisticIndex = messages.value.length
  messages.value.push({ role: 'me', text: echo })
  sending.value = true
  pickedOptions.value = []
  await scrollLog()
  try {
    await submitStructuredAnswers(factoryId.value, sessionId.value, { [name]: value })
    await refreshProgress()
    const next = progress.value?.currentDecision
    messages.value.push({
      role: 'ai',
      text: next
        ? (next.state === 'UNRESOLVED'
          ? `其他问题已经问完。现在回到待补的“${next.label}”；没有明确答案前不会确认完整方案。`
          : `好的。接下来只确认“${next.label}”：${next.question}`)
        : (progress.value?.readyForGenerate ? '都问齐了，可以生成装配预览。' : '这项已经记录，但完整方案还不能确认。'),
    })
    return true
  } catch (error) {
    if (messages.value[optimisticIndex]?.role === 'me'
      && messages.value[optimisticIndex]?.text === echo) {
      messages.value.splice(optimisticIndex, 1)
    }
    showFlowIssue({
      title: '这个答案没有保存',
      detail: errorMessage(error),
      sideEffect: '当前题仍保持未回答，没有生成或写入工厂配置。',
      retryLabel: '重试保存这个答案',
    }, async () => {
      await answerStructured(name, value, echo)
    })
    return false
  } finally {
    sending.value = false
    await scrollLog()
  }
}

function useDecisionExample(example: string) {
  draft.value = example
}

function useDecisionRecommendation(recommendation: string) {
  const decision = currentDecision.value
  if (!decision) return
  void answerStructured(decision.field, recommendation, `${decision.label}：${recommendation}`)
}

function confirmMultiDecision(values: string[]) {
  const decision = currentDecision.value
  if (!decision || !values.length) return
  void answerStructured(decision.field, values, `${decision.label}：${values.join('、')}`)
}

function deferCurrentDecision() {
  const decision = currentDecision.value
  if (!decision?.allowUnknown) return
  void answerStructured(
    decision.field,
    decision.unknownAnswerValue,
    `${decision.label}：暂时不确定，留作待补充`,
  )
}

/** 单选立刻提交; 多选先记下, 等客户点「确定」。 */
function pickOption(opt: string) {
  const it = currentItem.value
  if (!it) return
  if (it.name === 'stepCount' && opt === STEP_COUNT_MORE_OPTION) {
    stepCountMoreOpen.value = true
    return
  }
  if (!it.multiSelect) {
    void answerStructured(it.name, opt, opt)
    return
  }
  const i = pickedOptions.value.indexOf(opt)
  if (i >= 0) pickedOptions.value.splice(i, 1)
  else pickedOptions.value.push(opt)
}

function submitStepCountMore() {
  const it = currentItem.value
  const count = stepCountMoreValue.value
  if (!it || it.name !== 'stepCount' || !count) return
  void answerStructured(it.name, String(count), `${count} 道工序`)
}

function submitPicked() {
  const it = currentItem.value
  if (!it || pickedOptions.value.length === 0) return
  void answerStructured(it.name, [...pickedOptions.value], pickedOptions.value.join('、'))
}

/**
 * 采纳平台建议。
 *
 * ⛔ 必须是当前客户**点了**才提交；系统不能把平台建议自动当成当前工厂事实。
 */
function adopt(it: PendingItem) {
  if (!it.suggestion) return
  void answerStructured(it.name, it.suggestion, it.suggestion)
}

/**
 * 预览与落库**用同一份载荷**。
 *
 * ⛔ 两处各拼一份的话, 预览看的是 A、落库写的是 B, 而界面上完全一样 ——
 *    这与"预览和落库各算一份差异"是同一类缺陷, 只是换了个位置。
 */
function buildApplyPayload() {
  const g = generated.value
  const suggestedData = g?.suggestedData ?? { productTypes: [], materialTypes: [], conversionRates: [] }
  return {
    industryCode: g?.industryCode ?? '',
    industryName: g?.industryName ?? '',
    schemas: g?.schemas ?? [],
    // 生成完成时已经把目录勾选合进 suggestedData；此后细节编辑器直接改这一份。
    // ⛔ 不在这里再次覆盖 alerts/metricWatches，否则用户在预览里的修改会静默丢失。
    suggestedData: {
      ...suggestedData,
      conversionRates: suggestedData.conversionRates.map(conversion => ({
        fromCode: conversion.fromCode,
        toCode: conversion.toCode,
        rate: conversion.rate,
        note: conversion.note,
      })),
    },
    // 🔴 「客户说了而生成端没配出来」的那几条必须跟着一起提交 ——
    //    它随 apply 存进快照, 复核向导才看得到。
    //    ⛔ 不带的话它只活在建厂这一屏: 客户第二天来复核, 他说的三个存放区
    //    只建了一个这件事**在复核界面上一个字都没有**(2026-08-17 实测)。
    notBuilt: g?.notBuilt ?? [],
    // This is an explicit selection intent only. The authoritative draft
    // endpoint derives its own dependency changes; no client snapshot/pin is
    // created and no formal/business write happens here.
    draftCapabilityIntents: [...draftCapabilityIntentSelection.value],
    overwrite: overwrite.value,
  }
}

function markPreviewDirty() {
  previewDirty.value = true
}

function replaceGeneratedPayload(payload: { schemas: EntitySchema[]; suggestedData: SuggestedData }) {
  if (!generated.value) return
  generated.value = {
    ...generated.value,
    schemas: payload.schemas,
    suggestedData: payload.suggestedData,
  }
  markPreviewDirty()
}

const operationKeys = new Map<string, string>()

function operationKey(operation: string): string {
  const existing = operationKeys.get(operation)
  if (existing) return existing
  const suffix = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
  const key = `factory-blueprint-${operation.toLowerCase()}-${suffix}`
  operationKeys.set(operation, key)
  return key
}

function operationPayload(operation: string) {
  const blueprint = canonicalBlueprint.value
  if (!blueprint) throw new Error('权威 Factory Blueprint 尚未加载')
  return { idempotencyKey: operationKey(operation), expectedRowVersion: blueprint.rowVersion }
}

async function saveAuthoritativeDraft(): Promise<CanonicalFactoryBlueprint> {
  if (!generated.value?.schemas || !generated.value.suggestedData) {
    throw new Error('生成配置不完整，无法保存权威蓝图')
  }
  const canonicalSourceCustomerSupplied = canonicalBlueprint.value?.customerSupplied ?? undefined
  const authoritativeSessionId = canonicalViewMode.value
    ? canonicalBlueprint.value?.sessionId ?? undefined
    : sessionId.value || undefined
  const authoritativeCustomerSupplied = canonicalViewMode.value
    ? canonicalSourceCustomerSupplied
    : progress.value?.collected.customerSupplied ?? canonicalSourceCustomerSupplied
  const response = await saveCanonicalFactoryBlueprintDraft(factoryId.value, {
    factoryName: factoryName.value || undefined,
    sessionId: authoritativeSessionId,
    customerSupplied: authoritativeCustomerSupplied,
    conversionEvidence: generated.value.suggestedData.conversionRates.map(conversion => ({
      fromCode: conversion.fromCode,
      toCode: conversion.toCode,
      provenance: conversion.provenance ?? 'AI_INFERRED',
    })),
    idempotencyKey: operationKey('DRAFT'),
    expectedRowVersion: canonicalBlueprint.value?.rowVersion,
    payload: buildApplyPayload(),
  })
  canonicalBlueprint.value = response.data
  diffRows.value = response.data.plannedChanges
  await hydrateCapabilityCatalog(response.data.capabilityCatalogVersion)
  operationKeys.delete('DRAFT')
  return response.data
}

async function refreshPreview(): Promise<boolean> {
  if (!generated.value?.schemas || !generated.value.suggestedData || previewing.value) return false
  clearFlowIssue()
  previewing.value = true
  try {
    await saveAuthoritativeDraft()
    previewDirty.value = false
    ElMessage.success('权威蓝图草稿已保存并从服务端回读')
    return true
  } catch (error) {
    previewDirty.value = true
    showFlowIssue({
      title: '修改后的配置没有通过服务端校验',
      detail: errorMessage(error),
      sideEffect: '没有写入工厂；修改内容仍然保留，可以就地修正后再次校验。',
      retryLabel: '重新校验全部细节',
    }, retrySavedPreview)
    return false
  } finally {
    previewing.value = false
  }
}

async function retrySavedPreview() {
  await saveAndEnterFactoryPreview(refreshPreview, () => {
    proposalNeedsCorrection.value = false
    step.value = 1
  })
}

async function generate() {
  if (!progress.value?.assembledDescription) return
  clearFlowIssue()
  generating.value = true
  proposalNeedsCorrection.value = false
  let generationReceived = false
  try {
    const res = await aiInitializeFactory(factoryId.value, {
      factoryDescription: progress.value.assembledDescription,
      factoryName: factoryName.value || undefined,
      includeBusinessData: true,
    })
    if (res.data.suggestedData) {
      res.data.suggestedData = conserveFactoryAssemblyIntent(res.data.suggestedData, {
        alerts: collectAlerts(),
        metricWatches: collectMetricWatches(),
      })
    }
    generated.value = res.data
    generationReceived = true
    // Rule 1: 进预览前先把现值取回来, 才谈得上「现值 → 确认后的值」
    currentLoadFailed.value = false
    try {
      current.value = await fetchCurrentFactoryConfig(factoryId.value)
    } catch {
      current.value = null
      currentLoadFailed.value = true
    }
    // 保存后只使用 canonical API 回读的计划和能力，不保留本地蓝图替代品。
    await saveAuthoritativeDraft()
    previewDirty.value = false
    // A failed attempt may have left a persistent global error toast. Once the
    // same preserved input succeeds, the preview must not keep presenting that
    // stale failure as if the current result were invalid.
    ElMessage.closeAll()
    step.value = 1
  } catch (error) {
    proposalNeedsCorrection.value = generationReceived
    const recovery = factoryPreviewRecovery({
      generationReceived, retryGeneration: generate, retrySave: retrySavedPreview,
    })
    showFlowIssue({
      title: recovery.title,
      detail: generationFailureDetail(errorMessage(error)),
      sideEffect: recovery.sideEffect,
      retryLabel: recovery.retryLabel,
    }, recovery.retry)
  } finally {
    generating.value = false
  }
}

async function confirmApply() {
  const blueprint = canonicalBlueprint.value
  if (!blueprint || blueprint.lifecycleState !== 'PUBLISHED') return
  // Rule 2: 确认弹窗必须带身份与影响面, 不能只问「确定吗」
  const detail = overwriteCount.value > 0
    ? `将新建 ${createCount.value} 项，并覆盖 ${overwriteCount.value} 项已有配置。`
    : `将新建 ${createCount.value} 项，跳过 ${skipCount.value} 项已存在配置。`
  try {
    await ElMessageBox.confirm(
      detail,
      `写入工厂配置 — ${factoryName.value || factoryId.value}（${factoryId.value}）`,
      { type: overwriteCount.value > 0 ? 'warning' : 'info', confirmButtonText: '确认写入', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }
  applying.value = true
  clearFlowIssue()
  try {
    const res = await applyCanonicalFactoryBlueprint(
      factoryId.value, blueprint.id, operationPayload('APPLY'))
    canonicalBlueprint.value = res.data
    const receiptResult = res.data.latestReceipt?.result ?? {}
    applied.value = {
      ...(receiptResult as Partial<ApplyAIInitResult>),
      success: res.data.latestReceipt?.status === 'COMPLETED',
      factoryId: factoryId.value,
      message: res.data.latestReceipt?.status === 'PARTIAL_FAILED'
        ? '部分模块需要按回执复核，已发布蓝图保持不变'
        : '工厂配置已按已发布蓝图应用',
    } as ApplyAIInitResult
    operationKeys.delete('APPLY')
    step.value = 2
    if (applied.value.success) {
      ElMessage.success(applied.value.message)
    } else {
      ElMessage.warning(applied.value.message)
    }
  } catch (error) {
    showFlowIssue({
      title: '没有拿到工厂配置的成功回执',
      detail: errorMessage(error),
      sideEffect: '无法仅凭本次失败判断最终状态；当前预览会保留，重试前会再次显示目标工厂和影响数量，并由后端幂等规则防重复。',
      retryLabel: '重新确认并重试写入',
    }, confirmApply)
  } finally {
    // ⛔ 不 catch 错误自造文案: request.ts 已把后端 message 原样 sticky 弹出
    //    (防呆四位一体 b/c)。这里再包一层 "操作失败" 会把具体原因盖掉。
    applying.value = false
  }
}

async function validateBlueprint() {
  const blueprint = canonicalBlueprint.value
  if (!blueprint || previewDirty.value) return
  blueprintOperation.value = 'VALIDATE'
  clearFlowIssue()
  try {
    const response = await validateCanonicalFactoryBlueprint(
      factoryId.value, blueprint.id, operationPayload('VALIDATE'))
    canonicalBlueprint.value = response.data
    diffRows.value = response.data.plannedChanges
    operationKeys.delete('VALIDATE')
    if (response.data.validation?.valid) {
      ElMessage.success('服务端依赖验证通过；业务配置写入为 0')
    } else {
      ElMessage.warning(`发现 ${response.data.validation?.blockingIssues.length ?? 0} 个依赖阻塞项，尚未允许模拟`)
    }
  } catch (error) {
    showFlowIssue({
      title: '蓝图验证没有完成', detail: errorMessage(error),
      sideEffect: '业务配置写入为 0；当前蓝图和输入保持不变。', retryLabel: '重试服务端验证',
    }, validateBlueprint)
  } finally {
    blueprintOperation.value = ''
  }
}

async function simulateBlueprint() {
  const blueprint = canonicalBlueprint.value
  if (!blueprint || blueprint.lifecycleState !== 'VALIDATED') return
  blueprintOperation.value = 'SIMULATE'
  clearFlowIssue()
  try {
    const response = await simulateCanonicalFactoryBlueprint(
      factoryId.value, blueprint.id, operationPayload('SIMULATE'))
    canonicalBlueprint.value = response.data
    diffRows.value = response.data.plannedChanges
    operationKeys.delete('SIMULATE')
    if (response.data.simulation?.safe) {
      ElMessage.success('零写配置预检通过；Workflow 引擎尚未仿真或发布')
    } else {
      ElMessage.warning(`配置预检发现 ${response.data.simulation?.blockingIssues.length ?? 0} 个阻断项`)
    }
  } catch (error) {
    showFlowIssue({
      title: '蓝图模拟没有完成', detail: errorMessage(error),
      sideEffect: '业务配置写入为 0；不会自动发布蓝图或 Workflow。', retryLabel: '重试零写模拟',
    }, simulateBlueprint)
  } finally {
    blueprintOperation.value = ''
  }
}

async function publishBlueprint() {
  const blueprint = canonicalBlueprint.value
  if (!blueprint || !blueprint.simulation?.safe) return
  try {
    await ElMessageBox.confirm(
      `将发布 ${blueprint.factoryName} 的蓝图 v${blueprint.version}。本动作只固化不可变快照，不应用配置、不发布 Workflow。`,
      `发布 Factory Blueprint — ${blueprint.factoryId}`,
      { type: 'warning', confirmButtonText: '确认发布蓝图', cancelButtonText: '再看看' },
    )
  } catch {
    return
  }
  blueprintOperation.value = 'PUBLISH'
  clearFlowIssue()
  try {
    const response = await publishCanonicalFactoryBlueprint(
      factoryId.value, blueprint.id, operationPayload('PUBLISH'))
    canonicalBlueprint.value = response.data
    operationKeys.delete('PUBLISH')
    ElMessage.success('蓝图不可变版本已发布；工厂配置与 Workflow 尚未应用')
  } catch (error) {
    showFlowIssue({
      title: '蓝图版本没有确认发布', detail: errorMessage(error),
      sideEffect: '没有应用工厂配置，也没有发布 Workflow。', retryLabel: '重新确认发布蓝图',
    }, publishBlueprint)
  } finally {
    blueprintOperation.value = ''
  }
}

// 已回答的项要改, 必须说成「更正」(含「改成」)服务端才会把它当候选; 这里替用户把这句开头写好。
function startEditingFact(fact: { label: string }) {
  draft.value = `把「${fact.label}」改成`
}

async function confirmCarried(fields?: string[]) {
  if (!factoryId.value || !sessionId.value || sending.value) return
  clearFlowIssue()
  sending.value = true
  try {
    await confirmCarriedFacts(factoryId.value, sessionId.value, fields)
    await refreshProgress()
  } catch (error) {
    showFlowIssue({
      title: '上一版的值没有确认成功，仍保持待确认',
      detail: errorMessage(error),
      sideEffect: '这些值仍是「待确认」，没有写入已确认资料。',
      retryLabel: '重新确认',
    }, () => confirmCarried(fields))
  } finally {
    sending.value = false
  }
}

async function restart() {
  const answered = progress.value?.answered?.length ?? 0
  const remaining = progress.value?.remaining ?? 0
  if (sessionId.value || canonicalBlueprint.value) {
    try {
      await ElMessageBox.confirm(
        `当前访谈已确认 ${answered} 项、还剩 ${remaining} 项。重新开始会创建新访谈版本，当前未完成会话将不再继续；已保存的蓝图不会被删除。`,
        `重新开始 ${factoryName.value || factoryId.value} 的建厂访谈？`,
        {
          type: 'warning',
          confirmButtonText: '确认创建新访谈',
          cancelButtonText: '继续当前访谈',
          distinguishCancelAndClose: true,
        },
      )
    } catch {
      return
    }
  }
  draft.value = ''
  clearStoredDraft()
  sessionId.value = ''
  messages.value = []
  progress.value = null
  generated.value = null
  applied.value = null
  previewDirty.value = false
  current.value = null
  overwrite.value = false
  questionnaireAnswers.value = {}
  correctionDrafts.value = {}
  processRows.value = []
  confirmedProcessSignature.value = ''
  processDetailAnswers.value = {}
  productRows.value = []
  productPackaging.value = ''
  confirmedProductSignature.value = ''
  domainAnswers.value = {}
  userEditedSuggestedFields.clear()
  stepCountMoreOpen.value = false
  stepCountMoreValue.value = undefined
  progressChangeNotice.value = null
  canonicalViewMode.value = false
  canonicalViewReady.value = false
  canonicalViewLoading.value = false
  recoveredConversation.value = null
  clearFlowIssue()
  operationKeys.clear()
  step.value = 0
  await start(true)
}

async function returnToConversation() {
  if (canonicalViewMode.value && recoveredConversation.value) {
    await returnToRecoveredConversation()
    return
  }
  if (canResumeConversation.value) {
    step.value = 0
    return
  }
  await restart()
}

async function viewSavedCanonical() {
  const expectedSessionId = canonicalBlueprint.value?.sessionId?.trim()
  const expectedBlueprintId = canonicalBlueprint.value?.id?.trim()
  const expectedFactoryId = canonicalBlueprint.value?.factoryId?.trim()
  if (!expectedBlueprintId || !expectedFactoryId || !expectedSessionId || canonicalViewLoading.value) return
  canonicalViewMode.value = true
  canonicalViewReady.value = false
  canonicalViewLoading.value = true
  step.value = 1
  clearFlowIssue()
  await hydrateCanonicalRecoveryView({
    expectedBlueprintId,
    expectedFactoryId,
    expectedSessionId,
    hydrate: async () => {
      const hydrated = await hydrateCanonicalBlueprint(expectedSessionId, expectedBlueprintId, expectedFactoryId)
      return {
        canonicalBlueprintId: hydrated?.id,
        canonicalFactoryId: hydrated?.factoryId,
        canonicalSessionId: hydrated?.sessionId,
      }
    },
    onStart: () => {},
    onReady: () => {
      canonicalViewReady.value = true
      step.value = 1
      clearFlowIssue()
    },
    onError: (error) => {
      canonicalViewMode.value = false
      canonicalViewReady.value = false
      step.value = 0
      showFlowIssue({
        title: '已保存蓝图尚未完成身份回读',
        detail: errorMessage(error),
        sideEffect: '当前访谈没有被覆盖；蓝图编辑与保存仍保持关闭。',
        retryLabel: '重试查看已保存蓝图',
      }, viewSavedCanonical)
    },
  })
  canonicalViewLoading.value = false
}

async function returnToRecoveredConversation() {
  const recovered = recoveredConversation.value
  if (!recovered || canonicalViewLoading.value) return
  canonicalViewReady.value = false
  canonicalViewLoading.value = true
  step.value = 1
  await restoreRecoveredConversationView({
    expectedFactoryId: factoryId.value,
    expectedSessionId: recovered.sessionId,
    onStart: () => {},
    load: async () => (await getFactoryBuildProgress(factoryId.value, recovered.sessionId)).data,
    onRestored: (restoredProgress) => {
      sessionId.value = recovered.sessionId
      progress.value = null
      applyProgressSnapshot(restoredProgress)
      const restored = restoreConversationMessages(
        restoredProgress.history ?? [],
        restoredProgress.pendingItems ?? [],
        restoredProgress.answeredLabels ?? {},
      )
      messages.value = restored.length > 0 ? restored : [{
        role: 'ai',
        text: restoredProgress.status === 'COMPLETED'
          ? '已返回原来的已完成访谈；答案仍在服务端，可以继续查看或开始推演。'
          : '已返回原来的建厂访谈，可以从服务端进度继续。',
      }]
      draft.value = recovered.draft
      canonicalViewMode.value = false
      canonicalViewReady.value = false
      step.value = 0
      clearFlowIssue()
      ElMessage.closeAll()
    },
    onError: (error) => {
      // The canonical view is still the last identity-checked view, so keep it
      // open while offering a retry instead of falling back to another session.
      canonicalViewMode.value = true
      canonicalViewReady.value = true
      step.value = 1
      showFlowIssue({
        title: '原访谈没有完成身份回读',
        detail: errorMessage(error),
        sideEffect: '已保存蓝图仍保持当前内容，没有创建新访谈或覆盖蓝图来源。',
        retryLabel: '重试返回原访谈',
      }, returnToRecoveredConversation)
    },
  })
  canonicalViewLoading.value = false
}

async function hydrateCanonicalBlueprint(
  expectedSessionId?: string,
  expectedBlueprintId?: string,
  expectedFactoryId?: string,
): Promise<CanonicalFactoryBlueprint | null> {
  const strictExpectedSessionId = expectedSessionId?.trim()
  const strictExpectedBlueprintId = expectedBlueprintId?.trim()
  const strictExpectedFactoryId = expectedFactoryId?.trim()
  try {
    const response = await getCurrentCanonicalFactoryBlueprint(factoryId.value)
    const candidate = response.data
    if (strictExpectedSessionId) {
      if (!candidate
        || candidate.id !== strictExpectedBlueprintId
        || candidate.factoryId !== strictExpectedFactoryId
        || candidate.factoryId !== factoryId.value
        || candidate.sessionId !== strictExpectedSessionId) {
        throw new Error('已保存蓝图的工厂或访谈身份与当前查看目标不一致')
      }
    }
    // Strict recovery validates the canonical GET before changing any page state.
    canonicalBlueprint.value = candidate
    if (candidate) {
      diffRows.value = candidate.plannedChanges
      const sourcePayload = candidate.sourcePayload
      const provenanceByPair = new Map(
        candidate.conversionEvidence.map(item => [
          `${item.fromCode.trim()}→${item.toCode.trim()}`,
          item.provenance,
        ]),
      )
      generated.value = {
        success: true,
        industryCode: sourcePayload.industryCode,
        industryName: sourcePayload.industryName,
        schemas: sourcePayload.schemas,
        suggestedData: {
          ...sourcePayload.suggestedData,
          conversionRates: sourcePayload.suggestedData.conversionRates.map(conversion => ({
            ...conversion,
            provenance: provenanceByPair.get(
              `${conversion.fromCode.trim()}→${conversion.toCode.trim()}`,
            ) ?? 'AI_INFERRED',
          })),
        },
        notBuilt: sourcePayload.notBuilt,
        aiSummary: '已从服务端权威蓝图恢复上次保存的专业配置。',
      }
      draftCapabilityIntentSelection.value = [...(sourcePayload.draftCapabilityIntents ?? [])]
      overwrite.value = sourcePayload.overwrite
      sessionId.value = candidate.sessionId ?? ''
      if (strictExpectedSessionId) {
        // A canonical session can belong to another typed actor. Keep only the
        // saved blueprint source here; never fabricate or probe its interview.
        progress.value = null
        messages.value = []
      } else if (sessionId.value) {
        try {
          await refreshProgress()
          const restored = restoreConversationMessages(
            progress.value?.history ?? [],
            progress.value?.pendingItems ?? [],
            progress.value?.answeredLabels ?? {},
          )
          messages.value = restored.length > 0 ? restored : [{
            role: 'ai',
            text: canResumeConversation.value
              ? '已恢复这次访谈的服务端进度，可以继续修改未完成的回答。'
              : '原访谈已经结束；专业配置已完整恢复。若要重新访谈，请开始新的访谈版本。',
          }]
        } catch (error) {
          showFlowIssue({
            title: '蓝图已恢复，但原访谈进度读取失败',
            detail: errorMessage(error),
            sideEffect: '权威草稿和专业配置仍然保留；可以直接复核配置，或开始新的访谈版本。',
            retryLabel: '重试读取原访谈进度',
          }, async () => { await hydrateCanonicalBlueprint() })
        }
      }
      previewDirty.value = false
      step.value = 1
      return candidate
    }
    return null
  } catch (error) {
    if (strictExpectedSessionId) throw error
    canonicalBlueprint.value = null
    showFlowIssue({
      title: '权威 Factory Blueprint 读取失败', detail: errorMessage(error),
      sideEffect: '不会退回浏览器本地摘要；访谈输入仍可继续，保存蓝图前会再次连接服务端。',
      retryLabel: '重试读取权威蓝图',
    }, async () => { await hydrateCanonicalBlueprint() })
    return null
  }
}

async function hydrateRecoverableConversation(): Promise<'recovered' | 'empty' | 'error'> {
  try {
    const response = await getRecoverableFactoryBuildConversation(factoryId.value)
    const recoverable = response.data
    if (!recoverable) return 'empty'
    const blueprintSessionId = canonicalBlueprint.value?.sessionId ?? ''
    const shouldRecover = recoverable.status === 'ACTIVE'
      || (recoverable.status === 'COMPLETED'
        && recoverable.sessionId !== blueprintSessionId)
    if (!shouldRecover) return 'empty'

    await loadAlertCatalog()
    const recoveredDraft = recoverable.status === 'ACTIVE' ? readStoredDraft() : ''
    recoveredConversation.value = {
      sessionId: recoverable.sessionId,
      status: recoverable.status,
      draft: recoveredDraft,
    }
    sessionId.value = recoverable.sessionId
    progress.value = null
    applyProgressSnapshot(recoverable)
    const restored = restoreConversationMessages(
      recoverable.history ?? [],
      recoverable.pendingItems ?? [],
      recoverable.answeredLabels ?? {},
    )
    messages.value = restored.length > 0 ? restored : [{
      role: 'ai',
      text: recoverable.status === 'COMPLETED'
        ? '已恢复刚刚完成、尚未生成蓝图的访谈。答案仍在服务端，可以直接推演整厂流程。'
        : '已恢复尚未完成的建厂访谈，可以从上次停下的位置继续。',
    }]
    // 旧蓝图可能引用已经清理掉的会话，HTTP 拦截器会先弹出全局 404。
    // 既然服务端已经成功恢复了更新的权威会话，就关闭过期 toast 和旧蓝图的
    // 内联读取错误，避免它们在 390px 下压住当前问题或继续泄露旧 sessionId。
    clearFlowIssue()
    ElMessage.closeAll()
    draft.value = recoveredDraft
    step.value = 0
    return 'recovered'
  } catch (error) {
    showFlowIssue({
      title: '无法确认是否有待恢复的建厂访谈',
      detail: errorMessage(error),
      sideEffect: '没有创建新会话，也没有覆盖现有蓝图。为避免丢失已完成答案，暂不自动重新开始。',
      retryLabel: '重试查找待恢复访谈',
    }, async () => { await hydrateRecoverableConversation() })
    return 'error'
  }
}

async function hydrateCapabilityCatalog(catalogVersion?: string) {
  capabilityCatalogError.value = ''
  try {
    const response = await getCanonicalFactoryCapabilityCatalog(factoryId.value, catalogVersion)
    capabilityCatalog.value = response.data
    draftCapabilityIntentSelection.value = normalizeDraftCapabilityIntents(
      draftCapabilityIntentSelection.value,
      response.data,
    )
  } catch (error) {
    capabilityCatalog.value = null
    capabilityCatalogError.value = errorMessage(error)
  }
}

async function hydrateFactoryWorkspace() {
  await hydrateCanonicalBlueprint()
  await hydrateCapabilityCatalog(canonicalBlueprint.value?.capabilityCatalogVersion)
  const recovery = await hydrateRecoverableConversation()
  if (!canonicalBlueprint.value && recovery === 'empty') await start()
}

onMounted(async () => {
  // 工厂总管理员从菜单直接进入时，路由不会携带 factoryId；其登录上下文
  // 已经限定了唯一工厂，应直接进入自己的蓝图。平台管理员即使带有测试工厂
  // 上下文也仍需保留工厂选择页，不能按 factoryId 一刀切。
  if (!factoryId.value
    && authStore.currentRole === 'factory_super_admin'
    && authStore.factoryId) {
    factoryId.value = authStore.factoryId
    factoryName.value = authStore.factoryName || authStore.factoryId
  }
  if (factoryId.value) {
    await hydrateSelectedFactoryName()
    await hydrateFactoryWorkspace()
  }
  else await loadFactories()
})
</script>

<style lang="scss" scoped>
.reused-list { margin: 6px 0 0; padding-left: 18px; }
.page-container {
  --assembly-ease-out: cubic-bezier(0.23, 1, 0.32, 1);
  padding: 20px;
}

/* 本页已经是连续 Agent 对话工作台；全局客服浮层会遮挡右侧进度与确认控件。 */
:global(.inline-cs) { display: none !important; }

.preview-validation {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin: 16px 0;
  padding: 14px 16px;
  border: 1px solid #a8d4c9;
  border-radius: 10px;
  background: #f1faf8;
}
.preview-validation.dirty { border-color: #efc06f; background: #fff9ed; }
.preview-validation.draft { border-color: #a9c4df; background: #f3f8fd; }
.preview-validation.blocked { border-color: #e7a5a5; background: #fff5f5; }
.preview-validation strong { color: #1a2332; }
.preview-validation p { margin: 4px 0 0; color: #7a8599; font-size: 13px; }
.validation-blockers {
  display: grid;
  grid-template-columns: minmax(190px, .65fr) minmax(320px, 1.35fr);
  gap: 20px;
  margin: -4px 0 16px;
  padding: 16px;
  border: 1px solid #e7a5a5;
  border-radius: 10px;
  background: #fff8f8;
}
.validation-blockers > div strong,
.validation-blockers > div span,
.validation-blockers li strong,
.validation-blockers li span { display: block; }
.validation-blockers > div span,
.validation-blockers li span { margin-top: 4px; color: #7a6060; font-size: 12px; line-height: 1.55; }
.validation-blockers ul { margin: 0; padding-left: 20px; }
.validation-blockers li + li { margin-top: 10px; }

/* 要开哪些提醒 —— 勾选表 */
.unclass-note { line-height: 1.6; }
.unclass-list { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 8px; }
.unclass-item { font-size: 12px; }
.unclass-item em { color: var(--el-text-color-secondary); font-style: normal; margin-left: 4px; }

.alerts-block {
  margin-top: 16px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}
.inline-preference-block { background: #fff; }
.preference-disclosure {
  margin-top: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: #fff;
}
.preference-disclosure summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  cursor: pointer;
  list-style: none;
}
.preference-disclosure summary::-webkit-details-marker { display: none; }
.preference-disclosure summary:focus-visible { outline: 2px solid #1b65a8; outline-offset: 2px; }
.preference-disclosure summary > span { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.preference-disclosure summary strong { color: #1a2332; font-size: 13px; }
.preference-disclosure summary small { overflow: hidden; color: #7a8599; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.preference-disclosure[open] summary { border-bottom: 1px solid var(--el-border-color-lighter); }
.preference-disclosure .alerts-block { margin-top: 0; border: 0; }
.alerts-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.alerts-title { font-weight: 600; }
.alerts-note { color: var(--el-text-color-secondary); font-size: 12px; }
.alert-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 6px 0;
}
.alert-row + .alert-row { border-top: 1px solid var(--el-border-color-lighter); }
.alert-check { flex: 0 0 auto; min-width: 108px; }
.alert-detail { color: var(--el-text-color-regular); font-size: 13px; flex: 1 1 240px; }
.alert-amount { width: 120px; }
.alert-unit { color: var(--el-text-color-secondary); font-size: 12px; }
.alert-warn { color: var(--el-color-warning); font-size: 12px; }

.reconciliation { margin-top: 18px; padding: 16px; border: 1px solid #c8d6e3; border-radius: 12px; background: #f8fafc; }
.reconciliation-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.reconciliation-kicker { color: #21776a; font-size: 11px; font-weight: 800; letter-spacing: .08em; }
.reconciliation h3 { margin: 3px 0 0; color: #172033; font-size: 17px; }
.reconciliation-head p { margin: 5px 0 0; color: #66758a; font-size: 12px; line-height: 1.55; }
.issue-list { display: grid; gap: 10px; margin-top: 14px; }
.issue-card { padding: 12px; border: 1px solid #ead29f; border-radius: 10px; background: #fffaf0; }
.issue-card.blocking { border-color: #edb8b8; background: #fff6f6; }
.issue-copy strong { color: #6f4612; font-size: 13px; }
.issue-card.blocking .issue-copy strong { color: #a13333; }
.issue-copy p { margin: 4px 0 0; color: #6c7887; font-size: 12px; line-height: 1.55; }
.issue-fix { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; margin-top: 10px; }

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
  gap: 20px;
  flex-wrap: wrap;

  h2 { margin: 0; color: #1A2332; }
  .sub { margin: 6px 0 0; color: #7A8599; font-size: 13px; }
}
.ctx {
  display: flex; flex-direction: column; align-items: flex-end; gap: 4px;
  .ctx-name { color: #1A2332; font-weight: 600; }
  .ctx-id {
    color: #7A8599; font-size: 12px;
    background: #F4F6F9; padding: 2px 8px; border-radius: 4px;
  }
}

.steps { margin-bottom: 20px; }

.operation-stage,
.flow-issue {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  padding: 12px 14px;
  border-radius: 10px;
}
.operation-stage { border: 1px solid #bfdbf2; background: #f2f8fd; }
.operation-stage strong, .flow-issue strong { color: #1a2332; font-size: 13px; }
.operation-stage p, .flow-issue p { margin: 3px 0 0; color: #627084; font-size: 12px; line-height: 1.5; }
.operation-spinner { width: 18px; height: 18px; border: 2px solid #b9d4ec; border-top-color: #1b65a8; border-radius: 50%; animation: operation-spin .8s linear infinite; }
.flow-issue { grid-template-columns: minmax(0, 1fr) auto; border: 1px solid #efb4b4; background: #fff6f6; }
.flow-issue small { display: block; margin-top: 4px; color: #9b4b4b; font-size: 11px; line-height: 1.5; }
@keyframes operation-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .operation-spinner { animation: none; } }

.stage-strip {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 44px minmax(0, 1fr) 44px minmax(0, 1fr);
  align-items: center;
  margin-bottom: 14px;
  padding: 14px 18px;
  border: 1px solid #d9e2ec;
  border-radius: 12px;
  background: #f8fafc;
}
.stage-chip { display: flex; align-items: center; gap: 10px; color: #8693a3; opacity: .72; transform: translateY(0); transition: opacity 180ms var(--assembly-ease-out), transform 180ms var(--assembly-ease-out); }
.stage-chip > span { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 9px; background: #e8edf2; font-size: 11px; font-weight: 800; }
.stage-chip strong, .stage-chip small { display: block; }
.stage-chip strong { font-size: 13px; }
.stage-chip small { margin-top: 2px; font-size: 11px; }
.stage-chip.active { color: #173e37; opacity: 1; transform: translateY(-1px); }
.stage-chip.active > span { background: #d8f0eb; color: #21776a; }
.stage-line { height: 1px; background: #d5dde5; }

.questionnaire-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-top: 14px; }
.question-field { display: flex; min-width: 0; flex-direction: column; gap: 6px; }
.question-field > span { color: #1a2332; font-size: 13px; font-weight: 600; }
.question-field > small { min-height: 30px; color: #7a8599; font-size: 11px; line-height: 1.4; }
.question-field > .el-tag { align-self: flex-start; }
.count-quick-options { display: flex; flex-wrap: wrap; gap: 6px; }
.count-quick-options .el-button { min-width: 42px; min-height: 36px; margin-left: 0; }
.step-count-more { display: flex; align-items: center; gap: 8px; padding: 10px; border: 1px solid #d7e3ef; border-radius: 10px; background: #f4f6f9; }
.direct-count-more { margin: 0 14px 14px; }
.progress-change-notice { display: flex; flex-direction: column; gap: 3px; margin: -6px 0 16px; padding: 10px 12px; border-left: 3px solid #1b65a8; border-radius: 0 10px 10px 0; background: #eef5fb; color: #1a2332; }
.progress-change-notice strong { font-size: 12px; }
.progress-change-notice span { color: #66758a; font-size: 11px; line-height: 1.45; }
.questionnaire-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 16px; color: #7a8599; font-size: 12px; }
.questionnaire-handoff { display: flex; align-items: baseline; gap: 8px; padding: 10px 12px; border-top: 1px solid #edf2f7; background: #f8fafc; color: #66758a; font-size: 12px; }
.questionnaire-handoff strong { flex: none; color: #21776a; }

.content-card {
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.split {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(300px, 0.65fr);
  gap: 16px;
  align-items: start;
}
@media (max-width: 1200px) { .split { grid-template-columns: 1fr; } }

.talk-card :deep(.el-card__body) { display: flex; flex-direction: column; gap: 12px; padding: 0; }
.conversation-context {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 14px 18px; border-bottom: 1px solid #edf2f7; background: #f8fafc;
}
.conversation-context > div { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.conversation-context strong { color: #1a2332; font-size: 13px; line-height: 1.45; }
.context-kicker, .structured-kicker {
  color: #21776a; font-size: 10px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase;
}
.log {
  display: flex; flex-direction: column; gap: 12px;
  max-height: 620px; overflow-y: auto; padding: 16px 18px 4px;
}
.turn { display: flex; gap: 8px; max-width: 92%; animation: turn-enter 220ms var(--assembly-ease-out) both; }
.turn:nth-last-child(2) { animation-delay: 30ms; }
.turn:nth-last-child(1) { animation-delay: 60ms; }
.turn.me { margin-left: auto; flex-direction: row-reverse; }
.turn .av {
  width: 28px; height: 28px; border-radius: 6px; flex-shrink: 0;
  display: grid; place-items: center; font-size: 12px; font-weight: 600;
  color: #fff; background: #1B65A8;
}
.turn.me .av { background: #7A8599; }
.turn .bubble {
  padding: 8px 12px; border-radius: 8px;
  background: #F4F6F9; color: #1A2332; font-size: 14px;
  white-space: pre-wrap; word-break: break-word;
}
.turn.me .bubble { background: #1B65A8; color: #fff; }
.bubble.thinking { color: #7A8599; }
.structured-turn { max-width: 100%; }
.suggestion-prefill {
  display: flex; flex: 1; min-width: 0; align-items: flex-start; justify-content: space-between; gap: 16px;
  padding: 13px 15px; border: 1px solid #e4c98f; border-radius: 12px; background: #fffbef;
}
.suggestion-prefill > div { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.suggestion-prefill strong { color: #493a19; font-size: 14px; line-height: 1.45; }
.suggestion-prefill small { color: #77683f; font-size: 12px; line-height: 1.5; }
.structured-bubble {
  flex: 1; min-width: 0; padding: 16px; border: 1px solid #cbdde9; border-radius: 12px;
  background: #f7fbfe;
}
.structured-bubble:focus-within { border-color: #8fb8d9; box-shadow: 0 0 0 3px rgba(27, 101, 168, .08); }
.structured-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.structured-head h3 { margin: 4px 0 0; color: #1a2332; font-size: 16px; line-height: 1.4; }
.structured-head p { margin: 6px 0 0; color: #66758a; font-size: 12px; line-height: 1.55; }
.active-question-turn { display: flex; gap: 8px; padding: 0 18px 16px; }
.active-question-turn > .av {
  display: grid; place-items: center; flex: none; width: 28px; height: 28px;
  border-radius: 6px; background: #1b65a8; color: #fff; font-size: 12px; font-weight: 600;
}
.active-question-body { flex: 1; min-width: 0; overflow: hidden; border: 1px solid #dbe5ee; border-radius: 12px; background: #fff; }
.active-question-body > .decision-packet { border: 0; border-radius: 0; }
.active-question-body:focus-within { border-color: #8fb8d9; box-shadow: 0 0 0 3px rgba(27, 101, 168, .08); }
.active-question-body .composer { padding: 12px 14px; border-top: 1px solid #edf2f7; }
.active-question-body .options { padding: 0 14px 12px; }

.composer { display: flex; gap: 8px; align-items: flex-end; }
/* 枚举题下面那一行「都不是就自己说」—— 视觉上比按钮弱, 让选项仍是主入口 */
.choice-composer { margin-top: 8px; }
.choice-composer-tip { color: #909399; font-size: 12px; align-self: center; white-space: nowrap; }
.composer :deep(.el-textarea) { flex: 1; }

.inference-summary {
  display: flex; align-items: center; justify-content: space-between; gap: 18px;
  margin-bottom: 16px; padding: 14px 16px; border: 1px solid #bfded6; border-radius: 10px; background: #f2faf8;
}
.inference-summary > div { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.inference-summary span { color: #21776a; font-size: 11px; font-weight: 800; letter-spacing: .06em; }
.inference-summary strong { color: #1a2332; font-size: 14px; }
.inference-summary p { margin: 0; color: #66758a; font-size: 12px; line-height: 1.5; }
.draft-intent-card {
  margin: 16px 0; padding: 16px; border: 1px solid #cbdde9; border-radius: 12px; background: #f7fbfe;
}
.draft-intent-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.draft-intent-head h3 { margin: 4px 0 0; color: #1a2332; font-size: 16px; line-height: 1.4; }
.draft-intent-head p { margin: 6px 0 0; color: #66758a; font-size: 12px; line-height: 1.55; }
.draft-intent-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px 12px; margin-top: 14px; }
.draft-intent-option { display: flex; min-height: 52px; align-items: flex-start; margin: 0; padding: 9px 10px; border: 1px solid #dbe5ee; border-radius: 8px; background: #fff; }
.draft-intent-option :deep(.el-checkbox__label) { display: flex; min-width: 0; flex-direction: column; gap: 3px; white-space: normal; line-height: 1.35; }
.draft-intent-option small { color: #7a8599; font-size: 11px; line-height: 1.4; }
.draft-intent-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 14px; color: #66758a; font-size: 12px; }
.saved-canonical-callout, .canonical-view-callout {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  margin: 14px 18px; padding: 12px 14px; border: 1px solid #e7c778; border-radius: 10px; background: #fff9e8;
}
.saved-canonical-callout > div, .canonical-view-callout > div { min-width: 0; }
.saved-canonical-callout strong, .canonical-view-callout strong { display: block; margin-top: 3px; color: #4f3c13; font-size: 14px; }
.saved-canonical-callout p, .canonical-view-callout p { margin: 5px 0 0; color: #6f5c34; font-size: 12px; line-height: 1.5; }
.canonical-view-callout { margin: 0 0 16px; border-color: #9ec7e8; background: #f2f8fd; }
.canonical-view-callout strong { color: #1a2332; }
.canonical-view-callout p { color: #627084; }
.expert-review { margin: 16px 0; border: 1px solid #dce4ec; border-radius: 10px; padding: 0 14px; }
.expert-review-title { display: flex; align-items: baseline; gap: 10px; }
.expert-review-title strong { color: #1a2332; }
.expert-review-title span { color: #7a8599; font-size: 12px; font-weight: 400; }

.card-head { display: flex; justify-content: space-between; align-items: center; }
.head-right { display: flex; align-items: center; gap: 8px; }
.ow-label { color: #7A8599; font-size: 12px; }

.checklist { display: flex; flex-direction: column; gap: 12px; }
.ck { display: flex; gap: 8px; align-items: flex-start; }
.ck .ok { color: #67C23A; margin-top: 3px; }
.ck .pending { color: #C0C4CC; margin-top: 3px; }
.ck-label { color: #1A2332; font-size: 13px; font-weight: 600; }
.ck-value { color: #7A8599; font-size: 12px; margin-top: 2px; word-break: break-word; }
.ck-empty { color: #C0C4CC; font-size: 12px; margin-top: 2px; }
/* 问过没拿到、已放弃的 —— 与「还没问到」区分开: 那一类还会问, 这一类不会了 */
.ck .skipped { color: #E6A23C; margin-top: 3px; }
.ck-skipped { color: #E6A23C; font-size: 12px; margin-top: 2px; }

.diff { margin-bottom: 12px; }
.diff code { font-size: 12px; }
.was { color: #E6A23C; }
.none { color: #C0C4CC; }

.summary { color: #1A2332; font-size: 13px; margin-bottom: 4px; b { color: #1B65A8; } b.warn { color: #E6A23C; } }

.acts { display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px; }
.acts.center { justify-content: center; }

.done { text-align: left; display: flex; flex-direction: column; gap: 8px; margin-bottom: 16px; }
.done-row { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; font-size: 13px; }
.done-label { color: #7A8599; min-width: 64px; }
.done-written { color: #1A2332; }
.done-none { color: #C0C4CC; }
.done-skip { color: #E6A23C; font-size: 12px; }
.notbuilt { margin-bottom: 12px; padding: 10px 12px; border-radius: 6px;
            background: #FDF6EC; border: 1px solid #F5DAB1; text-align: left; }
.notbuilt-head { font-weight: 600; color: #B88230; margin-bottom: 6px; }
.notbuilt-group { margin-bottom: 6px; font-size: 13px; }
.notbuilt-items { color: #7A5B24; }
.notbuilt-fix { color: #8C7A57; font-size: 12px; margin-top: 2px; }
.workflow-next { max-width: 880px; margin: 18px auto 0; padding: 16px; border: 1px solid #efd39b; border-radius: 12px; background: #fffbef; text-align: left; }
.workflow-next-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.workflow-next-head p { margin: 5px 0 0; color: #6b5b35; font-size: 12px; line-height: 1.55; }
.workflow-next-list { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.workflow-next-list .el-button { margin-left: 0; }
.closeout-rail { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; max-width: 880px; margin: 0 auto 18px; }
.closeout-step { display: flex; align-items: center; gap: 10px; padding: 12px; border: 1px solid #dfe6ed; border-radius: 10px; background: #f8fafc; color: #7a8599; text-align: left; }
.closeout-step > span { display: grid; place-items: center; width: 28px; height: 28px; flex: none; border-radius: 8px; background: #e9eef3; font-size: 12px; font-weight: 800; }
.closeout-step strong, .closeout-step small { display: block; }
.closeout-step strong { color: #4f5d70; font-size: 13px; }
.closeout-step small { margin-top: 3px; font-size: 11px; line-height: 1.4; }
.closeout-step.done-step { border-color: #b9dfc6; background: #f3fbf5; }
.closeout-step.attention-step { border-color: #e7c778; background: #fff9e8; }
.closeout-step.attention-step > span { background: #f8df9a; color: #7b5914; }
.result-recovery { display: grid; grid-template-columns: minmax(220px, .8fr) minmax(320px, 1.2fr); gap: 22px; margin-bottom: 18px; padding: 18px; border: 1px solid #e7c778; border-radius: 12px; background: #fff9e8; text-align: left; }
.result-recovery span, .result-recovery strong { display: block; }
.result-recovery span { color: #8a651a; font-size: 11px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
.result-recovery strong { margin-top: 4px; color: #4f3c13; }
.result-recovery p { margin: 8px 0 0; color: #6f5c34; font-size: 12px; line-height: 1.6; }
.result-recovery ol { margin: 0; padding-left: 20px; color: #4f493e; }
.result-recovery li + li { margin-top: 8px; }
.closeout-step.done-step > span { background: #dff2e5; color: #2f7f46; }
.closeout-step.current-step { border-color: #9ec7e8; background: #f2f8fd; box-shadow: inset 3px 0 #1b65a8; }
.closeout-step.current-step > span { background: #dcecf8; color: #1b65a8; }
.next-best-action { display: flex; align-items: center; justify-content: space-between; gap: 20px; max-width: 880px; margin: 0 auto; padding: 16px; border: 1px solid #9ec7e8; border-radius: 12px; background: #f2f8fd; text-align: left; }
.next-best-action span, .next-best-action strong { display: block; }
.next-best-action span { color: #1b65a8; font-size: 11px; font-weight: 700; letter-spacing: .06em; }
.next-best-action strong { margin-top: 4px; color: #1a2332; font-size: 15px; }
.next-best-action p { margin: 5px 0 0; color: #627084; font-size: 12px; line-height: 1.55; }

.picker { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.picker-title { color: #1A2332; font-weight: 600; }
.picker-select { min-width: 320px; }

.mb16 { margin-bottom: 16px; }
.mt16 { margin-top: 16px; }

/* 当前提问区 —— 题面/提示/建议 */
.asking { padding: 12px 14px; border-top: 1px solid var(--el-border-color-lighter); }
.asking-head { display: flex; align-items: center; gap: 8px; }
.asking-label { font-weight: 600; font-size: 15px; }
.asking-hint { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
.suggest {
  margin-top: 10px; padding: 8px 10px; display: flex; align-items: center; gap: 10px;
  background: var(--el-fill-color-light); border-radius: 6px;
}
.suggest-text { flex: 1; font-size: 13px; line-height: 1.5; }
.suggest-tag {
  display: inline-block; margin-right: 6px; padding: 0 6px; border-radius: 3px;
  background: var(--el-color-primary-light-8); color: var(--el-color-primary); font-size: 12px;
}
/* 枚举题: 按钮换行铺开, 不用下拉 —— 选项少的时候下拉多一次点击 */
.options { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 14px 14px; }
.options .el-button { margin-left: 0; }
.unresolved-decision { border-color: #ead9ad; background: #fffaf0; }

/* 平台建议流程 */
.progress-disclosure { margin: 0; border: 1px solid #e1e7ee; border-radius: 10px; background: #fbfcfd; }
.progress-disclosure > summary {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 11px 12px; cursor: pointer; list-style: none;
}
.progress-disclosure > summary::-webkit-details-marker { display: none; }
.progress-disclosure > summary:focus-visible { outline: 2px solid #1b65a8; outline-offset: 2px; border-radius: 8px; }
.progress-disclosure > summary > span { display: flex; min-width: 0; flex-direction: column; gap: 2px; }
.progress-disclosure > summary strong { color: #1a2332; font-size: 13px; }
.progress-disclosure > summary small { color: #7a8599; font-size: 11px; line-height: 1.45; }
.progress-disclosure[open] > summary { border-bottom: 1px solid #e8edf2; }
.progress-disclosure-body { padding: 12px; }
.caseref { margin-bottom: 14px; padding: 10px 12px; background: var(--el-fill-color-lighter); border-radius: 6px; }
.caseref-head { font-weight: 600; font-size: 13px; margin-bottom: 6px; }
.caseref-src { margin-left: 8px; font-weight: 400; color: var(--el-text-color-secondary); font-size: 12px; }
.caseref-steps { margin: 0; padding-left: 20px; font-size: 13px; line-height: 1.9; }
.caseref-io { margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px; }
.ck-more { padding-left: 26px; color: var(--el-text-color-secondary); font-size: 12px; }

@keyframes turn-enter {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 900px) {
  .preview-validation { align-items: stretch; flex-direction: column; }
  .validation-blockers { grid-template-columns: 1fr; }
  .questionnaire-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .structured-head, .inference-summary { align-items: stretch; flex-direction: column; }
  .stage-strip { grid-template-columns: 1fr; gap: 8px; }
  .stage-line { display: none; }
  .steps--conversation { display: none; }
  .closeout-rail { grid-template-columns: 1fr; }
  .result-recovery { grid-template-columns: 1fr; }
}
@media (max-width: 560px) {
  .page-container { padding: 12px; }
  .card-head { align-items: flex-start; flex-direction: column; gap: 10px; }
  .head-right { width: 100%; justify-content: space-between; }
  .questionnaire-grid { grid-template-columns: 1fr; }
  .draft-intent-grid { grid-template-columns: 1fr; }
  .question-field small {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
  .conversation-context { align-items: flex-start; flex-direction: column; padding: 12px; }
  .log { max-height: none; padding: 12px; }
  .turn { gap: 6px; max-width: 96%; }
  .structured-turn { width: 100%; max-width: 100%; }
  .structured-turn > .av { display: none; }
  .turn .av, .active-question-turn > .av { width: 25px; height: 25px; border-radius: 7px; font-size: 10px; }
  .structured-bubble { padding: 12px; }
  .suggestion-prefill { align-items: stretch; flex-direction: column; gap: 8px; padding: 12px; }
  .suggestion-prefill .el-tag { align-self: flex-start; }
  .active-question-turn { padding: 0 12px 12px; }
  .expert-review-title { align-items: flex-start; flex-direction: column; gap: 2px; }
  .questionnaire-actions, .reconciliation-head, .workflow-next-head, .next-best-action { align-items: stretch; flex-direction: column; }
  .questionnaire-actions .el-button { width: 100%; }
  .draft-intent-head, .draft-intent-actions { align-items: stretch; flex-direction: column; }
  .draft-intent-grid { grid-template-columns: 1fr; }
  .draft-intent-actions .el-button { width: 100%; }
  .saved-canonical-callout, .canonical-view-callout { align-items: stretch; flex-direction: column; margin: 12px; }
  .saved-canonical-callout .el-button, .canonical-view-callout .el-button { width: 100%; }
  .issue-fix { grid-template-columns: 1fr; }
  .composer { align-items: stretch; flex-direction: column; }
  .step-count-more { align-items: stretch; flex-direction: column; }
  .step-count-more :deep(.el-input-number), .step-count-more .el-button { width: 100%; }
  .choice-composer-tip { white-space: normal; }
  .picker-select { min-width: 0; width: 100%; }
  .operation-stage, .flow-issue { grid-template-columns: 1fr; }
  .operation-spinner { display: none; }
  .questionnaire-handoff { align-items: flex-start; flex-direction: column; }
  .next-best-action .el-button, .flow-issue .el-button { width: 100%; }
  .steps :deep(.el-step__description) { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  :global(html) { scroll-behavior: auto !important; }
  :global(body *), :global(body *::before), :global(body *::after) {
    animation: none !important;
    transition-duration: 0s !important;
    transition-delay: 0s !important;
  }
  .turn { animation: none; }
  .stage-chip { transform: none; transition: none; }
  .stage-chip.active { transform: none; }
}
</style>
