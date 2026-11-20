<template>
  <!-- 非模态（modal=false）+ 不靠点外部/ESC 关闭（dismissible=false，只有右上角 X，且走 onOpenChange 的未保存确认）：
       抽屉里还要打开 Element 的命令式确认框（ElMessageBox）和共享的 SupplyImpactDialog（Element 对话框）。
       模态 USlideover 会给 body 加 pointer-events:none + 焦点陷阱，Element 浮层继承后点不了（本机实测：
       ElMessageBox 的「取消」点击超时）。关系/规格编辑是 UModal，嵌在里面照常工作。 -->
  <USlideover
    :open="modelValue"
    :modal="false"
    :overlay="false"
    :dismissible="false"
    side="right"
    title="供应商详情"
    description="供应商详情"
    :ui="slideUi"
    @update:open="onOpenChange"
  >
    <template #title>
      <span class="drawer-title">
        <UBadge v-if="detail?.shortName" color="primary" variant="outline" class="short-name-tag">{{ detail.shortName }}</UBadge>
        {{ detail?.name || supplier?.name || '供应商详情' }}
      </span>
    </template>
    <template #description>
      <span class="drawer-meta">
        {{ detail?.supplierCode || detail?.code || '-' }}
        <UBadge :color="isActive ? 'success' : 'neutral'" variant="soft">{{ isActive ? '合作中' : '暂停合作' }}</UBadge>
        <UBadge v-if="detail && !supplierProfileComplete(detail)" color="warning" variant="soft">资料不完整</UBadge>
      </span>
    </template>
    <template #actions>
      <div class="header-actions">
        <UButton data-test="supplier-supply-impact" color="neutral" variant="outline" size="sm" @click="impactVisible = true">影响分析</UButton>
        <template v-if="canWrite">
          <UButton v-if="!editing" color="neutral" variant="outline" size="sm" @click="startEdit">编辑资料</UButton>
          <UButton v-if="!editing" :color="isActive ? 'warning' : 'success'" variant="soft" size="sm" @click="changeStatus">
            {{ isActive ? '暂停合作' : '恢复合作' }}
          </UButton>
        </template>
      </div>
    </template>

    <template #body>
      <div v-if="loading" class="skeleton" data-testid="supplier-drawer-loading">
        <USkeleton v-for="n in 8" :key="n" class="h-4 w-full" />
      </div>
      <UTabs v-else-if="detail" v-model="activeTab" :items="TAB_ITEMS" variant="link" :ui="{ trigger: 'px-[10px] py-[8px] text-[13px]', content: 'pt-3' }">
        <template #profile>
          <UAlert :ui="alertUi"
            v-if="!supplierProfileComplete(detail) && !editing"
            color="warning"
            variant="subtle"
            icon="i-lucide-triangle-alert"
            title="资料不完整：历史数据可继续查看，编辑保存时必须补齐联系人、电话和地址。"
          />
          <UForm
            v-if="editing"
            ref="formRef"
            :state="form"
            :validate="validateSupplierForm"
            class="profile-form"
            @submit="saveProfile"
          >
            <UFormField label="供应商名称" name="name" required><UInput v-model="form.name" :maxlength="200" class="w-full" /></UFormField>
            <UFormField label="简称" name="shortName" help="留空时下拉显示全称。同一工厂内简称不能重复。">
              <UInput v-model="form.shortName" :maxlength="50" placeholder="选填，下拉里优先显示，例如「飞熊」" class="w-full" />
            </UFormField>
            <UFormField label="联系人" name="contactPerson" required><UInput v-model="form.contactPerson" :maxlength="100" class="w-full" /></UFormField>
            <UFormField label="联系电话" name="phone" required><UInput v-model="form.phone" :maxlength="40" class="w-full" /></UFormField>
            <UFormField label="地址" name="address" required><UTextarea v-model="form.address" :maxlength="500" :rows="2" class="w-full" /></UFormField>
            <UFormField label="邮箱" name="email"><UInput v-model="form.email" :maxlength="100" class="w-full" /></UFormField>
            <UFormField label="银行账户" name="bankAccount"><UInput v-model="form.bankAccount" :maxlength="100" class="w-full" /></UFormField>
            <UFormField label="税号" name="taxNumber"><UInput v-model="form.taxNumber" :maxlength="50" class="w-full" /></UFormField>
            <UFormField label="备注" name="notes"><UTextarea v-model="form.notes" :maxlength="5000" :rows="2" class="w-full" /></UFormField>
            <div class="edit-actions">
              <UButton color="neutral" variant="outline" @click="cancelEdit">取消</UButton>
              <UButton type="submit" :loading="saving">保存资料</UButton>
            </div>
          </UForm>
          <dl v-else class="info-grid" data-testid="supplier-profile-info">
            <div><dt>供应商名称</dt><dd>{{ detail.name || '资料不完整' }}</dd></div>
            <div><dt>简称</dt><dd>{{ detail.shortName || '未设置（下拉里显示全称）' }}</dd></div>
            <div><dt>联系人</dt><dd>{{ detail.contactPerson || '资料不完整' }}</dd></div>
            <div><dt>联系电话</dt><dd>{{ detail.phone || detail.contactPhone || '资料不完整' }}</dd></div>
            <div><dt>邮箱</dt><dd>{{ detail.email || '-' }}</dd></div>
            <div><dt>银行账户</dt><dd>{{ detail.bankAccount || '-' }}</dd></div>
            <div class="wide"><dt>地址</dt><dd>{{ detail.address || '资料不完整' }}</dd></div>
            <div><dt>税号</dt><dd>{{ detail.taxNumber || detail.taxId || '-' }}</dd></div>
            <div class="wide"><dt>备注</dt><dd>{{ detail.notes || '-' }}</dd></div>
          </dl>
        </template>

        <template #collections>
          <SupplierProfileCollections
            v-if="detail"
            :factory-id="factoryId"
            :supplier-id="detail.id"
            :supplier-name="detail.name"
            :can-write="canWrite && isActive"
            @changed="onCollectionsChanged"
          />
        </template>

        <template #materials>
          <UAlert :ui="alertUi"
            v-if="!isActive"
            color="warning"
            variant="subtle"
            icon="i-lucide-triangle-alert"
            title="供应商已暂停合作，供应原料关系仅可查看；恢复合作后才能新增或修改。"
            class="relation-status-alert"
          />
          <div class="tab-toolbar">
            <span>独立供应关系主数据；采购历史在“采购记录”中展示。</span>
            <UButton
              v-if="canWrite"
              color="neutral"
              variant="outline"
              :disabled="!isActive"
              @click="openRelationCreate"
            >管理供应原料</UButton>
          </div>
          <UTable :data="materialRelations" :columns="relationColumns" class="cs-table" data-testid="supplier-material-relations">
            <template #empty>暂无供应原料关系</template>
            <template #material-cell="{ row }">
              <div>{{ row.original.materialName || row.original.materialTypeId }}</div>
              <small class="muted">{{ row.original.materialCode || row.original.materialTypeId }}</small>
            </template>
            <template #supplierCode-cell="{ row }">{{ row.original.supplierMaterialCode || '-' }}</template>
            <!-- 🔴 缺陷 35: 列头不说口径、值却有口径 —— owner 表单填含税 2.00, 列表显示 1.77,
                 长得像「我填的数被系统改了」。列头与取值必须**一起**改, ⛔ 不能只改一头。
                 现在列头写明含税, 取的也是含税那个字段; 存量基准未知的行单独标出来。 -->
            <template #price-cell="{ row }">
              <span v-if="row.original.taxIncludedDefaultPurchasePrice != null">
                {{ row.original.currency || 'CNY' }} {{ Number(row.original.taxIncludedDefaultPurchasePrice).toFixed(2) }}/{{ displayUnit(row.original.purchaseUnit) }}
              </span>
              <!-- ⛔ 基准未知的存量值不能摆在「含税」列头下面 —— 那是把同一个谎反过来说一遍。 -->
              <!-- 说明用原生 title: 全局没包 UApp，UTooltip 缺 TooltipProvider 会让整个页签渲染失败（实测）。 -->
              <span
                v-else-if="row.original.defaultPurchasePrice != null"
                class="price-basis-unknown"
                data-tip="basis-unknown"
                title="这条关系是在「含税/未税」区分之前录入的，无法确定该价格是含税还是未税；编辑并填写含税价即可确认。"
              >
                {{ row.original.currency || 'CNY' }} {{ Number(row.original.defaultPurchasePrice).toFixed(2) }}/{{ displayUnit(row.original.purchaseUnit) }}
                · 基准未知
              </span>
              <span v-else>未设置</span>
            </template>
            <template #moq-cell="{ row }">{{ row.original.minOrderQuantity ?? '-' }} {{ displayUnit(row.original.purchaseUnit) }}</template>
            <template #lead-cell="{ row }">{{ row.original.leadTimeDays == null ? '-' : `${row.original.leadTimeDays}天` }}</template>
            <template #state-cell="{ row }">
              <UBadge :color="row.original.active === false ? 'neutral' : 'success'" variant="soft">
                {{ row.original.active === false ? '已停用' : row.original.preferred ? '首选' : '启用' }}
              </UBadge>
            </template>
            <!-- 🔒 缺陷 16: 「采购规格」/「编辑」两个按钮 -> 一个动作。
                 单一按钮永远可点(不带 canWrite/isActive 门槛), 因为「查看」这个能力
                 之前挂在「采购规格」按钮上、不受写权限/停用状态限制 —— 合并后必须继续保留。
                 可写与否只改按钮文案和弹窗内部能不能动, 不改按钮是否出现。 -->
            <template #actions-cell="{ row }">
              <div class="row-actions">
                <UButton size="xs" variant="link" @click="openRelationEdit(row.original)">
                  {{ canWrite && isActive ? '编辑' : '查看' }}
                </UButton>
                <UButton v-if="canWrite && isActive && row.original.active !== false" size="xs" variant="link" color="warning" @click="removeRelation(row.original)">停用</UButton>
              </div>
            </template>
          </UTable>
        </template>

        <template #prices>
          <UEmpty icon="i-lucide-receipt-text" description="当前后端未提供独立供应商报价台账；历史价格仍保留在采购单据中。" />
        </template>

        <template #purchases>
          <UTable :data="history" :columns="historyColumns" class="cs-table" data-testid="supplier-purchase-history">
            <template #empty>暂无采购记录摘要</template>
          </UTable>
        </template>

        <template #payables>
          <dl class="info-grid" data-testid="supplier-payables-info">
            <div><dt>采购单数</dt><dd>{{ detail.totalOrders ?? '-' }}</dd></div>
            <div><dt>最近采购</dt><dd>{{ detail.lastOrderDate || '-' }}</dd></div>
            <div><dt>累计采购金额</dt><dd>{{ formatMoney(detail.totalAmount) }}</dd></div>
            <div><dt>当前应付余额</dt><dd>{{ formatMoney(detail.currentBalance) }}</dd></div>
          </dl>
        </template>

        <template #changes>
          <UTimeline v-if="changeItems.length" :items="changeItems" :default-value="changeItems.length - 1" />
          <UEmpty v-else icon="i-lucide-history" description="暂无可展示的变更记录" />
        </template>
      </UTabs>
    </template>
  </USlideover>

  <SupplyImpactDialog
    v-if="impactVisible"
    v-model="impactVisible"
    :factory-id="factoryId"
    :target="{ supplierId: detail?.id }"
    :target-name="detail?.name ?? ''"
  />

  <!-- 🔒 缺陷 16: 新增/编辑现在是同一个弹窗形状 —— 关系表单 + 采购规格管理合在一起,
       ⛔ 不再拆成「关系」「采购规格」两个弹窗。
       新增: 关系还不存在, 只给「顺手新增一个采购规格」的轻量入口(原样保留)。
       编辑/查看: 关系表单之后紧跟完整的规格列表管理器(原 specDialogVisible 的内容原样搬进来)。
       `relationWritable` 统一控制"能不能改"; 查看态下相同表单以只读(disabled)呈现,
       ⛔ 不是新做一套只读展示 —— 复用同一批 v-model, 避免两份口径漂移。
       Nuxt UI 弹层里不放 Element 控件: 物料/单位下拉、数字框、开关全是 Nuxt UI。 -->
  <UModal
    v-model:open="relationDialogVisible"
    :title="relationDialogTitle"
    description="供应商供应原料关系与采购规格"
    :dismissible="false"
    :ui="modalUi('max-w-[760px]')"
  >
    <template #body>
      <div class="relation-form">
        <UFormField label="物料" required>
          <USelectMenu
            :model-value="relationForm.materialTypeId || undefined"
            :items="materialItems"
            value-key="value"
            placeholder="请选择物料"
            :search-input="{ placeholder: '搜索…' }"
            :disabled="Boolean(relationEditingId) || !relationWritable"
            class="w-full"
            @update:model-value="onMaterialPick"
          />
        </UFormField>
        <UFormField label="供应商料号"><UInput v-model="relationForm.supplierMaterialCode" :maxlength="100" :disabled="!relationWritable" class="w-full" /></UFormField>
        <UFormField label="采购单位" required help="采购订单将沿用该单位；系统内部保存标准单位代码，页面只显示中文业务单位。">
          <SupplierUnitSelect
            v-model="relationForm.purchaseUnit"
            :options="relationPurchaseUnitOptions"
            placeholder="请选择采购单位"
            :disabled="!relationWritable"
          />
          <UAlert :ui="alertUi"
            v-if="relationUnitReason"
            class="relation-unit-alert"
            color="error"
            variant="subtle"
            icon="i-lucide-circle-alert"
            :title="relationUnitReason"
          />
        </UFormField>
        <!-- 🔒 19②: 人填含税, 未税只读派生 (owner: 「未税报价……只能是自动计算出来」)。
             ⚠️ 原本【一个基准都没说】—— 用户填 50, 系统不知道那是含税还是未税,
             而它会一路流到采购单和入库批次。现在标题就把基准说清楚。 -->
        <UFormField
          :label="isExemptMaterial(relationMaterial) ? `免税采购价（元/${displayUnit(relationForm.purchaseUnit) || '采购单位'}）` : `含税默认采购价（元/${displayUnit(relationForm.purchaseUnit) || '采购单位'}）`"
        >
          <UInputNumber v-model="relationForm.taxIncludedDefaultPurchasePrice" :step-snapping="false" :min="0" :format-options="PRICE_FORMAT" :increment="false" :decrement="false" class="w-full" :disabled="!relationWritable" />
          <!-- 形态 B⁵ 第二次打在同一句 hint 上: 缺陷 17 改过一次(价格从选填变必填),
               owner 2026-09-02 改判 A(一律必填、存量也要补)之后又得改一次。
               ⛔ 删掉一条承诺就要在同一个 commit 里删掉它的描述 —— 过期的描述比没有描述更糟,
               它会让下一个人跳过核实。

               ⚠️ 这段注释刻意**不复述**那两句已被推翻的原文。
               `supplierMaterialRelation.source.spec.ts` 有一条阴性对照钉着它们不许回来,
               而源码闸是按字面匹配的 —— 把被禁的措辞抄进注释, 闸就会匹配到注释本身,
               既弄红了正确的代码, 又正是本仓记过的「闸把自己的注释当成被测对象」那个形态。 -->
          <template #help>
            价格与采购单位绑定，按含税价填写。新增与编辑都必须填写；存量没有价格的关系，需要补上价格才能保存。
          </template>
        </UFormField>
        <!-- ⛔ 算不出来时【不渲染】这一行, 而不是显示 0 或空白 —— 空白会被读成 0。
             镜像 material-types/list.vue:1772 的 v-if 写法。 -->
        <UFormField
          v-if="relationDerivedUntaxed != null"
          :label="`未税采购价（元/${displayUnit(relationForm.purchaseUnit) || '采购单位'}）`"
          :help="isExemptMaterial(relationMaterial) ? '免税时未税价等于采购价' : '= 含税价 ÷ (1 + 税率)，按物料税率自动计算，不需要填写'"
        >
          <UInput :model-value="relationDerivedUntaxed.toFixed(4)" disabled class="w-full" />
        </UFormField>
        <div v-else-if="relationForm.taxIncludedDefaultPurchasePrice != null" class="field-hint wide">
          该物料未配置税率，暂时算不出未税价。⛔ 这不代表未税价等于含税价。
        </div>
        <div v-else-if="relationEditingId && relationForm.defaultPurchasePrice != null" class="field-hint wide">
          ⚠️ 这条关系是在「含税/未税」区分之前录入的，存量价格 {{ relationForm.defaultPurchasePrice }} 的基准未知。
          填写含税价即可确认；在此之前它不会被用来自动带价。
        </div>
        <UFormField label="最小起订量"><UInputNumber v-model="relationForm.minOrderQuantity" :step-snapping="false" :min="0.0001" :format-options="PRICE_FORMAT" :increment="false" :decrement="false" class="w-full" :disabled="!relationWritable" /></UFormField>
        <UFormField label="交期（天）"><UInputNumber v-model="relationForm.leadTimeDays" :step-snapping="false" :min="0" :format-options="INT_FORMAT" :increment="false" :decrement="false" class="w-full" :disabled="!relationWritable" /></UFormField>
        <UFormField label="首选供应商"><USwitch v-model="relationForm.preferred" :disabled="!relationWritable" /></UFormField>
        <UFormField label="关系启用"><USwitch v-model="relationForm.active" :disabled="!relationWritable" /></UFormField>
      </div>

      <!-- 新增: 关系还不存在, 规格只给"顺手加一个"的轻量入口。 -->
      <template v-if="!relationEditingId">
        <USeparator class="my-4" :label="`采购规格（选填，不填按 ${displayUnit(relationForm.purchaseUnit) || '库存基本单位'} 直采）`" />
        <div v-if="!inlineSpecEnabled" class="inline-spec-toggle">
          <UButton color="neutral" variant="outline" icon="i-lucide-plus" @click="inlineSpecEnabled = true">顺手新增一个采购规格（例如「10kg/箱」）</UButton>
        </div>
        <template v-else>
          <div class="relation-form inline-spec-form">
            <UFormField label="规格名称" required><UInput v-model="inlineSpecForm.name" :maxlength="100" placeholder="例如 10kg/箱" class="w-full" /></UFormField>
            <UFormField label="采购包装单位" required>
              <SupplierUnitSelect v-model="inlineSpecForm.purchasePackageUnit" :options="purchaseUnitOptions" />
            </UFormField>
            <UFormField label="换算系数" required :help="`1 ${displayUnit(inlineSpecForm.purchasePackageUnit)} = ${inlineSpecForm.factor || '?'} ${displayUnit(relationForm.purchaseUnit) || '库存基本单位'}`">
              <UInputNumber v-model="inlineSpecForm.factor" :step-snapping="false" :min="0.0001" :format-options="PRICE_FORMAT" :increment="false" :decrement="false" class="w-full" />
            </UFormField>
            <UFormField :label="isExemptMaterial(relationMaterial) ? `免税报价（元/${displayUnit(inlineSpecForm.purchasePackageUnit) || '采购包装单位'}）` : `含税报价（元/${displayUnit(inlineSpecForm.purchasePackageUnit) || '采购包装单位'}）`">
              <UInputNumber v-model="inlineSpecForm.taxIncludedQuotedPrice" :step-snapping="false" :min="0" :format-options="PRICE_FORMAT" :increment="false" :decrement="false" class="w-full" />
            </UFormField>
            <UFormField
              v-if="inlineSpecDerivedUntaxed != null"
              :label="`未税报价（元/${displayUnit(inlineSpecForm.purchasePackageUnit) || '采购包装单位'}）`"
              :help="isExemptMaterial(relationMaterial) ? '免税时未税价等于报价' : '= 含税报价 ÷ (1 + 税率)，自动计算'"
            >
              <UInput :model-value="inlineSpecDerivedUntaxed.toFixed(4)" disabled class="w-full" />
            </UFormField>
          </div>
          <div class="inline-spec-toggle">
            <UButton color="neutral" variant="outline" @click="inlineSpecEnabled = false; resetInlineSpecForm()">不加了，收起</UButton>
          </div>
        </template>
      </template>

      <!-- 编辑/查看: 已有关系, 展示完整的采购规格列表 + 增改停用(原 specDialogVisible 内容)。 -->
      <template v-else>
        <USeparator class="my-4" label="采购规格" />
        <UAlert :ui="alertUi"
          color="info"
          variant="subtle"
          icon="i-lucide-info"
          :title="`${specRelation?.materialName || '当前物料'} · 库存基本单位 ${displayUnit(specRelation?.baseUnit)}`"
          description="采购订单选择规格后按包装数量下单，系统按换算系数折合库存基本单位；换算系数由后端快照锁定。"
          class="mb-3"
        />
        <UTable :data="purchaseSpecs" :columns="specColumns" class="cs-table" data-testid="supplier-purchase-specs">
          <template #empty>暂无采购规格；采购订单将按库存基本单位直采</template>
          <template #pkg-cell="{ row }">{{ displayUnit(row.original.purchasePackageUnit) }}</template>
          <template #factor-cell="{ row }">1 {{ displayUnit(row.original.purchasePackageUnit) }} = {{ row.original.factor }} {{ displayUnit(row.original.inventoryBaseUnit) }}</template>
          <!-- 缺陷 35 第二处: 与上面那一列同一个形状(列头无口径 / 值是未税)。
               ⚠️ 只修关系那一列就又是「改共享结构只改了一部分」—— 这次一起改。 -->
          <template #quote-cell="{ row }">
            <span v-if="row.original.taxIncludedQuotedPrice != null">
              {{ row.original.currency || 'CNY' }} {{ Number(row.original.taxIncludedQuotedPrice).toFixed(2) }}/{{ displayUnit(row.original.purchasePackageUnit) }}
            </span>
            <span
              v-else-if="row.original.quotedPrice != null"
              class="price-basis-unknown"
              data-tip="basis-unknown"
              title="该规格的报价是在「含税/未税」区分之前录入的，无法确定口径；编辑并填写含税报价即可确认。"
            >
              {{ row.original.currency || 'CNY' }} {{ Number(row.original.quotedPrice).toFixed(2) }}/{{ displayUnit(row.original.purchasePackageUnit) }}
              · 基准未知
            </span>
            <span v-else>-</span>
          </template>
          <template #state-cell="{ row }">
            <UBadge :color="row.original.active === false ? 'neutral' : 'success'" variant="soft">{{ row.original.active === false ? '停用' : row.original.defaultSpec ? '默认' : '启用' }}</UBadge>
          </template>
          <template #actions-cell="{ row }">
            <div class="row-actions">
              <UButton size="xs" variant="link" @click="editPurchaseSpec(row.original)">编辑</UButton>
              <UButton v-if="row.original.active !== false" size="xs" variant="link" color="warning" @click="removePurchaseSpec(row.original)">停用</UButton>
            </div>
          </template>
        </UTable>
        <template v-if="relationWritable">
          <div v-if="!specEditingId && !showNewSpecForm" class="spec-add-toggle">
            <UButton color="neutral" variant="outline" icon="i-lucide-plus" @click="showNewSpecForm = true">新增规格</UButton>
          </div>
          <template v-else>
            <USeparator class="my-4" :label="specEditingId ? '编辑规格' : '新增规格'" />
            <div class="relation-form">
              <UFormField label="规格名称" required><UInput v-model="specForm.name" :maxlength="100" placeholder="例如 10kg/箱" class="w-full" /></UFormField>
              <UFormField label="采购包装单位" required>
                <SupplierUnitSelect v-model="specForm.purchasePackageUnit" :options="purchaseUnitOptions" />
              </UFormField>
              <UFormField label="换算系数" required :help="`1 ${displayUnit(specForm.purchasePackageUnit)} = ${specForm.factor || '?'} ${displayUnit(specRelation?.baseUnit)}`">
                <UInputNumber v-model="specForm.factor" :step-snapping="false" :min="0.0001" :format-options="PRICE_FORMAT" :increment="false" :decrement="false" class="w-full" />
              </UFormField>
              <UFormField :label="isExemptMaterial(specMaterial) ? `免税报价（元/${displayUnit(specForm.purchasePackageUnit) || '采购包装单位'}）` : `含税报价（元/${displayUnit(specForm.purchasePackageUnit) || '采购包装单位'}）`">
                <UInputNumber v-model="specForm.taxIncludedQuotedPrice" :step-snapping="false" :min="0" :format-options="PRICE_FORMAT" :increment="false" :decrement="false" class="w-full" />
              </UFormField>
              <UFormField
                v-if="specDerivedUntaxed != null"
                :label="`未税报价（元/${displayUnit(specForm.purchasePackageUnit) || '采购包装单位'}）`"
                :help="isExemptMaterial(specMaterial) ? '免税时未税价等于报价' : '= 含税报价 ÷ (1 + 税率)，自动计算'"
              >
                <UInput :model-value="specDerivedUntaxed.toFixed(4)" disabled class="w-full" />
              </UFormField>
              <UFormField label="默认规格"><USwitch v-model="specForm.defaultSpec" /></UFormField>
              <UFormField label="启用"><USwitch v-model="specForm.active" /></UFormField>
            </div>
            <div class="spec-actions">
              <UButton v-if="specEditingId" color="neutral" variant="outline" @click="resetSpecForm">取消编辑</UButton>
              <UButton v-else color="neutral" variant="outline" @click="showNewSpecForm = false; resetSpecForm()">收起</UButton>
              <UButton :loading="specSaving" @click="savePurchaseSpec">{{ specEditingId ? '保存规格' : '新增规格' }}</UButton>
            </div>
          </template>
        </template>
      </template>
    </template>

    <template #footer>
      <UButton color="neutral" variant="outline" @click="relationDialogVisible = false">{{ relationWritable ? '取消' : '关闭' }}</UButton>
      <UButton v-if="relationWritable" :loading="relationSaving" @click="saveRelation">保存</UButton>
    </template>
  </UModal>

  <!-- 停用采购规格的确认：这一步发生在上面那个 UModal 里面，模态 UModal 会让 Element 的 ElMessageBox 点不了
       （body pointer-events:none），所以确认也是 UModal。v-if：没有待确认的规格时不渲染，
       「关系 + 规格合在同一个弹窗」那条断言数到的弹窗数仍是 1。 -->
  <UModal
    v-if="specToStop"
    :open="true"
    title="停用采购规格"
    description="停用采购规格前的确认"
    :dismissible="false"
    :ui="modalUi('max-w-[440px]')"
    @update:open="(open: boolean) => { if (!open) specToStop = null; }"
  >
    <template #body>
      <p class="confirm-text">停用后，新采购订单不能再选择该规格；历史订单快照不受影响。</p>
    </template>
    <template #footer>
      <UButton color="neutral" variant="outline" data-testid="spec-stop-cancel" @click="specToStop = null">取消</UButton>
      <UButton color="warning" :loading="specStopping" data-testid="spec-stop-confirm" @click="confirmStopSpec">确认停用</UButton>
    </template>
  </UModal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { CsTableColumn as TableColumn, CsTabItem as TabsItem } from '@/types/csTable';
