<template>
  <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2 class="page-title">采购收票</h2>
      <p class="page-subtitle">采购单完成后自动进入待收票；供应商来票后登记并核对</p>
    </div>

    <!-- 逾期提醒卡 (Fool-proof Rule 5: 有待办就导航) -->
    <el-card
      v-if="reminderPoIds.length > 0"
      class="reminder-card"
      shadow="never"
    >
      <el-alert
        type="warning"
        :closable="false"
        title=""
        style="border: none; padding: 0; background: transparent;"
      >
        <template #default>
          <span>
            <strong>{{ reminderPoIds.length }} 张采购单</strong>
            逾期未收票，请及时催收：
          </span>
          <el-tag
            v-for="poId in reminderPoIds.slice(0, 5)"
            :key="poId"
            type="warning"
            size="small"
            style="margin-left: 6px; cursor: pointer"
            @click="filterByPoId(poId)"
          >
            {{ poId }}
          </el-tag>
          <span v-if="reminderPoIds.length > 5" style="margin-left: 6px; color: #E6A23C">
            ...等 {{ reminderPoIds.length }} 张
          </span>
        </template>
      </el-alert>
    </el-card>

    <el-card class="data-card todo-card" shadow="never">
      <template #header>
        <div class="card-title-row">
          <span>待收票任务</span>
          <el-tag type="warning" effect="plain">{{ pendingOrders.length }} 单</el-tag>
        </div>
      </template>
      <el-alert
        v-if="pendingOrdersError"
        type="error"
        :closable="false"
        title="待收票任务加载失败"
        description="网络或权限异常，现有待办未被清空。请重试，仍失败时联系管理员检查财务只读权限。"
        show-icon
        class="pending-error"
      />
      <el-button v-if="pendingOrdersError" type="primary" plain @click="fetchPendingOrders">重试加载</el-button>
      <el-table
        v-loading="pendingOrdersLoading"
        :data="pendingOrders"
        stripe
        row-key="purchaseOrderId"
        empty-text="暂无待收票采购单"
      >
        <el-table-column prop="purchaseOrderNumber" label="采购单号" width="180" />
        <el-table-column prop="supplierName" label="供应商" min-width="150">
          <template #default="{ row }">{{ row.supplierName || row.supplierId || '—' }}</template>
        </el-table-column>
        <el-table-column label="订单金额（含税）" width="150" align="right">
          <template #default="{ row }">{{ row.orderAmount == null ? '—' : `¥${formatAmount(row.orderAmount)}` }}</template>
        </el-table-column>
        <el-table-column prop="orderDate" label="下单日期" width="120" />
        <el-table-column label="状态" width="100" align="center">
          <template #default><el-tag type="warning" size="small">待收票</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canWrite" type="primary" link @click="openUploadDialog(row)">登记发票</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 过滤栏 -->
    <el-card class="filter-card" shadow="never">
      <el-row :gutter="16" align="middle">
        <el-col :span="5">
          <el-select
            v-model="filterStatus"
            placeholder="发票状态"
            clearable
            style="width: 100%"
            @change="handleFilterChange"
          >
            <el-option label="全部" value="" />
            <el-option label="待核对" value="PENDING" />
            <el-option label="已核对" value="MATCHED" />
            <el-option label="不符" value="MISMATCHED" />
          </el-select>
        </el-col>
        <el-col :span="6">
          <el-input
            v-model="filterPoId"
            placeholder="按采购单号查询"
            clearable
            prefix-icon="Search"
            @clear="handleFilterChange"
            @keyup.enter="handleFilterChange"
          />
        </el-col>
        <el-col :span="4">
          <el-button type="primary" @click="handleFilterChange">查询</el-button>
          <el-button @click="resetFilter">重置</el-button>
        </el-col>
        <el-col :span="9" style="text-align: right">
          <el-button
            v-if="canWrite"
            type="primary"
            @click="openUploadDialog()"
          >
            上传发票
          </el-button>
        </el-col>
      </el-row>
    </el-card>

    <!-- 数据表格 -->
    <el-card class="data-card" shadow="never">
      <el-table
        v-loading="loading"
        :data="tableData"
        stripe
        row-key="id"
        style="width: 100%"
      >
        <el-table-column label="发票号" prop="invoiceNumber" width="150" />
        <el-table-column label="采购单号" prop="purchaseOrderNumber" width="160" />
        <el-table-column label="供应商" prop="supplierName" min-width="120" />
        <!-- @PriceSensitive -->
        <el-table-column label="发票金额" prop="amount" width="120">
          <template #default="{ row }">
            <span v-if="row.amount !== null && row.amount !== undefined">
              ¥{{ formatAmount(row.amount) }}
            </span>
            <span v-else class="price-hidden">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" prop="reconcileStatus" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.reconcileStatus)" size="small">
              {{ statusLabel(row.reconcileStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="逐行三单" width="130">
          <template #default="{ row }">
            <el-tag v-if="!row.lines || row.lines.length === 0" type="info" size="small">无行级资料</el-tag>
            <el-tag v-else-if="lineDifferenceCount(row) > 0" type="danger" size="small">
              {{ lineDifferenceCount(row) }} 行有差异
            </el-tag>
            <el-tag v-else type="success" size="small">{{ row.lines.length }} 行一致</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="核对备注" prop="reconcileNote" min-width="120">
          <template #default="{ row }">
            {{ row.reconcileNote || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="上传时间" prop="createdAt" width="150">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canWrite && row.reconcileStatus === 'PENDING'"
              type="primary"
              link
              size="small"
              @click="openReconcileDialog(row)"
            >
              核对
            </el-button>
            <el-button type="info" link size="small" @click="openDetailDialog(row)">
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="fetchData"
          @size-change="fetchData"
        />
      </div>
    </el-card>

    <!-- 上传发票 Dialog (Rule 2: 上传前需填采购单 ID，提供上下文) -->
    <el-dialog
      v-model="uploadDialogVisible"
      :title="uploadMatch ? `上传采购发票 — ${uploadMatch.purchaseOrderNumber || uploadMatch.purchaseOrderId}` : '上传采购发票'"
      width="900px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="uploadFormRef"
        :model="uploadForm"
        :rules="uploadRules"
        label-width="100px"
      >
        <el-form-item label="采购单号" prop="purchaseOrderId">
          <div class="po-input-row">
            <el-input
              v-model="uploadForm.purchaseOrderId"
              placeholder="请填写采购单 ID"
              @change="loadUploadLines"
            />
            <el-button :loading="uploadLinesLoading" @click="loadUploadLines">读取采购单行</el-button>
          </div>
        </el-form-item>
        <el-form-item label="供应商 ID" prop="supplierId">
          <el-input
            v-model="uploadForm.supplierId"
            placeholder="请填写供应商 ID"
          />
        </el-form-item>
        <el-form-item label="发票号" prop="invoiceNumber">
          <el-input
            v-model="uploadForm.invoiceNumber"
            placeholder="请填写发票号码"
          />
        </el-form-item>
        <el-form-item label="发票金额" prop="amount">
          <el-input-number
            v-model="uploadForm.amount"
            :precision="2"
            :min="0.01"
            style="width: 100%"
            placeholder="发票金额"
          />
        </el-form-item>
        <el-form-item label="逐行登记">
          <div class="upload-lines">
            <el-alert
              v-if="uploadLinesError"
              type="error"
              :closable="false"
              :title="uploadLinesError"
              show-icon
            />
            <el-alert
              v-else-if="!uploadMatch"
              type="info"
              :closable="false"
              title="发票必须逐行登记：先读取采购单行，按票面逐行填数量和金额（至少一行）"
              show-icon
            />
            <template v-else>
              <el-table :data="uploadDrafts" size="small" border row-key="purchaseOrderItemId">
                <el-table-column label="采购单行物料" min-width="120">
                  <template #default="{ row }">{{ row.materialName || row.materialTypeId }}</template>
                </el-table-column>
                <el-table-column label="收货 / 退货 / 已开" min-width="150">
                  <template #default="{ row }">
                    <span v-if="row.blocker" class="line-blocker">{{ row.blocker }}</span>
                    <span v-else>
                      {{ fmtQty(row.receivedQuantity) }} / {{ fmtQty(row.returnedQuantity) }} / {{ fmtQty(row.invoicedQuantity) }} {{ row.unit }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="还可开" width="100">
                  <template #default="{ row }">
                    <strong v-if="row.remainingQuantity !== null">{{ fmtQty(row.remainingQuantity) }} {{ row.unit }}</strong>
                    <span v-else>—</span>
                  </template>
                </el-table-column>
                <el-table-column label="收货行（可选）" min-width="140">
                  <template #default="{ row }">
                    <el-select v-model="row.receiveItemId" clearable placeholder="不指定" size="small" style="width: 100%">
                      <el-option
                        v-for="r in row.receipts"
                        :key="r.receiveItemId"
                        :label="`${r.receiveNumber || r.receiveItemId} 剩 ${fmtQty(r.remainingQuantity)}`"
                        :value="r.receiveItemId"
                        :disabled="r.remainingQuantity <= 0"
                      />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="发票所列物料" min-width="130">
                  <template #default="{ row }">
                    <el-select v-model="row.invoiceMaterialTypeId" size="small" filterable allow-create style="width: 100%">
                      <el-option
                        v-for="m in uploadMaterialOptions"
                        :key="m.value"
                        :label="m.label"
                        :value="m.value"
                      />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="本次数量" width="130">
                  <template #default="{ row }">
                    <el-input-number
                      v-model="row.quantity"
                      :min="0"
                      :max="lineMax(row)"
                      :precision="2"
                      :disabled="row.remainingQuantity === null || row.remainingQuantity <= 0"
                      size="small"
                      controls-position="right"
                      style="width: 100%"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="发票行金额（含税）" width="150">
                  <template #default="{ row }">
                    <el-input-number
                      v-model="row.amount"
                      :min="0"
                      :precision="2"
                      :disabled="!row.quantity"
                      size="small"
                      controls-position="right"
                      style="width: 100%"
                    />
                    <div v-if="row.quantity && row.expectedUnitAmount !== null" class="line-expected">
                      按采购价应开 ¥{{ formatAmount(round2(row.quantity * row.expectedUnitAmount)) }}
                    </div>
                  </template>
                </el-table-column>
              </el-table>
              <div class="line-total" :class="{ 'line-total--diff': lineTotalDiffers }">
                已填 {{ selectedUploadLines.length }} 行，行合计 ¥{{ formatAmount(uploadLineTotal) }}
                <span v-if="lineTotalDiffers">，与发票金额不一致（不会自动相符）</span>
              </div>
            </template>
          </div>
        </el-form-item>
        <el-form-item label="OCR识别结果" prop="ocrRawResult">
          <el-input
            v-model="uploadForm.ocrRawResult"
            type="textarea"
            :rows="3"
            placeholder="可选：粘贴 OCR 识别原文"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="uploadLinesInvalid" @click="doUpload">上传</el-button>
      </template>
    </el-dialog>

    <!-- 核对 Dialog (Rule 2: 含采购单号+供应商; Rule 3: 结果 dropdown) -->
    <el-dialog
      v-model="reconcileDialogVisible"
      :title="`发票核对 — ${currentRow?.supplierName || ''} (${currentRow?.purchaseOrderNumber || ''})`"
      width="500px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="dialog-context">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="发票号">{{ currentRow.invoiceNumber }}</el-descriptions-item>
          <el-descriptions-item label="采购单号">{{ currentRow.purchaseOrderNumber }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ currentRow.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="发票金额">
            {{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-alert
          v-if="!currentRow.lines || currentRow.lines.length === 0"
          type="warning"
          :closable="false"
          title="这张票没有行级资料，系统没法逐行核对采购单行和收货，请对照原票人工判断"
          show-icon
          style="margin-bottom: 12px"
        />
        <el-alert
          v-for="line in differenceLines(currentRow)"
          :key="line.id"
          type="error"
          :closable="false"
          :title="`${lineStatusLabel(line.matchStatus)}：${line.matchNote || ''}`"
          style="margin-bottom: 8px"
        />
      </div>

      <el-form
        ref="reconcileFormRef"
        :model="reconcileForm"
        :rules="reconcileRules"
        label-width="90px"
      >
        <!-- Rule 3: dropdown 而非自由文本 -->
        <el-form-item label="核对结果" prop="reconcileStatus">
          <el-select v-model="reconcileForm.reconcileStatus" placeholder="请选择核对结果" style="width: 100%">
            <el-option
              :label="currentRow && lineDifferenceCount(currentRow) > 0 ? '相符 — 不可选：存在行级差异' : '三单相符 — 可结账'"
              value="MATCHED"
              :disabled="!!currentRow && lineDifferenceCount(currentRow) > 0"
            />
            <el-option label="不符 — 需复查" value="MISMATCHED" />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="reconcileForm.reconcileStatus === 'MISMATCHED'"
          label="差异原因"
          prop="note"
        >
          <el-select v-model="reconcileForm.note" placeholder="请选择差异原因" style="width: 100%" allow-create filterable>
            <el-option label="发票金额与采购单不符" value="发票金额与采购单不符" />
            <el-option label="数量不符" value="数量不符" />
            <el-option label="税率差异" value="税率差异" />
            <el-option label="供应商开票错误" value="供应商开票错误" />
            <el-option label="其他原因" value="其他原因" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="note" v-if="reconcileForm.reconcileStatus !== 'MISMATCHED'">
          <el-input
            v-model="reconcileForm.note"
            type="textarea"
            :rows="2"
            placeholder="核对备注（可选）"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="reconcileDialogVisible = false">取消</el-button>
        <el-button
          :type="reconcileForm.reconcileStatus === 'MISMATCHED' ? 'danger' : 'success'"
          :loading="submitting"
          :disabled="!reconcileForm.reconcileStatus"
          @click="doReconcile"
        >
          确认核对
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情 Dialog -->
    <el-dialog
      v-model="detailDialogVisible"
      :title="`发票详情 — ${currentRow?.invoiceNumber || ''}`"
      width="860px"
    >
      <div v-if="currentRow">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="发票号">{{ currentRow.invoiceNumber }}</el-descriptions-item>
          <el-descriptions-item label="采购单号">{{ currentRow.purchaseOrderNumber }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ currentRow.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="发票金额">
            {{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(currentRow.reconcileStatus)" size="small">
              {{ statusLabel(currentRow.reconcileStatus) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="核对备注" :span="2">{{ currentRow.reconcileNote || '—' }}</el-descriptions-item>
          <el-descriptions-item label="上传时间">{{ formatDate(currentRow.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatDate(currentRow.updatedAt) }}</el-descriptions-item>
          <el-descriptions-item label="OCR识别" :span="2">
            <el-text v-if="currentRow.ocrRawResult" type="info" size="small" truncated>
              {{ currentRow.ocrRawResult }}
            </el-text>
            <span v-else>—</span>
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">本票发票行</h4>
        <el-table v-if="currentRow.lines && currentRow.lines.length" :data="currentRow.lines" size="small" border>
          <el-table-column label="采购单行" width="90" prop="purchaseOrderItemId" />
          <el-table-column label="发票物料" min-width="110">
            <template #default="{ row }">{{ row.materialTypeId || '（同采购单行）' }}</template>
          </el-table-column>
          <el-table-column label="数量" width="100">
            <template #default="{ row }">{{ fmtQty(row.quantity) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="金额 / 应开" width="170">
            <template #default="{ row }">
              ¥{{ formatAmount(row.amount) }} / {{ row.expectedAmount === null ? '—' : `¥${formatAmount(row.expectedAmount)}` }}
            </template>
          </el-table-column>
          <el-table-column label="逐行核对" min-width="200">
            <template #default="{ row }">
              <el-tag :type="lineStatusTag(row.matchStatus)" size="small">{{ lineStatusLabel(row.matchStatus) }}</el-tag>
              <span v-if="row.matchNote" class="line-note">{{ row.matchNote }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="这张票登记时没有逐行资料" :image-size="48" />

        <h4 class="section-title">
          采购单逐行开票进度
          <el-tag v-if="detailMatch" :type="detailMatch.fullyMatched ? 'success' : 'warning'" size="small">
            {{ detailMatch.fullyMatched ? '已全部核对' : '有未开 / 部分开票的行' }}
          </el-tag>
        </h4>
        <el-alert v-if="detailMatchError" type="error" :closable="false" :title="detailMatchError" show-icon />
        <el-table v-else v-loading="detailMatchLoading" :data="detailMatch?.lines || []" size="small" border>
          <el-table-column label="物料" min-width="110">
            <template #default="{ row }">{{ row.materialName || row.materialTypeId }}</template>
          </el-table-column>
          <el-table-column label="订购" width="90">
            <template #default="{ row }">{{ fmtQty(row.orderedQuantity) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="有效收货" width="90">
            <template #default="{ row }">{{ fmtQty(row.receivedQuantity) }}</template>
          </el-table-column>
          <el-table-column label="退货" width="80">
            <template #default="{ row }">{{ fmtQty(row.returnedQuantity) }}</template>
          </el-table-column>
          <el-table-column label="已开（核对中+已核对）" width="150">
            <template #default="{ row }">{{ fmtQty(row.invoicedQuantity) }}（已核对 {{ fmtQty(row.matchedQuantity) }}）</template>
          </el-table-column>
          <el-table-column label="还可开" width="90">
            <template #default="{ row }">{{ fmtQty(row.remainingQuantity) }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="120">
            <template #default="{ row }">
              <el-tag :type="poLineStatusTag(row.status)" size="small">{{ poLineStatusLabel(row.status) }}</el-tag>
              <div v-if="row.blocker" class="line-blocker">{{ row.blocker }}</div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { get, post, put } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { usePermissionStore } from '@/store/modules/permission'

// ─── 类型定义 ───────────────────────────────────────────────
type InvoiceStatus = 'PENDING' | 'MATCHED' | 'MISMATCHED'

interface InvoiceRow {
  id: string
  purchaseOrderId: string
  purchaseOrderNumber: string
  supplierId: string
  supplierName: string
  invoiceNumber: string
  amount: number | null   // @PriceSensitive
  reconcileStatus: InvoiceStatus
  reconcileNote: string | null
  ocrRawResult: string | null
  createdAt: string | null
  updatedAt: string | null
  lines?: InvoiceLineView[]
}

type LineMatchStatus = 'MATCHED' | 'MATERIAL_MISMATCH' | 'PRICE_MISMATCH' | 'PRICE_UNKNOWN' | 'MATERIAL_UNKNOWN'

interface InvoiceLineView {
  id: number
  purchaseOrderItemId: number
  receiveItemId: number | null
  materialTypeId: string | null
  quantity: number
  unit: string
  unitPrice: number | null
  amount: number | null          // @PriceSensitive
  expectedAmount: number | null  // @PriceSensitive
  matchStatus: LineMatchStatus
  matchNote: string | null
}

type PoLineStatus = 'NOT_RECEIVED' | 'UNINVOICED' | 'PARTIAL' | 'INVOICED' | 'BLOCKED'

interface MatchReceipt {
  receiveItemId: number
  receiveRecordId: string
  receiveNumber: string | null
  receivedQuantity: number
  invoicedQuantity: number
  remainingQuantity: number
}

interface MatchLine {
  purchaseOrderItemId: number
  materialTypeId: string
  materialName: string | null
  unit: string
  orderedQuantity: number | null
  receivedQuantity: number | null
  returnedQuantity: number | null
  invoiceableQuantity: number | null
  invoicedQuantity: number
  matchedQuantity: number
  remainingQuantity: number | null
  expectedUnitAmount: number | null  // @PriceSensitive
  status: PoLineStatus
  blocker: string | null
  receipts: MatchReceipt[]
}

interface MatchStatus {
  purchaseOrderId: string
  purchaseOrderNumber: string | null
  fullyMatched: boolean
  lines: MatchLine[]
}

interface UploadLineDraft extends MatchLine {
  receiveItemId: number | null
  invoiceMaterialTypeId: string
  quantity: number | null
  amount: number | null
}

interface PendingInvoiceOrder {
  purchaseOrderId: string
  purchaseOrderNumber: string
  supplierId: string | null
  supplierName: string | null
  orderAmount: number | null
  orderDate: string | null
}

// ─── Store ────────────────────────────────────────────────────
const authStore = useAuthStore()
const permStore = usePermissionStore()
const factoryId = computed(() => authStore.factoryId)
const canWrite = computed(() =>
  authStore.hasRole([
    'factory_super_admin',
    'platform_admin',
    'permission_admin',
    'finance_manager',
    'procurement_manager',
  ]) && (permStore.canWrite('procurement') || permStore.canWrite('finance')),
)

// ─── 状态 ────────────────────────────────────────────────────
const loading = ref(false)
const submitting = ref(false)
const tableData = ref<InvoiceRow[]>([])
const pendingOrders = ref<PendingInvoiceOrder[]>([])
const pendingOrdersLoading = ref(false)
const pendingOrdersError = ref(false)
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const filterStatus = ref('')
const filterPoId = ref('')

const reminderPoIds = ref<string[]>([])

const uploadDialogVisible = ref(false)
const reconcileDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const currentRow = ref<InvoiceRow | null>(null)

// 上传表单
const uploadFormRef = ref<FormInstance>()
interface UploadInvoiceForm {
  purchaseOrderId: string
  supplierId: string
  invoiceNumber: string
  amount: number | null
  ocrRawResult: string
}
const uploadForm = ref<UploadInvoiceForm>({
  purchaseOrderId: '',
  supplierId: '',
  invoiceNumber: '',
  amount: null,
  ocrRawResult: ''
})
const uploadRules: FormRules = {
  purchaseOrderId: [{ required: true, message: '请填写采购单 ID', trigger: 'blur' }],
  supplierId: [{ required: true, message: '请填写供应商 ID', trigger: 'blur' }],
  invoiceNumber: [{ required: true, message: '请填写发票号', trigger: 'blur' }],
  amount: [
    { required: true, message: '请填写发票金额', trigger: 'change' },
    { type: 'number', min: 0.01, message: '发票金额必须大于 0', trigger: 'change' },
  ]
}

// 核对表单
const reconcileFormRef = ref<FormInstance>()
const reconcileForm = ref({ reconcileStatus: '', note: '' })
const reconcileRules: FormRules = {
  reconcileStatus: [{ required: true, message: '请选择核对结果', trigger: 'change' }]
}

// 逐行三单（上传）
const uploadMatch = ref<MatchStatus | null>(null)
const uploadDrafts = ref<UploadLineDraft[]>([])
const uploadLinesLoading = ref(false)
const uploadLinesError = ref('')

// 逐行三单（详情）
const detailMatch = ref<MatchStatus | null>(null)
const detailMatchLoading = ref(false)
const detailMatchError = ref('')

const uploadMaterialOptions = computed(() =>
  uploadDrafts.value.map((d) => ({ value: d.materialTypeId, label: d.materialName || d.materialTypeId })),
)
const selectedUploadLines = computed(() =>
  uploadDrafts.value.filter((d) => d.quantity !== null && d.quantity > 0),
)
const uploadLineTotal = computed(() =>
  round2(selectedUploadLines.value.reduce((sum, d) => sum + (d.amount ?? 0), 0)),
)
const lineTotalDiffers = computed(() =>
  selectedUploadLines.value.length > 0
  && uploadForm.value.amount !== null
  && Math.abs(uploadLineTotal.value - uploadForm.value.amount) > 0.005,
)
// 防呆 Rule 1: 没填任何行、超出可开量或填了数量没填金额 → 不让提交（后端仍以 400/409 兜底）
const uploadLinesInvalid = computed(() =>
  selectedUploadLines.value.length === 0
  || selectedUploadLines.value.some((d) =>
    d.amount === null
    || d.remainingQuantity === null
    || (d.quantity ?? 0) > lineMax(d) + 1e-9),
)

function lineMax(d: UploadLineDraft): number {
  const lineLeft = d.remainingQuantity ?? 0
  if (d.receiveItemId === null) return Math.max(lineLeft, 0)
  const receipt = d.receipts.find((r) => r.receiveItemId === d.receiveItemId)
  return Math.max(Math.min(lineLeft, receipt ? receipt.remainingQuantity : 0), 0)
}

function round2(v: number): number {
  return Math.round(v * 100) / 100
}

function fmtQty(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—'
  return Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 4 })
}

function isDifference(line: InvoiceLineView): boolean {
  return line.matchStatus === 'MATERIAL_MISMATCH' || line.matchStatus === 'PRICE_MISMATCH'
}

function differenceLines(row: InvoiceRow): InvoiceLineView[] {
  return (row.lines ?? []).filter(isDifference)
}

function lineDifferenceCount(row: InvoiceRow): number {
  return differenceLines(row).length
}

function lineStatusLabel(s: LineMatchStatus): string {
  const map: Record<LineMatchStatus, string> = {
    MATCHED: '一致',
    MATERIAL_MISMATCH: '物料不符',
    PRICE_MISMATCH: '金额不符',
    PRICE_UNKNOWN: '单价/税率未知',
    MATERIAL_UNKNOWN: '发票未写物料',
  }
  return map[s] ?? s
}

function lineStatusTag(s: LineMatchStatus): 'success' | 'warning' | 'danger' | 'info' {
  if (s === 'MATCHED') return 'success'
  if (s === 'PRICE_UNKNOWN' || s === 'MATERIAL_UNKNOWN') return 'warning'
  return 'danger'
}

function poLineStatusLabel(s: PoLineStatus): string {
  const map: Record<PoLineStatus, string> = {
    NOT_RECEIVED: '未收货',
    UNINVOICED: '未开票',
    PARTIAL: '部分开票',
    INVOICED: '已开满',
    BLOCKED: '量不出来',
  }
  return map[s] ?? s
}

function poLineStatusTag(s: PoLineStatus): 'success' | 'warning' | 'danger' | 'info' {
  if (s === 'INVOICED') return 'success'
  if (s === 'PARTIAL' || s === 'UNINVOICED') return 'warning'
  if (s === 'BLOCKED') return 'danger'
  return 'info'
}

function errorMessage(err: unknown, fallback: string): string {
  const e = err as { message?: string; actionHint?: string | null; response?: { data?: { message?: string } } }
  const message = e?.response?.data?.message || e?.message || fallback
  return e?.actionHint ? `${message}（${e.actionHint}）` : message
}

async function fetchMatchStatus(poId: string): Promise<MatchStatus> {
  const res = await get(`/${factoryId.value}/invoices/match-status`, { params: { poId } })
  if (!res.success || !res.data) throw new Error(res.message || '逐行三单状态加载失败')
  return res.data as MatchStatus
}

async function loadUploadLines() {
  const poId = uploadForm.value.purchaseOrderId?.trim()
  uploadMatch.value = null
  uploadDrafts.value = []
  uploadLinesError.value = ''
  if (!poId || !factoryId.value) return
  uploadLinesLoading.value = true
  try {
    const status = await fetchMatchStatus(poId)
    uploadMatch.value = status
    uploadDrafts.value = status.lines.map((line) => ({
      ...line,
      receiveItemId: null,
      invoiceMaterialTypeId: line.materialTypeId,
      quantity: null,
      amount: null,
    }))
  } catch (err: unknown) {
    uploadLinesError.value = errorMessage(err, '采购单行加载失败')
  } finally {
    uploadLinesLoading.value = false
  }
}

// ─── 标签映射 ────────────────────────────────────────────────
function statusLabel(s: string): string {
  const map: Record<string, string> = {
    PENDING: '待核对',
    MATCHED: '已核对',
    MISMATCHED: '不符'
  }
  return map[s] ?? s
}

function statusTag(s: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  const map: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    PENDING: 'warning',
    MATCHED: 'success',
    MISMATCHED: 'danger'
  }
  return map[s] ?? 'info'
}

function formatAmount(v: number | null): string {
  if (v === null || v === undefined) return '—'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function formatDate(dt: string | null): string {
  if (!dt) return '—'
  return new Date(dt).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  })
}

// ─── 逾期提醒 ────────────────────────────────────────────────
async function fetchReminders() {
  if (!factoryId.value) return
  try {
    const res = await get(`/${factoryId.value}/invoices/reminder`, {})
    if (res.success && Array.isArray(res.data)) {
      reminderPoIds.value = res.data as string[]
    }
  } catch {
    // 提醒失败不阻塞主流程
  }
}

function filterByPoId(poId: string) {
  filterPoId.value = poId
  handleFilterChange()
}

// ─── 数据加载 ────────────────────────────────────────────────
async function fetchData() {
  if (!factoryId.value) return
  loading.value = true
  try {
    // The current backend contract returns an array and only accepts poId on
    // this GET.  Send Axios query params correctly, then apply status and
    // pagination locally so the controls reflect the actual response.
    const res = await get(`/${factoryId.value}/invoices/views`, {
      params: filterPoId.value ? { poId: filterPoId.value } : {}
    })
    if (res.success) {
      const data = res.data
      if (data && typeof data === 'object' && 'content' in data) {
        tableData.value = (data as { content: InvoiceRow[]; totalElements: number }).content
        total.value = (data as { content: InvoiceRow[]; totalElements: number }).totalElements
      } else if (Array.isArray(data)) {
        const rows = data as InvoiceRow[]
        const filteredRows = filterStatus.value
          ? rows.filter((row) => row.reconcileStatus === filterStatus.value)
          : rows
        total.value = filteredRows.length
        const start = (page.value - 1) * pageSize.value
        tableData.value = filteredRows.slice(start, start + pageSize.value)
      }
    }
  } catch (err) {
    console.error('获取发票列表失败', err)
  } finally {
    loading.value = false
  }
}

async function fetchPendingOrders() {
  if (!factoryId.value) return
  pendingOrdersLoading.value = true
  pendingOrdersError.value = false
  try {
    const res = await get(`/${factoryId.value}/invoices/pending-orders`, {})
    if (!res.success || !Array.isArray(res.data)) {
      throw new Error(res.message || '待收票任务响应格式异常')
    }
    pendingOrders.value = res.data as PendingInvoiceOrder[]
  } catch {
    pendingOrdersError.value = true
  } finally {
    pendingOrdersLoading.value = false
  }
}

function handleFilterChange() {
  page.value = 1
  fetchData()
}

function resetFilter() {
  filterStatus.value = ''
  filterPoId.value = ''
  handleFilterChange()
}

// ─── 上传发票 ────────────────────────────────────────────────
function openUploadDialog(todo?: PendingInvoiceOrder) {
  uploadFormRef.value?.resetFields()
  uploadForm.value = {
    purchaseOrderId: todo?.purchaseOrderId || '',
    supplierId: todo?.supplierId || '',
    invoiceNumber: '',
    // 订单金额只用于对照，不能冒充供应商真实来票金额；否则后端会把相等金额自动判为 MATCHED。
    amount: null,
    ocrRawResult: ''
  }
  uploadDialogVisible.value = true
  void loadUploadLines()
}

async function doUpload() {
  if (!uploadFormRef.value || !factoryId.value) return
  await uploadFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const res = await post(`/${factoryId.value}/invoices/upload`, {
        purchaseOrderId: uploadForm.value.purchaseOrderId,
        supplierId: uploadForm.value.supplierId,
        invoiceNumber: uploadForm.value.invoiceNumber,
        amount: uploadForm.value.amount,
        ocrRawResult: uploadForm.value.ocrRawResult || null,
        lines: selectedUploadLines.value.map((d) => ({
          purchaseOrderItemId: d.purchaseOrderItemId,
          receiveItemId: d.receiveItemId,
          materialTypeId: d.invoiceMaterialTypeId,
          quantity: d.quantity,
          amount: d.amount,
        })),
      })
      if (res.success) {
        const status = (res.data as { reconcileStatus?: string } | null)?.reconcileStatus
        ElMessage({
          message: status === 'MATCHED' ? '发票已登记，逐行三单相符' : '发票已登记，待人工核对',
          type: 'success',
          duration: 3000,
        })
        uploadDialogVisible.value = false
        fetchData()
        fetchReminders()
        fetchPendingOrders()
      } else {
        ElMessage({ message: res.message || '上传失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      // 超开 409 的原因和剩余量留在弹窗里，紧挨着要改的行
      uploadLinesError.value = errorMessage(err, '上传失败，请检查网络')
      if (uploadMatch.value) {
        const keep = uploadDrafts.value
        await loadUploadLines()
        uploadLinesError.value = errorMessage(err, '上传失败，请检查网络')
        uploadDrafts.value = uploadDrafts.value.map((d) => {
          const prev = keep.find((k) => k.purchaseOrderItemId === d.purchaseOrderItemId)
          return prev ? { ...d, receiveItemId: prev.receiveItemId, invoiceMaterialTypeId: prev.invoiceMaterialTypeId,
            quantity: prev.quantity, amount: prev.amount } : d
        })
      }
    } finally {
      submitting.value = false
    }
  })
}

// ─── 核对发票 ────────────────────────────────────────────────
function openReconcileDialog(row: InvoiceRow) {
  currentRow.value = row
  reconcileForm.value = { reconcileStatus: '', note: '' }
  reconcileFormRef.value?.resetFields()
  reconcileDialogVisible.value = true
}

async function doReconcile() {
  if (!reconcileFormRef.value || !currentRow.value || !factoryId.value) return
  await reconcileFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const res = await put(
        `/${factoryId.value}/invoices/${currentRow.value!.id}/reconcile`,
        {
          reconcileStatus: reconcileForm.value.reconcileStatus,
          note: reconcileForm.value.note || null
        }
      )
      if (res.success) {
        ElMessage({ message: '发票核对完成', type: 'success', duration: 3000 })
        reconcileDialogVisible.value = false
        fetchData()
      } else {
        ElMessage({ message: res.message || '核对失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '核对失败，请检查网络'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      submitting.value = false
    }
  })
}

// ─── 详情 ────────────────────────────────────────────────────
async function openDetailDialog(row: InvoiceRow) {
  currentRow.value = row
  detailDialogVisible.value = true
  detailMatch.value = null
  detailMatchError.value = ''
  if (!factoryId.value) return
  detailMatchLoading.value = true
  try {
    detailMatch.value = await fetchMatchStatus(row.purchaseOrderId)
  } catch (err: unknown) {
    detailMatchError.value = errorMessage(err, '采购单逐行状态加载失败')
  } finally {
    detailMatchLoading.value = false
  }
}

// ─── 生命周期 ────────────────────────────────────────────────
onMounted(() => {
  fetchData()
  fetchReminders()
  fetchPendingOrders()
})
</script>

<style scoped>
.page-container {
  padding: 20px;
  background: #F4F6F9;
  min-height: 100%;
}

.page-header {
  margin-bottom: 16px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #1B65A8;
  margin: 0 0 4px;
}

.page-subtitle {
  font-size: 13px;
  color: #909399;
  margin: 0;
}

.reminder-card {
  border-radius: 10px;
  margin-bottom: 16px;
  border-color: #F0A030;
  box-shadow: 0 2px 12px rgba(240, 160, 48, 0.1);
}

.filter-card {
  border-radius: 10px;
  margin-bottom: 16px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.data-card {
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.todo-card { margin-bottom: 16px; }
.card-title-row { display: flex; align-items: center; justify-content: space-between; font-weight: 600; }
.pending-error { margin-bottom: 12px; }

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
}

.price-hidden {
  color: #C0C4CC;
  font-style: italic;
}

.dialog-context {
  margin-bottom: 4px;
}

.po-input-row { display: flex; gap: 8px; width: 100%; }
.upload-lines { width: 100%; }
.line-total { margin-top: 8px; font-size: 13px; color: #606266; }
.line-total--diff { color: #E6A23C; }
.line-expected { font-size: 12px; color: #909399; margin-top: 2px; }
.line-blocker { color: #F56C6C; font-size: 12px; }
.line-note { margin-left: 6px; font-size: 12px; color: #606266; }
.section-title { margin: 16px 0 8px; font-size: 14px; display: flex; align-items: center; gap: 8px; }
</style>
