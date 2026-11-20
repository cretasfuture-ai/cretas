<template>
  <div class="profile-collections">
    <UAlert :ui="alertUi"
      color="info"
      variant="subtle"
      icon="i-lucide-info"
      class="mirror-note"
      title="标记为「主」的联系人 / 地址 / 银行账户会同步到基本资料，采购单、打印和出纳付款都以它为准。"
    />

    <!-- ─────────────────────────── 联系人 ─────────────────────────── -->
    <section class="collection" data-testid="supplier-contacts">
      <div class="collection-head">
        <h4>联系人<span class="count">（{{ contacts.length }}）</span></h4>
        <UButton v-if="canWrite" size="xs" color="neutral" variant="outline" icon="i-lucide-plus" @click="openContact()">
          新增联系人
        </UButton>
      </div>
      <UEmpty
        v-if="!contacts.length"
        icon="i-lucide-user-round"
        description="还没有联系人。至少维护一位，采购单和打印才有人可联系。"
        class="collection-empty"
      >
        <template v-if="canWrite" #actions>
          <UButton @click="openContact()">新增联系人</UButton>
        </template>
      </UEmpty>
      <UTable v-else :data="contacts" :columns="contactColumns" class="cs-table">
        <template #name-cell="{ row }">
          <UBadge v-if="row.original.isPrimary" color="success" variant="soft" class="primary-tag">主</UBadge>
          {{ row.original.name }}
        </template>
        <template #type-cell="{ row }">{{ contactTypeLabel(row.original) }}</template>
        <template #position-cell="{ row }">{{ row.original.position || '-' }}</template>
        <template #phone-cell="{ row }">{{ row.original.phone || '-' }}</template>
        <template #email-cell="{ row }">{{ row.original.email || '-' }}</template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" variant="link" @click="openContact(row.original)">编辑</UButton>
            <UButton v-if="!row.original.isPrimary" size="xs" variant="link" @click="makeContactPrimary(row.original)">设为主</UButton>
            <UButton size="xs" variant="link" color="error" @click="removeContact(row.original)">删除</UButton>
          </div>
        </template>
      </UTable>
    </section>

    <!-- ──────────────────────────── 地址 ──────────────────────────── -->
    <section class="collection" data-testid="supplier-addresses">
      <div class="collection-head">
        <h4>地址<span class="count">（{{ addresses.length }}）</span></h4>
        <UButton v-if="canWrite" size="xs" color="neutral" variant="outline" icon="i-lucide-plus" @click="openAddress()">
          新增地址
        </UButton>
      </div>
      <UEmpty
        v-if="!addresses.length"
        icon="i-lucide-map-pin"
        description="还没有地址。注册地、发货地、开票地不同时，分开维护可避免采购单印错。"
        class="collection-empty"
      >
        <template v-if="canWrite" #actions>
          <UButton @click="openAddress()">新增地址</UButton>
        </template>
      </UEmpty>
      <UTable v-else :data="addresses" :columns="addressColumns" class="cs-table">
        <template #label-cell="{ row }">
          <UBadge v-if="row.original.isPrimary" color="success" variant="soft" class="primary-tag">主</UBadge>
          {{ row.original.label || addressTypeLabel(row.original) }}
        </template>
        <template #type-cell="{ row }">{{ addressTypeLabel(row.original) }}</template>
        <template #address-cell="{ row }"><span :title="row.original.address">{{ row.original.address }}</span></template>
        <template #contact-cell="{ row }">
          {{ row.original.contactName || '-' }}<template v-if="row.original.contactPhone"> · {{ row.original.contactPhone }}</template>
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" variant="link" @click="openAddress(row.original)">编辑</UButton>
            <UButton v-if="!row.original.isPrimary" size="xs" variant="link" @click="makeAddressPrimary(row.original)">设为主</UButton>
            <UButton size="xs" variant="link" color="error" @click="removeAddress(row.original)">删除</UButton>
          </div>
        </template>
      </UTable>
    </section>

    <!-- ────────────────────────── 银行账户 ────────────────────────── -->
    <section class="collection" data-testid="supplier-bank-accounts">
      <div class="collection-head">
        <h4>银行账户<span class="count">（{{ bankAccounts.length }}）</span></h4>
        <UButton v-if="canWrite" size="xs" color="neutral" variant="outline" icon="i-lucide-plus" @click="openBank()">
          新增银行账户
        </UButton>
      </div>
      <UEmpty
        v-if="!bankAccounts.length"
        icon="i-lucide-landmark"
        description="还没有银行账户。出纳付款时会以「主」账户为默认收款账号。"
        class="collection-empty"
      >
        <template v-if="canWrite" #actions>
          <UButton @click="openBank()">新增银行账户</UButton>
        </template>
      </UEmpty>
      <UTable v-else :data="bankAccounts" :columns="bankColumns" class="cs-table">
        <template #accountName-cell="{ row }">
          <UBadge v-if="row.original.isPrimary" color="success" variant="soft" class="primary-tag">主</UBadge>
          {{ row.original.accountName }}
        </template>
        <template #bank-cell="{ row }">
          {{ row.original.bankName }}<template v-if="row.original.branchName"> · {{ row.original.branchName }}</template>
        </template>
        <template #accountNumber-cell="{ row }">{{ row.original.accountNumber }}</template>
        <template #currency-cell="{ row }">{{ row.original.currency || 'CNY' }}</template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" variant="link" @click="openBank(row.original)">编辑</UButton>
            <UButton v-if="!row.original.isPrimary" size="xs" variant="link" @click="makeBankPrimary(row.original)">设为主</UButton>
            <UButton size="xs" variant="link" color="error" @click="removeBank(row.original)">删除</UButton>
          </div>
        </template>
      </UTable>
    </section>

    <!-- ─────────────────────────── 联系人弹窗 ─────────────────────────── -->
    <UModal
      v-model:open="contactDialogVisible"
      :title="`${contactForm.id ? '编辑' : '新增'}联系人 — ${supplierName}`"
      description="维护供应商联系人"
      :dismissible="false"
      :ui="modalUi('max-w-[520px]')"
    >
      <template #body>
        <UForm ref="contactFormRef" :state="contactForm" :validate="validateContact" class="collection-form" @submit="submitContact">
          <UFormField label="姓名" name="name" required><UInput v-model="contactForm.name" :maxlength="100" class="w-full" /></UFormField>
          <UFormField label="类型" name="contactType">
            <USelect v-model="contactForm.contactType" :items="SUPPLIER_CONTACT_TYPE_OPTIONS" class="w-full" />
          </UFormField>
          <UFormField label="职务" name="position">
            <UInput v-model="contactForm.position" :maxlength="100" placeholder="选填，例如「销售经理」" class="w-full" />
          </UFormField>
          <UFormField label="电话" name="phone">
            <UInput v-model="contactForm.phone" :maxlength="40" placeholder="大陆手机号或带区号座机，可含分机" class="w-full" />
          </UFormField>
          <UFormField label="邮箱" name="email"><UInput v-model="contactForm.email" :maxlength="100" class="w-full" /></UFormField>
          <UFormField
            label="设为主联系人"
            name="isPrimary"
            :help="primaryHint(contactForm, contacts, '主联系人会同步到基本资料，采购单和打印都用它。')"
          >
            <USwitch v-model="contactForm.isPrimary" :disabled="isOnlyPrimary(contactForm, contacts)" />
          </UFormField>
          <UFormField label="备注" name="notes"><UTextarea v-model="contactForm.notes" :maxlength="500" :rows="2" class="w-full" /></UFormField>
        </UForm>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="contactDialogVisible = false">取消</UButton>
        <UButton :loading="saving" @click="contactFormRef?.submit()">保存</UButton>
      </template>
    </UModal>

    <!-- ──────────────────────────── 地址弹窗 ──────────────────────────── -->
    <UModal
      v-model:open="addressDialogVisible"
      :title="`${addressForm.id ? '编辑' : '新增'}地址 — ${supplierName}`"
      description="维护供应商地址"
      :dismissible="false"
      :ui="modalUi('max-w-[520px]')"
    >
      <template #body>
        <UForm ref="addressFormRef" :state="addressForm" :validate="validateAddress" class="collection-form" @submit="submitAddress">
          <UFormField label="类型" name="addressType">
            <USelect v-model="addressForm.addressType" :items="SUPPLIER_ADDRESS_TYPE_OPTIONS" class="w-full" />
          </UFormField>
          <UFormField label="标签" name="label">
            <UInput v-model="addressForm.label" :maxlength="60" placeholder="选填，例如「昆山仓」" class="w-full" />
          </UFormField>
          <UFormField label="地址" name="address" required><UTextarea v-model="addressForm.address" :maxlength="500" :rows="2" class="w-full" /></UFormField>
          <UFormField label="收货联系人" name="contactName"><UInput v-model="addressForm.contactName" :maxlength="100" class="w-full" /></UFormField>
          <UFormField label="联系电话" name="contactPhone"><UInput v-model="addressForm.contactPhone" :maxlength="40" class="w-full" /></UFormField>
          <UFormField
            label="设为主地址"
            name="isPrimary"
            :help="primaryHint(addressForm, addresses, '主地址会同步到基本资料，也是溯源产地的来源。')"
          >
            <USwitch v-model="addressForm.isPrimary" :disabled="isOnlyPrimary(addressForm, addresses)" />
          </UFormField>
          <UFormField label="备注" name="notes"><UTextarea v-model="addressForm.notes" :maxlength="500" :rows="2" class="w-full" /></UFormField>
        </UForm>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="addressDialogVisible = false">取消</UButton>
        <UButton :loading="saving" @click="addressFormRef?.submit()">保存</UButton>
      </template>
    </UModal>

    <!-- ─────────────────────────── 银行账户弹窗 ─────────────────────────── -->
    <UModal
      v-model:open="bankDialogVisible"
      :title="`${bankForm.id ? '编辑' : '新增'}银行账户 — ${supplierName}`"
      description="维护供应商银行账户"
      :dismissible="false"
      :ui="modalUi('max-w-[520px]')"
    >
      <template #body>
        <UAlert :ui="alertUi"
          color="warning"
          variant="subtle"
          icon="i-lucide-triangle-alert"
          class="bank-warning"
          title="出纳付款单默认打到「主」账户，请核对户名与账号后再保存。"
        />
        <UForm ref="bankFormRef" :state="bankForm" :validate="validateBank" class="collection-form" @submit="submitBank">
          <UFormField label="户名" name="accountName">
            <UInput v-model="bankForm.accountName" :maxlength="200" :placeholder="`留空默认用「${supplierName}」`" class="w-full" />
          </UFormField>
          <UFormField label="开户行" name="bankName" required>
            <UInput v-model="bankForm.bankName" :maxlength="100" placeholder="例如「中国工商银行」" class="w-full" />
          </UFormField>
          <UFormField label="支行/网点" name="branchName">
            <UInput v-model="bankForm.branchName" :maxlength="200" placeholder="选填，例如「北京朝阳支行」" class="w-full" />
          </UFormField>
          <UFormField label="账号" name="accountNumber" required>
            <UInput v-model="bankForm.accountNumber" :maxlength="32" placeholder="只填数字，不要带开户行名称或空格" class="w-full" />
          </UFormField>
          <UFormField label="币种" name="currency">
            <USelect v-model="bankForm.currency" :items="CURRENCIES" class="w-full" />
          </UFormField>
          <UFormField
            label="设为主账户"
            name="isPrimary"
            :help="primaryHint(bankForm, bankAccounts, '主账户是出纳付款单的默认收款账号。')"
          >
            <USwitch v-model="bankForm.isPrimary" :disabled="isOnlyPrimary(bankForm, bankAccounts)" />
          </UFormField>
          <UFormField label="备注" name="notes"><UTextarea v-model="bankForm.notes" :maxlength="500" :rows="2" class="w-full" /></UFormField>
        </UForm>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="bankDialogVisible = false">取消</UButton>
        <UButton :loading="saving" @click="bankFormRef?.submit()">保存</UButton>
      </template>
    </UModal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import {
  SUPPLIER_ADDRESS_TYPE_OPTIONS,
  SUPPLIER_CONTACT_TYPE_OPTIONS,
  deleteSupplierAddress,
  deleteSupplierBankAccount,
  deleteSupplierContact,
  listSupplierAddresses,
  listSupplierBankAccounts,
  listSupplierContacts,
  saveSupplierAddress,
  saveSupplierBankAccount,
  saveSupplierContact,
  type SupplierAddress,
  type SupplierBankAccount,
  type SupplierContact,
} from '@/api/supplierManagement';
import { isValidSupplierPhone, isReadableSupplierAddress, normalizeText } from './supplierModel';
import { alertUi, modalUi } from './supplierOverlayUi';