import { get } from '@/api/request';
import { listSystemUnits, type SystemUnit } from '@/api/systemUnits';
import SupplierUnitSelect from './SupplierUnitSelect.vue';
import SupplierProfileCollections from './SupplierProfileCollections.vue';
import SupplyImpactDialog from '@/components/common/SupplyImpactDialog.vue';
import {
  getSupplier,
  listSupplierMaterials,
  createSupplierMaterial,
  updateSupplierMaterial,
  deleteSupplierMaterial,
  listSupplierPurchaseSpecs,
  createSupplierPurchaseSpec,
  updateSupplierPurchaseSpec,
  deleteSupplierPurchaseSpec,
  updateSupplier,
  updateSupplierStatus,
  type SupplierMaterialPayload,
  type SupplierMaterialRelation,
  type SupplierPurchaseSpec,
  type SupplierPurchaseSpecPayload,
  type SupplierRecord,
  type SupplierSavePayload,
} from '@/api/supplierManagement';
import { displayUnit } from '@/utils/unitPricing';
// ⚠️ 复用采购单侧那一份换算, ⛔ 不新写 —— 见下方 19② 注释块。
import { purchaseUntaxedUnitPrice } from '@/views/procurement/orders/purchaseOrderTaxInclusivePricing';
import { purchaseUnitSelectionReason, resolvePurchaseUnits } from '@/utils/purchaseUnitContract';
import {
  validateSupplierForm, supplierProfileComplete, supplierStatus, normalizeSupplierPayload,
} from './supplierModel';
import { toSupplierHistoryViewRow, type SupplierHistoryApiRow, type SupplierHistoryViewRow } from './supplierHistory';
import { alertUi, modalUi, slideUi } from './supplierOverlayUi';

