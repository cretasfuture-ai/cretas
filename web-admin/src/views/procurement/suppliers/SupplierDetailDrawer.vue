<template>
  <el-drawer
    :model-value="modelValue"
    size="760px"
    :before-close="beforeClose"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="drawer-header">
        <div>
          <div class="drawer-title">
            <el-tag v-if="detail?.shortName" type="primary" size="small" effect="plain" class="short-name-tag">
              {{ detail.shortName }}
            </el-tag>
            {{ detail?.name || supplier?.name || '供应商详情' }}
          </div>
          <div class="drawer-meta">
            {{ detail?.supplierCode || detail?.code || '-' }}
            <el-tag :type="isActive ? 'success' : 'info'" size="small">{{ isActive ? '合作中' : '暂停合作' }}</el-tag>
            <el-tag v-if="detail && !supplierProfileComplete(detail)" type="warning" size="small">资料不完整</el-tag>
          </div>
        </div>
        <div v-if="canWrite" class="header-actions">
          <el-button v-if="!editing" @click="startEdit">编辑资料</el-button>
          <el-button v-if="!editing" :type="isActive ? 'warning' : 'success'" plain @click="changeStatus">
            {{ isActive ? '暂停合作' : '恢复合作' }}
          </el-button>
        </div>
      </div>
    </template>

    <el-skeleton v-if="loading" :rows="8" animated />
    <template v-else-if="detail">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="基本资料" name="profile">
          <el-alert
            v-if="!supplierProfileComplete(detail) && !editing"
            title="资料不完整：历史数据可继续查看，编辑保存时必须补齐联系人、电话和地址。"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-form
            v-if="editing"
            ref="formRef"
            :model="form"
            :rules="supplierFormRules"
            label-width="110px"
            class="profile-form"
          >
            <el-form-item label="供应商名称" prop="name"><el-input v-model="form.name" maxlength="200" /></el-form-item>
            <el-form-item label="简称" prop="shortName">
              <el-input v-model="form.shortName" maxlength="50" show-word-limit placeholder="选填，下拉里优先显示，例如「飞熊」" />
              <div class="field-hint">留空时下拉显示全称。同一工厂内简称不能重复。</div>
            </el-form-item>
            <el-form-item label="联系人" prop="contactPerson"><el-input v-model="form.contactPerson" maxlength="100" /></el-form-item>
            <el-form-item label="联系电话" prop="phone"><el-input v-model="form.phone" maxlength="40" /></el-form-item>
            <el-form-item label="地址" prop="address"><el-input v-model="form.address" type="textarea" maxlength="500" show-word-limit /></el-form-item>
            <el-form-item label="邮箱"><el-input v-model="form.email" maxlength="100" /></el-form-item>
            <el-form-item label="银行账户"><el-input v-model="form.bankAccount" maxlength="100" /></el-form-item>
            <el-form-item label="税号"><el-input v-model="form.taxNumber" maxlength="50" /></el-form-item>
            <el-form-item label="备注"><el-input v-model="form.notes" type="textarea" maxlength="5000" show-word-limit /></el-form-item>
            <div class="edit-actions">
              <el-button @click="cancelEdit">取消</el-button>
              <el-button type="primary" :loading="saving" @click="saveProfile">保存资料</el-button>
            </div>
          </el-form>
          <el-descriptions v-else :column="2" border class="profile-descriptions">
            <el-descriptions-item label="供应商名称">{{ detail.name || '资料不完整' }}</el-descriptions-item>
            <el-descriptions-item label="简称">{{ detail.shortName || '未设置（下拉里显示全称）' }}</el-descriptions-item>
            <el-descriptions-item label="联系人">{{ detail.contactPerson || '资料不完整' }}</el-descriptions-item>
            <el-descriptions-item label="联系电话">{{ detail.phone || detail.contactPhone || '资料不完整' }}</el-descriptions-item>
            <el-descriptions-item label="邮箱">{{ detail.email || '-' }}</el-descriptions-item>
            <el-descriptions-item label="地址" :span="2">{{ detail.address || '资料不完整' }}</el-descriptions-item>
            <el-descriptions-item label="银行账户">{{ detail.bankAccount || '-' }}</el-descriptions-item>
            <el-descriptions-item label="税号">{{ detail.taxNumber || detail.taxId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="备注" :span="2">{{ detail.notes || '-' }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>

        <el-tab-pane label="联系人/地址/账户" name="collections">
          <SupplierProfileCollections
            v-if="detail"
            :factory-id="factoryId"
            :supplier-id="detail.id"
            :supplier-name="detail.name"
            :can-write="canWrite && isActive"
            @changed="onCollectionsChanged"
          />
        </el-tab-pane>

        <el-tab-pane label="供应原料" name="materials">
          <el-alert
            v-if="!isActive"
            type="warning"
            :closable="false"
            show-icon
            title="供应商已暂停合作，供应原料关系仅可查看；恢复合作后才能新增或修改。"
            class="relation-status-alert"
          />
          <div class="tab-toolbar">
            <span>独立供应关系主数据；采购历史在“采购记录”中展示。</span>
            <el-button
              v-if="canWrite"
              type="primary"
              plain
              :disabled="!isActive"
              @click="openRelationCreate"
            >管理供应原料</el-button>
          </div>
          <el-table :data="materialRelations" border stripe empty-text="暂无供应原料关系">
            <el-table-column label="物料" min-width="190" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ row.materialName || row.materialTypeId }}</div>
                <small>{{ row.materialCode || row.materialTypeId }}</small>
              </template>
            </el-table-column>
            <el-table-column label="供应商料号" width="130">
              <template #default="{ row }">{{ row.supplierMaterialCode || '-' }}</template>
            </el-table-column>
            <el-table-column label="默认采购价" width="145" align="right">
              <template #default="{ row }">
                {{ row.defaultPurchasePrice == null ? '未设置' : `${row.currency || 'CNY'} ${Number(row.defaultPurchasePrice).toFixed(2)}/${displayUnit(row.purchaseUnit)}` }}
              </template>
            </el-table-column>
            <el-table-column label="起订量" width="105" align="right">
              <template #default="{ row }">{{ row.minOrderQuantity ?? '-' }} {{ displayUnit(row.purchaseUnit) }}</template>
            </el-table-column>
            <el-table-column label="交期" width="85">
              <template #default="{ row }">{{ row.leadTimeDays == null ? '-' : `${row.leadTimeDays}天` }}</template>
            </el-table-column>
            <el-table-column label="关系状态" width="105">
              <template #default="{ row }">
                <el-tag :type="row.active === false ? 'info' : 'success'" size="small">
                  {{ row.active === false ? '已停用' : row.preferred ? '首选' : '启用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="openSpecManager(row)">采购规格</el-button>
                <el-button v-if="canWrite && isActive" type="primary" link @click="openRelationEdit(row)">编辑</el-button>
                <el-button v-if="canWrite && isActive && row.active !== false" type="warning" link @click="removeRelation(row)">停用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="价格/报价" name="prices">
          <el-empty description="当前后端未提供独立供应商报价台账；历史价格仍保留在采购单据中。" />
        </el-tab-pane>
        <el-tab-pane label="采购记录" name="purchases">
          <el-table :data="history" border stripe empty-text="暂无采购记录摘要">
            <el-table-column prop="materialName" label="物料" min-width="180" />
            <el-table-column prop="purchaseCount" label="采购次数" width="100" />
            <el-table-column prop="lastPurchaseDate" label="最近采购日期" width="140" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="对账/应付" name="payables">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="采购单数">{{ detail.totalOrders ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="最近采购">{{ detail.lastOrderDate || '-' }}</el-descriptions-item>
            <el-descriptions-item label="累计采购金额">{{ formatMoney(detail.totalAmount) }}</el-descriptions-item>
            <el-descriptions-item label="当前应付余额">{{ formatMoney(detail.currentBalance) }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
        <el-tab-pane label="变更记录" name="changes">
          <el-timeline>
            <el-timeline-item v-if="detail.createdAt" :timestamp="detail.createdAt">
              创建供应商 · {{ detail.createdByName || '系统记录' }}
            </el-timeline-item>
            <el-timeline-item v-if="detail.updatedAt" :timestamp="detail.updatedAt" type="primary">
              最近更新
            </el-timeline-item>
          </el-timeline>
          <el-empty v-if="!detail.createdAt && !detail.updatedAt" description="暂无可展示的变更记录" />
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-dialog
      v-model="relationDialogVisible"
      :title="relationEditingId ? '编辑供应原料关系' : '新增供应原料关系'"
      width="620px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form label-width="120px">
        <el-form-item label="物料" required>
          <el-select v-model="relationForm.materialTypeId" filterable :disabled="Boolean(relationEditingId)" style="width: 100%" @change="onRelationMaterialChange">
            <el-option
              v-for="material in materialOptions"
              :key="material.id"
              :label="`${material.name} (${material.code || material.id})`"
              :value="material.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商料号"><el-input v-model="relationForm.supplierMaterialCode" maxlength="100" /></el-form-item>
        <el-form-item label="采购单位" required>
          <UnitSelect
            v-model="relationForm.purchaseUnit"
            :factory-id="factoryId"
            :options="relationPurchaseUnitOptions"
            usage-scope="PURCHASE_QUANTITY"
            placeholder="请选择采购单位"
          />
          <el-alert
            v-if="relationUnitReason"
            class="relation-unit-alert"
            type="error"
            :closable="false"
            show-icon
            :title="relationUnitReason"
          />
          <div class="field-hint">采购订单将沿用该单位；系统内部保存标准单位代码，页面只显示中文业务单位。</div>
        </el-form-item>
        <!-- 🔒 19②: 人填含税, 未税只读派生 (owner: 「未税报价……只能是自动计算出来」)。
             ⚠️ :218 原本【一个基准都没说】—— 用户填 50, 系统不知道那是含税还是未税,
             而它会一路流到采购单和入库批次。现在标题就把基准说清楚。 -->
        <el-form-item :label="isExemptMaterial(relationMaterial) ? `免税采购价（元/${displayUnit(relationForm.purchaseUnit) || '采购单位'}）` : `含税默认采购价（元/${displayUnit(relationForm.purchaseUnit) || '采购单位'}）`">
          <el-input-number v-model="relationForm.taxIncludedDefaultPurchasePrice" :min="0" :precision="4" controls-position="right" style="width: 100%" />
          <!-- 形态 B⁵ 第二次打在同一句 hint 上: 缺陷 17 改过一次(价格从选填变必填),
               owner 2026-09-02 改判 A(一律必填、存量也要补)之后又得改一次。
               ⛔ 删掉一条承诺就要在同一个 commit 里删掉它的描述 —— 过期的描述比没有描述更糟,
               它会让下一个人跳过核实。

               ⚠️ 这段注释刻意**不复述**那两句已被推翻的原文。
               `supplierMaterialRelation.source.spec.ts` 有一条阴性对照钉着它们不许回来,
               而源码闸是按字面匹配的 —— 把被禁的措辞抄进注释, 闸就会匹配到注释本身,
               既弄红了正确的代码, 又正是本仓记过的「闸把自己的注释当成被测对象」那个形态。 -->
          <div class="field-hint">
            价格与采购单位绑定，按含税价填写。新增与编辑都必须填写；存量没有价格的关系，需要补上价格才能保存。
          </div>
        </el-form-item>
        <!-- ⛔ 算不出来时【不渲染】这一行, 而不是显示 0 或空白 —— 空白会被读成 0。
             镜像 material-types/list.vue:1772 的 v-if 写法。 -->
        <el-form-item v-if="relationDerivedUntaxed != null" :label="`未税采购价（元/${displayUnit(relationForm.purchaseUnit) || '采购单位'}）`">
          <el-input :model-value="relationDerivedUntaxed.toFixed(4)" disabled style="width: 100%" />
          <div class="field-hint">{{ isExemptMaterial(relationMaterial) ? '免税时未税价等于采购价' : '= 含税价 ÷ (1 + 税率)，按物料税率自动计算，不需要填写' }}</div>
        </el-form-item>
        <div v-else-if="relationForm.taxIncludedDefaultPurchasePrice != null" class="field-hint">
          该物料未配置税率，暂时算不出未税价。⛔ 这不代表未税价等于含税价。
        </div>
        <div v-else-if="relationEditingId && relationForm.defaultPurchasePrice != null" class="field-hint">
          ⚠️ 这条关系是在「含税/未税」区分之前录入的，存量价格 {{ relationForm.defaultPurchasePrice }} 的基准未知。
          填写含税价即可确认；在此之前它不会被用来自动带价。
        </div>
        <el-form-item label="最小起订量"><el-input-number v-model="relationForm.minOrderQuantity" :min="0.0001" :precision="4" style="width: 100%" /></el-form-item>
        <el-form-item label="交期（天）"><el-input-number v-model="relationForm.leadTimeDays" :min="0" :precision="0" style="width: 100%" /></el-form-item>
        <el-form-item label="首选供应商"><el-switch v-model="relationForm.preferred" /></el-form-item>
        <el-form-item label="关系启用"><el-switch v-model="relationForm.active" /></el-form-item>
      </el-form>
      <template v-if="!relationEditingId">
        <el-divider><span class="inline-spec-divider">采购规格（选填，不填按 {{ displayUnit(relationForm.purchaseUnit) || '库存基本单位' }} 直采）</span></el-divider>
        <div v-if="!inlineSpecEnabled" class="inline-spec-toggle">
          <el-button type="primary" plain @click="inlineSpecEnabled = true">+ 顺手新增一个采购规格（例如「10kg/箱」）</el-button>
        </div>
        <template v-else>
          <el-form label-width="120px" class="inline-spec-form">
            <el-form-item label="规格名称" required><el-input v-model="inlineSpecForm.name" maxlength="100" placeholder="例如 10kg/箱" /></el-form-item>
            <el-form-item label="采购包装单位" required>
              <UnitSelect v-model="inlineSpecForm.purchasePackageUnit" :factory-id="factoryId" :options="purchaseUnitOptions" usage-scope="PURCHASE_QUANTITY" />
            </el-form-item>
            <el-form-item label="换算系数" required>
              <el-input-number v-model="inlineSpecForm.factor" :min="0.0001" :precision="4" style="width: 220px" />
              <span class="field-hint">1 {{ displayUnit(inlineSpecForm.purchasePackageUnit) }} = {{ inlineSpecForm.factor || '?' }} {{ displayUnit(relationForm.purchaseUnit) || '库存基本单位' }}</span>
            </el-form-item>
            <el-form-item :label="isExemptMaterial(relationMaterial) ? `免税报价（元/${displayUnit(inlineSpecForm.purchasePackageUnit) || '采购包装单位'}）` : `含税报价（元/${displayUnit(inlineSpecForm.purchasePackageUnit) || '采购包装单位'}）`">
              <el-input-number v-model="inlineSpecForm.taxIncludedQuotedPrice" :min="0" :precision="4" style="width: 220px" />
            </el-form-item>
            <el-form-item v-if="inlineSpecDerivedUntaxed != null" :label="`未税报价（元/${displayUnit(inlineSpecForm.purchasePackageUnit) || '采购包装单位'}）`">
              <el-input :model-value="inlineSpecDerivedUntaxed.toFixed(4)" disabled style="width: 220px" />
              <span class="field-hint">{{ isExemptMaterial(relationMaterial) ? '免税时未税价等于报价' : '= 含税报价 ÷ (1 + 税率)，自动计算' }}</span>
            </el-form-item>
          </el-form>
          <div class="inline-spec-toggle">
            <el-button @click="inlineSpecEnabled = false; resetInlineSpecForm()">不加了，收起</el-button>
          </div>
        </template>
      </template>
      <template #footer>
        <el-button @click="relationDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="relationSaving" @click="saveRelation">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="specDialogVisible"
      title="采购规格"
      width="760px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-alert
        :closable="false"
        type="info"
        show-icon
        :title="`${specRelation?.materialName || '当前物料'} · 库存基本单位 ${displayUnit(specRelation?.baseUnit)}`"
        description="采购订单选择规格后按包装数量下单，系统按换算系数折合库存基本单位；换算系数由后端快照锁定。"
        style="margin-bottom: 12px"
      />
      <el-table :data="purchaseSpecs" border stripe empty-text="暂无采购规格；采购订单将按库存基本单位直采">
        <el-table-column prop="name" label="规格名称" min-width="130" />
        <el-table-column label="采购包装单位" width="120"><template #default="{ row }">{{ displayUnit(row.purchasePackageUnit) }}</template></el-table-column>
        <el-table-column label="换算" min-width="150"><template #default="{ row }">1 {{ displayUnit(row.purchasePackageUnit) }} = {{ row.factor }} {{ displayUnit(row.inventoryBaseUnit) }}</template></el-table-column>
        <el-table-column label="报价" width="150"><template #default="{ row }">{{ row.quotedPrice == null ? '-' : `${row.currency || 'CNY'} ${Number(row.quotedPrice).toFixed(2)}/${displayUnit(row.purchasePackageUnit)}` }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.active === false ? 'info' : 'success'">{{ row.active === false ? '停用' : row.defaultSpec ? '默认' : '启用' }}</el-tag></template></el-table-column>
        <el-table-column v-if="canWrite && isActive" label="操作" width="130">
          <template #default="{ row }">
            <el-button type="primary" link @click="editPurchaseSpec(row)">编辑</el-button>
            <el-button v-if="row.active !== false" type="warning" link @click="removePurchaseSpec(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template v-if="canWrite && isActive">
        <div v-if="!specEditingId && !showNewSpecForm" class="spec-add-toggle">
          <el-button type="primary" plain @click="showNewSpecForm = true">+ 新增规格</el-button>
        </div>
        <template v-else>
          <el-divider>{{ specEditingId ? '编辑规格' : '新增规格' }}</el-divider>
          <el-form label-width="125px" :model="specForm">
            <el-form-item label="规格名称" required><el-input v-model="specForm.name" maxlength="100" placeholder="例如 10kg/箱" /></el-form-item>
            <el-form-item label="采购包装单位" required>
              <UnitSelect v-model="specForm.purchasePackageUnit" :factory-id="factoryId" usage-scope="PURCHASE_QUANTITY" />
            </el-form-item>
            <el-form-item label="换算系数" required>
              <el-input-number v-model="specForm.factor" :min="0.0001" :precision="4" style="width: 220px" />
              <span class="field-hint">1 {{ displayUnit(specForm.purchasePackageUnit) }} = {{ specForm.factor || '?' }} {{ displayUnit(specRelation?.baseUnit) }}</span>
            </el-form-item>
            <el-form-item :label="isExemptMaterial(specMaterial) ? `免税报价（元/${displayUnit(specForm.purchasePackageUnit) || '采购包装单位'}）` : `含税报价（元/${displayUnit(specForm.purchasePackageUnit) || '采购包装单位'}）`"><el-input-number v-model="specForm.taxIncludedQuotedPrice" :min="0" :precision="4" style="width: 220px" /></el-form-item>
            <el-form-item v-if="specDerivedUntaxed != null" :label="`未税报价（元/${displayUnit(specForm.purchasePackageUnit) || '采购包装单位'}）`">
              <el-input :model-value="specDerivedUntaxed.toFixed(4)" disabled style="width: 220px" />
              <span class="field-hint">{{ isExemptMaterial(specMaterial) ? '免税时未税价等于报价' : '= 含税报价 ÷ (1 + 税率)，自动计算' }}</span>
            </el-form-item>
            <el-form-item label="最小起订量"><el-input-number v-model="specForm.minOrderQuantity" :min="0.0001" :precision="4" style="width: 220px" /></el-form-item>
            <el-form-item label="交期（天）"><el-input-number v-model="specForm.leadTimeDays" :min="0" :precision="0" style="width: 220px" /></el-form-item>
            <el-form-item label="默认规格"><el-switch v-model="specForm.defaultSpec" /></el-form-item>
            <el-form-item label="启用"><el-switch v-model="specForm.active" /></el-form-item>
            <el-form-item>
              <el-button v-if="specEditingId" @click="resetSpecForm">取消编辑</el-button>
              <el-button v-else @click="showNewSpecForm = false; resetSpecForm()">收起</el-button>
              <el-button type="primary" :loading="specSaving" @click="savePurchaseSpec">{{ specEditingId ? '保存规格' : '新增规格' }}</el-button>
            </el-form-item>
          </el-form>
        </template>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import { get } from '@/api/request';
import { listSystemUnits, type SystemUnit } from '@/api/systemUnits';
import UnitSelect from '@/components/common/UnitSelect.vue';
import SupplierProfileCollections from './SupplierProfileCollections.vue';
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
  supplierFormRules, supplierProfileComplete, supplierStatus, normalizeSupplierPayload,
} from './supplierModel';
import { toSupplierHistoryViewRow, type SupplierHistoryApiRow, type SupplierHistoryViewRow } from './supplierHistory';

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
const specDialogVisible = ref(false);
const specRelation = ref<SupplierMaterialRelation | null>(null);
const purchaseSpecs = ref<SupplierPurchaseSpec[]>([]);
const specEditingId = ref('');
const specSaving = ref(false);
const showNewSpecForm = ref(false);
const specForm = reactive<SupplierPurchaseSpecPayload>({
  name: '', purchasePackageUnit: '', inventoryBaseUnit: '', factor: 1, quotedPrice: null, taxIncludedQuotedPrice: null, currency: 'CNY',
  minOrderQuantity: null, leadTimeDays: null, defaultSpec: false, active: true,
});
const formRef = ref<FormInstance>();
const originalForm = ref('');
const form = reactive<SupplierSavePayload>({
  name: '', shortName: '', contactPerson: '', phone: '', address: '', email: '', bankAccount: '', taxNumber: '', notes: '',
});

const isActive = computed(() => detail.value ? supplierStatus(detail.value) === 'ACTIVE' : false);
const relationMaterial = computed(() => materialOptions.value.find((row) => row.id === relationForm.materialTypeId));

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
  if (!detail.value || !formRef.value) return;
  await formRef.value.validate();
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

async function beforeClose(done: () => void): Promise<void> {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('尚有未保存修改，关闭后将丢失，确定关闭吗？', '未保存修改', { type: 'warning' });
    } catch { return; }
  }
  done();
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

function onRelationMaterialChange(materialTypeId: string): void {
  if (relationEditingId.value) return;
  const material = materialOptions.value.find((row) => row.id === materialTypeId);
  const resolution = resolvePurchaseUnits(material?.unit, allUnitOptions.value, purchaseUnitOptions.value);
  relationForm.purchaseUnit = resolution.defaultUnit;
}

async function openRelationEdit(row: SupplierMaterialRelation): Promise<void> {
  if (!isActive.value) return void ElMessage.warning('供应商已暂停合作，不能修改供应原料关系');
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
      const specPayload: SupplierPurchaseSpecPayload = {
        name: inlineSpecForm.name.trim(),
        purchasePackageUnit: inlineSpecForm.purchasePackageUnit,
        inventoryBaseUnit: savedRelation.baseUnit || payload.purchaseUnit,
        factor: Number(inlineSpecForm.factor),
        taxIncludedQuotedPrice: inlineSpecForm.taxIncludedQuotedPrice,
        currency: payload.currency,
        minOrderQuantity: null,
        leadTimeDays: null,
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

async function openSpecManager(row: SupplierMaterialRelation): Promise<void> {
  if (!detail.value) return;
  specRelation.value = row;
  purchaseSpecs.value = await listSupplierPurchaseSpecs(props.factoryId, detail.value.id, row.id);
  showNewSpecForm.value = false;
  resetSpecForm();
  specDialogVisible.value = true;
}

function resetSpecForm(): void {
  specEditingId.value = '';
  Object.assign(specForm, {
    name: '', purchasePackageUnit: '', inventoryBaseUnit: specRelation.value?.baseUnit || '', factor: 1, quotedPrice: null, taxIncludedQuotedPrice: null, currency: 'CNY',
    minOrderQuantity: null, leadTimeDays: null, defaultSpec: false, active: true, version: undefined,
  });
}

function editPurchaseSpec(row: SupplierPurchaseSpec): void {
  showNewSpecForm.value = true;
  specEditingId.value = row.id;
  Object.assign(specForm, {
    name: row.name,
    purchasePackageUnit: row.purchasePackageUnit,
    inventoryBaseUnit: row.inventoryBaseUnit,
    factor: Number(row.factor),
    quotedPrice: row.quotedPrice ?? null,
    taxIncludedQuotedPrice: row.taxIncludedQuotedPrice ?? null,   // ⛔ 同样不反推

    currency: row.currency || 'CNY',
    minOrderQuantity: row.minOrderQuantity ?? null,
    leadTimeDays: row.leadTimeDays ?? null,
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

async function removePurchaseSpec(row: SupplierPurchaseSpec): Promise<void> {
  if (!detail.value || !specRelation.value) return;
  await ElMessageBox.confirm('停用后，新采购订单不能再选择该规格；历史订单快照不受影响。', '停用采购规格', { type: 'warning' });
  await deleteSupplierPurchaseSpec(
    props.factoryId, detail.value.id, specRelation.value.id, row.id, row.version,
  );
  purchaseSpecs.value = await listSupplierPurchaseSpecs(props.factoryId, detail.value.id, specRelation.value.id);
}

function formatMoney(value: number | null | undefined): string {
  return value == null ? '-' : `¥${Number(value).toFixed(2)}`;
}
</script>

<style scoped lang="scss">
.drawer-header { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.drawer-title { color: var(--el-text-color-primary); font-size: 18px; font-weight: 600; }
.drawer-meta { display: flex; align-items: center; gap: 8px; margin-top: 8px; color: var(--el-text-color-secondary); font-size: 13px; }
.header-actions, .edit-actions, .tab-toolbar { display: flex; align-items: center; gap: 8px; }
.profile-form { margin-top: 16px; }
.profile-descriptions { margin-top: 16px; }
.edit-actions { justify-content: flex-end; }
.tab-toolbar { justify-content: space-between; margin-bottom: 12px; color: var(--el-text-color-secondary); font-size: 13px; }
.contract-alert { margin-top: 12px; }
.field-hint { margin-left: 10px; color: var(--el-text-color-secondary); font-size: 12px; }
.inline-spec-divider { color: var(--el-text-color-secondary); font-size: 13px; font-weight: 400; }
.inline-spec-toggle, .spec-add-toggle { display: flex; justify-content: center; margin: 4px 0 12px; }
.inline-spec-form { margin-top: 4px; }
</style>