const props = defineProps<{
  factoryId: string;
  supplierId: string;
  supplierName: string;
  canWrite: boolean;
}>();

const emit = defineEmits<{ (e: 'changed'): void }>();

const CURRENCIES = ['CNY', 'USD', 'EUR', 'JPY', 'HKD'];

const contacts = ref<SupplierContact[]>([]);
const addresses = ref<SupplierAddress[]>([]);
const bankAccounts = ref<SupplierBankAccount[]>([]);
const saving = ref(false);

const contactDialogVisible = ref(false);
const addressDialogVisible = ref(false);
const bankDialogVisible = ref(false);
// UForm 校验在 @submit 之前由 :validate 完成；footer 里的保存按钮只负责触发 submit()
type FormHandle = { submit: () => Promise<void> };
const contactFormRef = ref<FormHandle | null>(null);
const addressFormRef = ref<FormHandle | null>(null);
const bankFormRef = ref<FormHandle | null>(null);

const contactForm = reactive<SupplierContact>(emptyContact());
const addressForm = reactive<SupplierAddress>(emptyAddress());
const bankForm = reactive<SupplierBankAccount>(emptyBank());

const actionsColumn = <T,>(): TableColumn<T> => ({ id: 'actions', header: '操作' });
const contactColumns = computed<TableColumn<SupplierContact>[]>(() => [
  { id: 'name', header: '姓名' },
  { id: 'type', header: '类型' },
  { id: 'position', header: '职务' },
  { id: 'phone', header: '电话' },
  { id: 'email', header: '邮箱' },
  ...(props.canWrite ? [actionsColumn<SupplierContact>()] : []),
]);
const addressColumns = computed<TableColumn<SupplierAddress>[]>(() => [
  { id: 'label', header: '标签' },
  { id: 'type', header: '类型' },
  { id: 'address', header: '地址' },
  { id: 'contact', header: '收货联系人' },
  ...(props.canWrite ? [actionsColumn<SupplierAddress>()] : []),
]);
const bankColumns = computed<TableColumn<SupplierBankAccount>[]>(() => [
  { id: 'accountName', header: '户名' },
  { id: 'bank', header: '开户行' },
  { id: 'accountNumber', header: '账号' },
  { id: 'currency', header: '币种' },
  ...(props.canWrite ? [actionsColumn<SupplierBankAccount>()] : []),
]);