const impactVisible = ref(false);

const props = defineProps<{
  modelValue: boolean;
  factoryId: string;
  supplier: SupplierRecord | null;
  canWrite: boolean;
  initialTab?: string;
}>();
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void; (event: 'changed'): void }>();

const loading = ref(false);
const saving = ref(false);
const editing = ref(false);
const activeTab = ref('profile');
const detail = ref<SupplierRecord | null>(null);
const history = ref<SupplierHistoryViewRow[]>([]);
const materialRelations = ref<SupplierMaterialRelation[]>([]);
// ⚠️ 19②: 多带 taxRate / taxTreatment 回来 —— 未税是由含税按【物料】税率派生的,
// 税率是货物的属性不是供应商的属性, 所以供应侧没有也不需要自己的税率字段。
// 接口 /raw-material-types/active 返回的 RawMaterialTypeDTO 本来就带这两个字段。
const materialOptions = ref<Array<{
  id: string; name: string; code?: string | null; unit?: string | null;
  taxRate?: string | null; taxTreatment?: string | null;
}>>([]);
const allUnitOptions = ref<SystemUnit[]>([]);
const purchaseUnitOptions = ref<SystemUnit[]>([]);
const relationDialogVisible = ref(false);
const relationEditingId = ref('');
const relationSaving = ref(false);
const relationForm = reactive<SupplierMaterialPayload>({
  materialTypeId: '', supplierMaterialCode: '', defaultPurchasePrice: null, taxIncludedDefaultPurchasePrice: null, currency: 'CNY', purchaseUnit: '',
  minOrderQuantity: null, leadTimeDays: null, preferred: false, active: true,
});
const inlineSpecEnabled = ref(false);
const inlineSpecForm = reactive<{ name: string; purchasePackageUnit: string; factor: number; quotedPrice: number | null; taxIncludedQuotedPrice: number | null }>({
  name: '', purchasePackageUnit: '', factor: 1, quotedPrice: null, taxIncludedQuotedPrice: null,
});
// 🔒 缺陷 16: 采购规格管理不再有独立弹窗(specDialogVisible 已删) —— 现在合在
//    relationDialogVisible 里, 编辑/查看态下随关系表单一起展示。
const specRelation = ref<SupplierMaterialRelation | null>(null);
const purchaseSpecs = ref<SupplierPurchaseSpec[]>([]);
const specEditingId = ref('');
const specSaving = ref(false);
const specToStop = ref<SupplierPurchaseSpec | null>(null);
const specStopping = ref(false);
const showNewSpecForm = ref(false);
// 🔒 缺陷 16 phase 1: minOrderQuantity/leadTimeDays 已从规格表单移除 —— 见 supplierManagement.ts 注释。
const specForm = reactive<SupplierPurchaseSpecPayload>({
  name: '', purchasePackageUnit: '', inventoryBaseUnit: '', factor: 1, quotedPrice: null, taxIncludedQuotedPrice: null, currency: 'CNY',
  defaultSpec: false, active: true,
});
const formRef = ref<{ submit: () => Promise<void> } | null>(null);
const originalForm = ref('');
const form = reactive<SupplierSavePayload>({
  name: '', shortName: '', contactPerson: '', phone: '', address: '', email: '', bankAccount: '', taxNumber: '', notes: '',
});