function emptyContact(): SupplierContact {
  return {
    id: null, name: '', contactType: 'OTHER', phone: '', email: '',
    position: '', isPrimary: false, notes: '', version: null,
  };
}
function emptyAddress(): SupplierAddress {
  return {
    id: null, label: '', addressType: 'BUSINESS', address: '', contactName: '',
    contactPhone: '', isPrimary: false, notes: '', version: null,
  };
}
function emptyBank(): SupplierBankAccount {
  return {
    id: null, accountName: '', bankName: '', branchName: '', accountNumber: '',
    currency: 'CNY', isPrimary: false, notes: '', version: null,
  };
}

type FormError = { name: string; message: string };
const PHONE_MESSAGE = '请输入大陆手机号或带区号座机，可带分机';
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** 与原 Element rules 同一组判据与文案（UForm :validate 用）。 */
function validateContact(state: Partial<SupplierContact>): FormError[] {
  const errors: FormError[] = [];
  if (!normalizeText(state.name)) errors.push({ name: 'name', message: '请输入联系人姓名' });
  if (normalizeText(state.phone) && !isValidSupplierPhone(state.phone)) errors.push({ name: 'phone', message: PHONE_MESSAGE });
  if (normalizeText(state.email) && !EMAIL_PATTERN.test(normalizeText(state.email))) {
    errors.push({ name: 'email', message: '邮箱格式不正确' });
  }
  return errors;
}

function validateAddress(state: Partial<SupplierAddress>): FormError[] {
  const errors: FormError[] = [];
  if (!normalizeText(state.address)) errors.push({ name: 'address', message: '请输入地址' });
  else if (!isReadableSupplierAddress(state.address)) {
    errors.push({ name: 'address', message: '请输入可识别的地址（不超过 500 字，不能只填符号）' });
  }
  if (normalizeText(state.contactPhone) && !isValidSupplierPhone(state.contactPhone)) {
    errors.push({ name: 'contactPhone', message: PHONE_MESSAGE });
  }
  return errors;
}

function validateBank(state: Partial<SupplierBankAccount>): FormError[] {
  const errors: FormError[] = [];
  if (!normalizeText(state.bankName)) errors.push({ name: 'bankName', message: '请输入开户行' });
  if (!normalizeText(state.accountNumber)) errors.push({ name: 'accountNumber', message: '请输入银行账号' });
  else if (!/^[0-9]{8,32}$/.test(normalizeText(state.accountNumber))) {
    errors.push({ name: 'accountNumber', message: '账号只能是 8-32 位数字，请勿包含空格、开户行名称或其他符号' });
  }
  return errors;
}

watch(
  () => [props.factoryId, props.supplierId],
  () => { void reload(); },
  { immediate: true },
);