const TAB_ITEMS: TabsItem[] = [
  { label: '基本资料', value: 'profile', slot: 'profile' },
  { label: '联系人/地址/账户', value: 'collections', slot: 'collections' },
  { label: '供应原料', value: 'materials', slot: 'materials' },
  { label: '价格/报价', value: 'prices', slot: 'prices' },
  { label: '采购记录', value: 'purchases', slot: 'purchases' },
  { label: '对账/应付', value: 'payables', slot: 'payables' },
  { label: '变更记录', value: 'changes', slot: 'changes' },
];
// UInputNumber 没有 precision：用 Intl 的小数位上限（原 el-input-number precision=4 / 0）
const PRICE_FORMAT = { maximumFractionDigits: 4 };
const INT_FORMAT = { maximumFractionDigits: 0 };
const relationColumns: TableColumn<SupplierMaterialRelation>[] = [
  { id: 'material', header: '物料' },
  { id: 'supplierCode', header: '供应商料号' },
  { id: 'price', header: '含税默认采购价' },
  { id: 'moq', header: '起订量' },
  { id: 'lead', header: '交期' },
  { id: 'state', header: '关系状态' },
  { id: 'actions', header: '操作' },
];
const historyColumns: TableColumn<SupplierHistoryViewRow>[] = [
  { accessorKey: 'materialName', header: '物料' },
  { accessorKey: 'purchaseCount', header: '采购次数' },
  { accessorKey: 'lastPurchaseDate', header: '最近采购日期' },
];
const specColumns = computed<TableColumn<SupplierPurchaseSpec>[]>(() => [
  { accessorKey: 'name', header: '规格名称' },
  { id: 'pkg', header: '采购包装单位' },
  { id: 'factor', header: '换算' },
  { id: 'quote', header: '含税报价' },
  { id: 'state', header: '状态' },
  ...(relationWritable.value ? [{ id: 'actions', header: '操作' } as TableColumn<SupplierPurchaseSpec>] : []),
]);
const changeItems = computed(() => {
  const d = detail.value;
  if (!d) return [];
  return [
    ...(d.createdAt ? [{ value: 'created', date: d.createdAt, title: `创建供应商 · ${d.createdByName || '系统记录'}` }] : []),
    ...(d.updatedAt ? [{ value: 'updated', date: d.updatedAt, title: '最近更新' }] : []),
  ];
});