async function reload(): Promise<void> {
  if (!props.factoryId || !props.supplierId) return;
  const [c, a, b] = await Promise.all([
    listSupplierContacts(props.factoryId, props.supplierId),
    listSupplierAddresses(props.factoryId, props.supplierId),
    listSupplierBankAccounts(props.factoryId, props.supplierId),
  ]);
  contacts.value = c;
  addresses.value = a;
  bankAccounts.value = b;
}

function contactTypeLabel(row: SupplierContact): string {
  return row.contactTypeLabel
    || SUPPLIER_CONTACT_TYPE_OPTIONS.find((o) => o.value === row.contactType)?.label
    || '其他';
}

function addressTypeLabel(row: SupplierAddress): string {
  return row.addressTypeLabel
    || SUPPLIER_ADDRESS_TYPE_OPTIONS.find((o) => o.value === row.addressType)?.label
    || '其他';
}

/** 唯一一条 / 当前就是主的那条：不给关掉开关，否则会出现"一条都不是主"。 */
function isOnlyPrimary(form: { id?: string | null; isPrimary?: boolean | null }, list: unknown[]): boolean {
  return Boolean(form.isPrimary) && (list.length <= 1 || Boolean(form.id));
}

function primaryHint(
  form: { id?: string | null; isPrimary?: boolean | null },
  list: unknown[],
  base: string,
): string {
  if (!list.length) return `第一条会自动成为主记录。${base}`;
  if (isOnlyPrimary(form, list)) return `当前已是主记录，要换主请到目标那条点「设为主」。${base}`;
  return base;
}

// ─────────────────────────────── 联系人 ───────────────────────────────

function openContact(row?: SupplierContact): void {
  Object.assign(contactForm, row ? { ...row } : emptyContact());
  contactDialogVisible.value = true;
}

async function submitContact(): Promise<void> {
  saving.value = true;
  try {
    contacts.value = await saveSupplierContact(props.factoryId, props.supplierId, { ...contactForm });
    contactDialogVisible.value = false;
    ElMessage.success('联系人已保存');
    emit('changed');
  } finally {
    saving.value = false;
  }
}

async function makeContactPrimary(row: SupplierContact): Promise<void> {
  await ElMessageBox.confirm(
    `把「${row.name}」设为主联系人？基本资料里的联系人、电话、邮箱会同步成它，采购单和打印都会改用它。`,
    '切换主联系人',
    { type: 'warning', confirmButtonText: '设为主联系人' },
  );
  contacts.value = await saveSupplierContact(props.factoryId, props.supplierId, { ...row, isPrimary: true });
  ElMessage.success('已切换主联系人');
  emit('changed');
}

async function removeContact(row: SupplierContact): Promise<void> {
  await ElMessageBox.confirm(
    row.isPrimary
      ? `「${row.name}」是主联系人，删除后会自动把下一位提升为主，基本资料随之改变。确定删除吗？`
      : `确定删除联系人「${row.name}」吗？`,
    '删除联系人',
    { type: 'warning', confirmButtonText: '删除' },
  );
  contacts.value = await deleteSupplierContact(props.factoryId, props.supplierId, String(row.id));
  ElMessage.success('联系人已删除');
  emit('changed');
}

// ──────────────────────────────── 地址 ────────────────────────────────

function openAddress(row?: SupplierAddress): void {
  Object.assign(addressForm, row ? { ...row } : emptyAddress());
  addressDialogVisible.value = true;
}

async function submitAddress(): Promise<void> {
  saving.value = true;
  try {
    addresses.value = await saveSupplierAddress(props.factoryId, props.supplierId, { ...addressForm });
    addressDialogVisible.value = false;
    ElMessage.success('地址已保存');
    emit('changed');
  } finally {
    saving.value = false;
  }
}

async function makeAddressPrimary(row: SupplierAddress): Promise<void> {
  await ElMessageBox.confirm(
    `把「${row.label || row.address}」设为主地址？基本资料里的地址会同步成它，溯源产地也按它算。`,
    '切换主地址',
    { type: 'warning', confirmButtonText: '设为主地址' },
  );
  addresses.value = await saveSupplierAddress(props.factoryId, props.supplierId, { ...row, isPrimary: true });
  ElMessage.success('已切换主地址');
  emit('changed');
}

async function removeAddress(row: SupplierAddress): Promise<void> {
  await ElMessageBox.confirm(
    row.isPrimary
      ? `「${row.label || row.address}」是主地址，删除后会自动把下一条提升为主。确定删除吗？`
      : `确定删除地址「${row.label || row.address}」吗？`,
    '删除地址',
    { type: 'warning', confirmButtonText: '删除' },
  );
  addresses.value = await deleteSupplierAddress(props.factoryId, props.supplierId, String(row.id));
  ElMessage.success('地址已删除');
  emit('changed');
}

// ────────────────────────────── 银行账户 ──────────────────────────────

function openBank(row?: SupplierBankAccount): void {
  Object.assign(bankForm, row ? { ...row } : emptyBank());
  bankDialogVisible.value = true;
}

async function submitBank(): Promise<void> {
  saving.value = true;
  try {
    bankAccounts.value = await saveSupplierBankAccount(props.factoryId, props.supplierId, { ...bankForm });
    bankDialogVisible.value = false;
    ElMessage.success('银行账户已保存');
    emit('changed');
  } finally {
    saving.value = false;
  }
}

async function makeBankPrimary(row: SupplierBankAccount): Promise<void> {
  await ElMessageBox.confirm(
    `把「${row.bankName} ${row.accountNumber}」设为主账户？出纳付款单的默认收款账号会改成它。`,
    '切换主账户',
    { type: 'warning', confirmButtonText: '设为主账户' },
  );
  bankAccounts.value = await saveSupplierBankAccount(
    props.factoryId, props.supplierId, { ...row, isPrimary: true });
  ElMessage.success('已切换主账户');
  emit('changed');
}

async function removeBank(row: SupplierBankAccount): Promise<void> {
  const isLast = bankAccounts.value.length <= 1;
  await ElMessageBox.confirm(
    isLast
      ? `这是最后一个银行账户，删除后基本资料的开户行和账号会一并清空，出纳将看不到默认收款账户。确定删除吗？`
      : row.isPrimary
        ? `「${row.bankName} ${row.accountNumber}」是主账户，删除后会自动把下一条提升为主，出纳的默认收款账号随之改变。确定删除吗？`
        : `确定删除银行账户「${row.bankName} ${row.accountNumber}」吗？`,
    '删除银行账户',
    { type: 'warning', confirmButtonText: '删除' },
  );
  bankAccounts.value = await deleteSupplierBankAccount(props.factoryId, props.supplierId, String(row.id));
  ElMessage.success('银行账户已删除');
  emit('changed');
}

defineExpose({ reload });
</script>

<style scoped>
.profile-collections { display: flex; flex-direction: column; gap: var(--cs-space-5); }
.mirror-note { margin-bottom: var(--cs-space-1); }
.collection-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--cs-space-2);
}
.collection-head h4 { margin: 0; font-size: var(--cs-type-section); font-weight: 600; color: var(--cs-text); }
.collection-head .count { font-weight: 400; color: var(--cs-muted); }
.collection-empty { border: 1px dashed var(--cs-border); border-radius: var(--cs-radius-sm); }
.primary-tag { margin-right: 6px; }
.row-actions { display: flex; align-items: center; gap: var(--cs-space-1); }
.collection-form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.bank-warning { margin-bottom: var(--cs-space-3); }
</style>