const isActive = computed(() => detail.value ? supplierStatus(detail.value) === 'ACTIVE' : false);
const relationMaterial = computed(() => materialOptions.value.find((row) => row.id === relationForm.materialTypeId));
const materialItems = computed(() => materialOptions.value.map((material) => ({
  label: `${material.name} (${material.code || material.id})`,
  value: material.id,
})));

/**
 * 🔒 缺陷 16: 合并弹窗之后，同一个表单形状既要给"编辑"用也要给"查看"用。
 * 新增永远可写；编辑仅在 canWrite && isActive 时可写，否则整份表单以只读呈现。
 * ⚠️ 单一按钮（"编辑"/"查看"）不再靠 v-if 隐藏入口，靠这个 computed 控制"能不能改"。
 */
const relationWritable = computed(() => !relationEditingId.value || (props.canWrite && isActive.value));
const relationDialogTitle = computed(() => {
  if (!relationEditingId.value) return '新增供应原料关系';
  return relationWritable.value ? '编辑供应原料关系' : '查看供应原料关系';
});

/* ═══════════ 19②: 含税录入 / 未税派生 ═══════════
 *
 * 🔒 owner 裁定:「一般在中国基本上都是含税报价含税价格……不可能一开始就知道未税报价。
 *    未税报价是不需要去填写的, 只能是自动计算出来; 他填的指的是含税报价。」
 * ⇒ 三个录入口一律填【含税】, 未税只读派生。
 *
 * ⚠️ 换算刻意复用采购单侧那一份 (purchaseUntaxedUnitPrice, 4 位) 而不是自己写:
 *    同一条「未税 = 含税 ÷ (1+税率)」在本仓已经有两份实现且精度不同
 *    (采购单 4 位 / 原料档案内联 computed 2 位)。供应商价格【流向】采购单,
 *    所以在被消费的那个边界上对齐。⛔ 不要在这里再写一遍除法。
 */

/** 物料的税率百分数 (9 / 13); 免税或未配置返回 null。 */
function materialTaxPercent(material?: { taxRate?: string | null; taxTreatment?: string | null } | null): number | null {
  if (!material || material.taxTreatment === 'EXEMPT') return null;
  if (material.taxRate === 'TAX_9') return 9;
  if (material.taxRate === 'TAX_13') return 13;
  return null;
}

function isExemptMaterial(material?: { taxTreatment?: string | null } | null): boolean {
  return material?.taxTreatment === 'EXEMPT';
}

/**
 * 由含税价派生未税价。
 * ⛔ 返回 null 一律表示「算不出来」—— 调用方必须【不显示】, 而不是显示 0 或空白。
 * 空白会被读成 0, 那正是本次改动要消灭的形状。
 */
function derivedUntaxed(taxIncluded: number | null | undefined,
                        material?: { taxRate?: string | null; taxTreatment?: string | null } | null): number | null {
  if (taxIncluded == null) return null;
  if (isExemptMaterial(material)) return Number(taxIncluded);   // 免税: 未税 == 含税
  const pct = materialTaxPercent(material);
  if (pct == null) return null;                                  // 没有税率 ⇒ 不显示
  return purchaseUntaxedUnitPrice(taxIncluded, pct);
}

/** 关系侧: 当前选中物料的派生未税价。 */
const relationDerivedUntaxed = computed(
  () => derivedUntaxed(relationForm.taxIncludedDefaultPurchasePrice, relationMaterial.value));

/** 规格侧: 规格挂在某条关系上, 税率取那条关系的物料。 */
const specMaterial = computed(
  () => materialOptions.value.find((row) => row.id === specRelation.value?.materialTypeId));
const inlineSpecDerivedUntaxed = computed(
  () => derivedUntaxed(inlineSpecForm.taxIncludedQuotedPrice, relationMaterial.value));
const specDerivedUntaxed = computed(
  () => derivedUntaxed(specForm.taxIncludedQuotedPrice, specMaterial.value));
const relationUnitResolution = computed(() => resolvePurchaseUnits(
  relationMaterial.value?.unit,
  allUnitOptions.value,
  purchaseUnitOptions.value,
));
const relationPurchaseUnitOptions = computed(() => relationUnitResolution.value.compatibleUnits);
const relationUnitReason = computed(() => (relationForm.materialTypeId
  ? purchaseUnitSelectionReason(relationForm.purchaseUnit, relationUnitResolution.value)
  : ''));
const dirty = computed(() => editing.value && JSON.stringify(normalizeSupplierPayload(form)) !== originalForm.value);

watch(() => props.modelValue, (open) => {
  if (open && props.supplier?.id) {
    activeTab.value = props.initialTab || 'profile';
    void loadDetail();
  }
  if (!open) resetState();
});

/**
 * 子档（联系人/地址/账户）变动后必须重拉主档 —— 主记录会镜像回 suppliers 的
 * 联系人/电话/邮箱/地址/开户行/账号, 不重拉的话「基本资料」页签仍显示旧值,
 * 用户会以为没保存成功。
 */
async function onCollectionsChanged(): Promise<void> {
  if (!detail.value) return;
  detail.value = await getSupplier(props.factoryId, detail.value.id);
  emit('changed');
}

async function loadDetail(): Promise<void> {
  if (!props.supplier?.id) return;
  loading.value = true;
  try {
    const [supplierData, historyResponse, relationRows] = await Promise.all([
      getSupplier(props.factoryId, props.supplier.id),
      get<SupplierHistoryApiRow[]>(`/${props.factoryId}/suppliers/${props.supplier.id}/history`),
      listSupplierMaterials(props.factoryId, props.supplier.id),
    ]);
    detail.value = supplierData;
    history.value = (historyResponse.data ?? []).map(toSupplierHistoryViewRow);
    materialRelations.value = relationRows;
  } finally {
    loading.value = false;
  }
}

function fillForm(): void {
  if (!detail.value) return;
  Object.assign(form, {
    name: detail.value.name ?? '',
    shortName: detail.value.shortName ?? '',
    contactPerson: detail.value.contactPerson ?? '',
    phone: detail.value.phone || detail.value.contactPhone || '',
    address: detail.value.address ?? '',
    email: detail.value.email ?? '',
    bankAccount: detail.value.bankAccount ?? '',
    taxNumber: detail.value.taxNumber || detail.value.taxId || '',
    notes: detail.value.notes ?? '',
    version: detail.value.version ?? undefined,
  });
  originalForm.value = JSON.stringify(normalizeSupplierPayload(form));
}

function startEdit(): void {
  fillForm();
  editing.value = true;
  activeTab.value = 'profile';
}

async function saveProfile(): Promise<void> {
  // 校验由 UForm 的 validate（validateSupplierForm）在 @submit 之前完成
  if (!detail.value) return;
  saving.value = true;
  try {
    detail.value = await updateSupplier(props.factoryId, detail.value.id, normalizeSupplierPayload(form));
    editing.value = false;
    ElMessage.success('供应商资料已更新');
    emit('changed');
  } finally {
    saving.value = false;
  }
}

async function cancelEdit(): Promise<void> {
  if (dirty.value) {
    await ElMessageBox.confirm('尚有未保存修改，确定放弃吗？', '放弃修改', { type: 'warning' });
  }
  editing.value = false;
}

/** USlideover 的 update:open：打开直通；关闭（X / ESC / 点遮罩）有未保存修改时先确认。 */
async function onOpenChange(open: boolean): Promise<void> {
  if (!open && dirty.value) {
    try {
      await ElMessageBox.confirm('尚有未保存修改，关闭后将丢失，确定关闭吗？', '未保存修改', { type: 'warning' });
    } catch { return; }
  }
  emit('update:modelValue', open);
}

async function changeStatus(): Promise<void> {
  if (!detail.value) return;
  const active = !isActive.value;
  const action = active ? '恢复合作' : '暂停合作';
  try {
    const prompt = await ElMessageBox.prompt(
      active ? '请填写恢复合作原因，恢复后可继续新建采购业务。' : '请填写暂停合作原因，历史单据仍可查看。',
      action,
      { inputPlaceholder: `请输入${action}原因`, inputValidator: (value) => Boolean(value?.trim()) || '原因不能为空' },
    );
    detail.value = await updateSupplierStatus(props.factoryId, detail.value, active, prompt.value);
    ElMessage.success(`${action}成功`);
    emit('changed');
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') console.error('Supplier status update failed', error);
  }
}

function resetState(): void {
  editing.value = false;
  activeTab.value = 'profile';
  detail.value = null;
  history.value = [];
  materialRelations.value = [];
  originalForm.value = '';
}

async function ensureMaterialOptions(): Promise<void> {
  const [materials, allUnits, purchaseUnits] = await Promise.all([
    materialOptions.value.length
      ? Promise.resolve(null)
      : get<Array<{ id: string; name: string; code?: string | null; unit?: string | null }>>(
        `/${props.factoryId}/raw-material-types/active`,
      ),
    allUnitOptions.value.length ? Promise.resolve(null) : listSystemUnits(props.factoryId),
    purchaseUnitOptions.value.length
      ? Promise.resolve(null)
      : listSystemUnits(props.factoryId, 'PURCHASE_QUANTITY'),
  ]);
  if (materials) materialOptions.value = materials.data ?? [];
  if (allUnits) allUnitOptions.value = allUnits.data ?? [];
  if (purchaseUnits) purchaseUnitOptions.value = purchaseUnits.data ?? [];
}

async function openRelationCreate(): Promise<void> {
  if (!isActive.value) return void ElMessage.warning('供应商已暂停合作，不能新增供应原料关系');
  await ensureMaterialOptions();
  relationEditingId.value = '';
  Object.assign(relationForm, {
    materialTypeId: '', supplierMaterialCode: '', defaultPurchasePrice: null, taxIncludedDefaultPurchasePrice: null, currency: 'CNY', purchaseUnit: '',
    minOrderQuantity: null, leadTimeDays: null, preferred: false, active: true, version: undefined,
  });
  inlineSpecEnabled.value = false;
  resetInlineSpecForm();
  relationDialogVisible.value = true;
}

function resetInlineSpecForm(): void {
  Object.assign(inlineSpecForm, { name: '', purchasePackageUnit: '', factor: 1, quotedPrice: null, taxIncludedQuotedPrice: null });
}

/* ═══════════════ 缺陷 17: 价格必填 —— 但只在价格被动过时拦 ═══════════════
 *
 * 🔴 这里的校验是**手写**的, 不是 el-form 的 rules。
 *    本文件三个 <el-form> 里, 只有供应商基本信息那个(:44)真正接了
 *    `:model` + `:rules` + `ref`; 采购关系(:188)/内联规格(:233)两个三样全无,
 *    独立规格(:290)只有 `:model`。全文件**没有任何地方调用 formRef.validate()**。
 *    ⇒ el-form-item 上的 `required` 在这三个表单里**只画一个红星, 不拦任何东西**。
 *    在价格上加 `required` 会得到一个截图上"看起来修好了"、实际零约束的假修复。
 *
 * 🔒 owner 裁定「只在价格被动过时拦」的理由(照此解释边界情况):
 *    LIUSHANMEN 存量有 4 条价格为空的关系(DEMO235005→WL026/WL065/WL130、上海鹃城→YL063)。
 *    一律拦会让"只想把某条从启用改成停用"的人先编一个价格出来 ——
 *    ⛔ 而编出来的价格比空着更糟: 空的还能被发现, 编的会一直安静地错下去。
 */

/**
 * 价格本身合不合法。
 *
 * ⚠️ 判据是 **> 0** 而不是"非空", 因为后端就是这么判的:
 *   SupplierMaterialServiceImpl:116        → SUPPLIER_MATERIAL_PRICE_INVALID
 *   SupplierMaterialPurchaseSpecServiceImpl:134 → PURCHASE_SPEC_PRICE_INVALID
 * 只拦空值会让前端放行 0, 然后被接口 400 打回 —— 那是把一个死胡同挪到了下一步。
 */
function priceProblem(value: number | null | undefined): string | null {
  if (value == null) return '请填写价格';
  if (!(Number(value) > 0)) return '价格必须大于 0';
  return null;
}

function onMaterialPick(value: string | undefined): void {
  relationForm.materialTypeId = value ?? '';
  onRelationMaterialChange(relationForm.materialTypeId);
}

function onRelationMaterialChange(materialTypeId: string): void {
  if (relationEditingId.value) return;
  const material = materialOptions.value.find((row) => row.id === materialTypeId);
  const resolution = resolvePurchaseUnits(material?.unit, allUnitOptions.value, purchaseUnitOptions.value);
  relationForm.purchaseUnit = resolution.defaultUnit;
}

/**
 * 🔒 缺陷 16: 两个入口合一 —— 这个函数现在既服务"编辑"也服务"查看"。
 * ⚠️ 不再在这里挡 `!isActive`：以前"编辑"按钮本身就被 `v-if="canWrite && isActive"`
 * 藏起来了, 这条早退是防御性的、从未真正被点到; 而旧的"采购规格"按钮**没有**这层门槛,
 * 供应商暂停合作时仍然可以查看规格。合并成一个按钮之后, 这个能力必须保留 ——
 * 门槛移到 `relationWritable`(只挡"能不能保存", 不挡"能不能看")。
 */
async function openRelationEdit(row: SupplierMaterialRelation): Promise<void> {
  if (!detail.value) return;
  await ensureMaterialOptions();
  relationEditingId.value = row.id;
  Object.assign(relationForm, {
    materialTypeId: row.materialTypeId,
    supplierMaterialCode: row.supplierMaterialCode ?? '',
    defaultPurchasePrice: row.defaultPurchasePrice ?? null,
    // 🔴 只回填后端存的含税值。⛔ 不从未税反推 —— 存量行的基准我们不知道,
    //    反推出来的数会以「用户填过」的样子出现在输入框里。
    taxIncludedDefaultPurchasePrice: row.taxIncludedDefaultPurchasePrice ?? null,
    currency: row.currency || 'CNY',
    purchaseUnit: row.purchaseUnit || row.baseUnit || '',
    minOrderQuantity: row.minOrderQuantity ?? null,
    leadTimeDays: row.leadTimeDays ?? null,
    preferred: Boolean(row.preferred),
    active: row.active !== false,
    version: row.version ?? undefined,
  });
  // 编辑: 记下打开时的价格。用户没动它就不拦 —— 存量空价格的关系仍然可以改别的字段。
  inlineSpecEnabled.value = false;
  resetInlineSpecForm();
  // 缺陷 16: 两个弹窗合一之后, 这里顺带做以前 openSpecManager() 做的事 ——
  // 不然编辑/查看弹窗里的采购规格列表会是空的。
  specRelation.value = row;
  purchaseSpecs.value = await listSupplierPurchaseSpecs(props.factoryId, detail.value.id, row.id);
  showNewSpecForm.value = false;
  resetSpecForm();
  relationDialogVisible.value = true;
}

async function saveRelation(): Promise<void> {
  if (!detail.value) return;
  if (!isActive.value) return void ElMessage.warning('供应商已暂停合作，不能保存供应原料关系');
  if (!relationForm.materialTypeId) return void ElMessage.warning('请选择物料');
  if (relationUnitReason.value) return void ElMessage.warning(relationUnitReason.value);
  // 缺陷 17-A ①: 含税默认采购价 —— 新增与编辑【一律必填】。
  //
  // 🔒 owner 2026-09-02 改判: 原先是「只在价格被动过时拦」(#451), 现在是「一律必填,
  //    存量也得补」。⇒ 编辑一条空价格的存量关系, 在补上价格之前存不下去。
  //
  // ⚠️ owner 是在**知道代价**的前提下选的 A: 逼人填会促使人编一个价格出来, 而编出来的
  //    价格比空着更危险 —— 空的还会在 submitOrder 被拦, 编的会一路静默进应付。
  //    ⇒ 那是他的选择, ⛔ 不是可以在这里设计规避的东西。
  const problem = priceProblem(relationForm.taxIncludedDefaultPurchasePrice);
  if (problem) return void ElMessage.warning(`含税默认采购价：${problem}`);
  const wantsInlineSpec = !relationEditingId.value && inlineSpecEnabled.value;
  if (wantsInlineSpec) {
    if (!inlineSpecForm.name.trim()) return void ElMessage.warning('请输入采购规格名称');
    if (!inlineSpecForm.purchasePackageUnit) return void ElMessage.warning('请选择采购规格的采购包装单位');
    if (!(Number(inlineSpecForm.factor) > 0)) return void ElMessage.warning('采购规格的换算系数必须大于 0');
    // 缺陷 17-A ②: 内联规格的含税报价 —— 与 ①③ 同一条规则。
    // 🔴 这条分支只在【新增】时可达(wantsInlineSpec 自带 !relationEditingId),
    //    本来就是无条件必填; 17-A 之后三条路径规则一致。
    //    它是空价格最主要的来源 —— 只修 ① 和 ③ 等于堵了编辑口, 前门照开。
    const specProblem = priceProblem(inlineSpecForm.taxIncludedQuotedPrice);
    if (specProblem) return void ElMessage.warning(`采购规格的含税报价：${specProblem}`);
  }
  const payload: SupplierMaterialPayload = {
    ...relationForm,
    supplierMaterialCode: relationForm.supplierMaterialCode?.trim(),
    // 🔒 币种输入已按 owner 裁定收起 (缺陷 21): 目前只可能人民币。
    //    这里仍然带上它 —— ⛔ 不删数据库列, 也不改后端契约; 后端
    //    SupplierMaterialServiceImpl.SUPPLIER_MATERIAL_CURRENCY 才是最终口径。
    currency: relationForm.currency.trim().toUpperCase(),
    purchaseUnit: relationForm.purchaseUnit.trim(),
  };
  relationSaving.value = true;
  try {
    let savedRelation: SupplierMaterialRelation;
    if (relationEditingId.value) {
      savedRelation = await updateSupplierMaterial(props.factoryId, detail.value.id, relationEditingId.value, payload);
    } else {
      savedRelation = await createSupplierMaterial(props.factoryId, detail.value.id, payload);
    }
    if (wantsInlineSpec) {
      // 🔒 缺陷 16 phase 1: minOrderQuantity/leadTimeDays 已从规格 payload 移除 ——
      // 起订量/交期只在供应关系层(payload 之上的 relationForm)录入一次。
      const specPayload: SupplierPurchaseSpecPayload = {
        name: inlineSpecForm.name.trim(),
        purchasePackageUnit: inlineSpecForm.purchasePackageUnit,
        inventoryBaseUnit: savedRelation.baseUnit || payload.purchaseUnit,
        factor: Number(inlineSpecForm.factor),
        taxIncludedQuotedPrice: inlineSpecForm.taxIncludedQuotedPrice,
        currency: payload.currency,
        defaultSpec: true,
        active: true,
      };
      await createSupplierPurchaseSpec(props.factoryId, detail.value.id, savedRelation.id, specPayload);
    }
    materialRelations.value = await listSupplierMaterials(props.factoryId, detail.value.id);
    relationDialogVisible.value = false;
    ElMessage.success(wantsInlineSpec ? '供应原料关系与采购规格已保存' : '供应原料关系已保存');
  } finally {
    relationSaving.value = false;
  }
}

async function removeRelation(row: SupplierMaterialRelation): Promise<void> {
  if (!detail.value) return;
  if (!isActive.value) return void ElMessage.warning('供应商已暂停合作，不能修改供应原料关系');
  await ElMessageBox.confirm('确定停用此供应原料关系吗？历史采购记录不会删除，后续可编辑恢复。', '停用关系', { type: 'warning' });
  await deleteSupplierMaterial(props.factoryId, detail.value.id, row.id, row.version);
  materialRelations.value = await listSupplierMaterials(props.factoryId, detail.value.id);
  ElMessage.success('供应原料关系已停用');
}

// 🔒 缺陷 16: openSpecManager() 已删 —— 它做的事(加载规格列表 + 重置规格子表单 +
// 打开弹窗)现在是 openRelationEdit() 的一部分, 两个入口合一之后不再需要独立函数。
// 旧的 `vm.openSpecManager(row)` 调用点(测试)改调 `vm.openRelationEdit(row)`。

function resetSpecForm(): void {
  specEditingId.value = '';
  // 🔒 缺陷 16 phase 1: minOrderQuantity/leadTimeDays 已从规格表单移除。
  Object.assign(specForm, {
    name: '', purchasePackageUnit: '', inventoryBaseUnit: specRelation.value?.baseUnit || '', factor: 1, quotedPrice: null, taxIncludedQuotedPrice: null, currency: 'CNY',
    defaultSpec: false, active: true, version: undefined,
  });
}

function editPurchaseSpec(row: SupplierPurchaseSpec): void {
  showNewSpecForm.value = true;
  specEditingId.value = row.id;
  // 🔒 缺陷 16 phase 1: minOrderQuantity/leadTimeDays 已从规格表单移除。
  Object.assign(specForm, {
    name: row.name,
    purchasePackageUnit: row.purchasePackageUnit,
    inventoryBaseUnit: row.inventoryBaseUnit,
    factor: Number(row.factor),
    quotedPrice: row.quotedPrice ?? null,
    taxIncludedQuotedPrice: row.taxIncludedQuotedPrice ?? null,   // ⛔ 同样不反推
    currency: row.currency || 'CNY',
    defaultSpec: Boolean(row.defaultSpec),
    active: row.active !== false,
    version: row.version ?? undefined,
  });
  // 编辑既有规格: 记下打开时的报价, 没动就不拦。
}

async function savePurchaseSpec(): Promise<void> {
  if (!detail.value || !specRelation.value) return;
  if (!isActive.value) return void ElMessage.warning('供应商已暂停合作，不能维护采购规格');
  if (!specForm.name.trim()) return void ElMessage.warning('请输入规格名称');
  if (!specForm.purchasePackageUnit) return void ElMessage.warning('请选择采购包装单位');
  if (!(Number(specForm.factor) > 0)) return void ElMessage.warning('换算系数必须大于 0');
  // 缺陷 17 ③: 独立规格的未税报价(编辑路径)。新增必填; 编辑只在动过时才校验。
  // 缺陷 17-A ③: 与 ① 同一条规则 —— 新增与编辑一律必填。
  const problem = priceProblem(specForm.taxIncludedQuotedPrice);
  if (problem) return void ElMessage.warning(`含税报价：${problem}`);
  const payload: SupplierPurchaseSpecPayload = {
    ...specForm,
    name: specForm.name.trim(),
    inventoryBaseUnit: specRelation.value.baseUnit || specForm.inventoryBaseUnit,
    currency: specForm.currency.trim().toUpperCase(),
  };
  specSaving.value = true;
  try {
    if (specEditingId.value) {
      await updateSupplierPurchaseSpec(
        props.factoryId, detail.value.id, specRelation.value.id, specEditingId.value, payload,
      );
    } else {
      await createSupplierPurchaseSpec(props.factoryId, detail.value.id, specRelation.value.id, payload);
    }
    purchaseSpecs.value = await listSupplierPurchaseSpecs(props.factoryId, detail.value.id, specRelation.value.id);
    resetSpecForm();
    ElMessage.success('采购规格已保存');
  } finally {
    specSaving.value = false;
  }
}

/** 点「停用」只是请求确认（打开确认弹窗）；真正的停用在 confirmStopSpec。 */
function removePurchaseSpec(row: SupplierPurchaseSpec): void {
  if (!detail.value || !specRelation.value) return;
  specToStop.value = row;
}

async function confirmStopSpec(): Promise<void> {
  const row = specToStop.value;
  if (!row || !detail.value || !specRelation.value) return;
  specStopping.value = true;
  try {
    await deleteSupplierPurchaseSpec(
      props.factoryId, detail.value.id, specRelation.value.id, row.id, row.version,
    );
    purchaseSpecs.value = await listSupplierPurchaseSpecs(props.factoryId, detail.value.id, specRelation.value.id);
    specToStop.value = null;
  } finally {
    specStopping.value = false;
  }
}

function formatMoney(value: number | null | undefined): string {
  return value == null ? '-' : `¥${Number(value).toFixed(2)}`;
}
</script>

<style scoped>
.drawer-title { display: inline-flex; align-items: center; gap: var(--cs-space-2); color: var(--cs-text); }
.drawer-meta { display: inline-flex; align-items: center; gap: var(--cs-space-2); margin-top: var(--cs-space-1); color: var(--cs-muted); font-size: var(--cs-font-small); }
.header-actions, .edit-actions, .tab-toolbar, .row-actions, .spec-actions { display: flex; align-items: center; gap: var(--cs-space-2); }
.skeleton { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.profile-form { display: flex; flex-direction: column; gap: var(--cs-space-3); margin-top: var(--cs-space-3); }
.info-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-3); margin: var(--cs-space-3) 0 0; padding: var(--cs-space-3) var(--cs-space-4); background: var(--cs-surface-subtle); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }
.info-grid dt { font-size: var(--cs-font-small); color: var(--cs-muted); }
.info-grid dd { margin: 2px 0 0; color: var(--cs-text); word-break: break-all; }
.info-grid .wide { grid-column: 1 / -1; }
.edit-actions, .spec-actions { justify-content: flex-end; }
.spec-actions { margin-top: var(--cs-space-3); }
.tab-toolbar { justify-content: space-between; margin: var(--cs-space-3) 0; color: var(--cs-muted); font-size: var(--cs-font-small); }
.relation-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-3) var(--cs-space-4); }
.relation-form .wide { grid-column: 1 / -1; }
.relation-unit-alert { margin-top: var(--cs-space-2); }
.field-hint { color: var(--cs-muted); font-size: var(--cs-font-small); }
.muted { color: var(--cs-muted); }
.confirm-text { margin: 0; color: var(--cs-text); }
/* 缺陷 35: 基准未知的存量价格 —— 视觉上要与「已确认含税」区分开,
   ⛔ 但不能弱到看不见: 它是一个需要人去确认的状态, 不是次要信息。 */
.price-basis-unknown { color: var(--cs-warning); }
.inline-spec-toggle, .spec-add-toggle { display: flex; justify-content: center; margin: var(--cs-space-1) 0 var(--cs-space-3); }
.inline-spec-form { margin-top: var(--cs-space-1); }
</style>
<style>
/* USlideover 自带 sm:max-w-md，同名工具类合并不掉；宽度用一条全局规则（同 production/outsource） */
.supplier-slide { width: 100%; max-width: min(880px, 92vw) !important; }
</style>
