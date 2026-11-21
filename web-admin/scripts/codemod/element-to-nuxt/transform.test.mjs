import assert from 'node:assert/strict';
import { test } from 'node:test';
import { compileTemplate, parse } from 'vue/compiler-sfc';
import { transform } from './transform.mjs';

const file = 'src/views/production/demo/list.vue';
const run = source => transform(source, file);

test('模板和脚本按 AST 转换，不改注释、业务字面值与多行引号', () => {
  const source = `<script setup lang="ts">
import { ElMessage as notify, type FormInstance } from 'element-plus';
const code = '生产';
const submit = () => notify.success("保存成功");
</script>
<template>
  <!-- <el-table>不会转换的注释</el-table> -->
  <el-card header="操作台">
    <el-button type="primary" @click="submit"> 张三 &amp; 李四 </el-button>
    <el-descriptions :column="2"><el-descriptions-item label="编号">{{ code }}</el-descriptions-item></el-descriptions>
  </el-card>
</template>`;
  const outcome = run(source);
  assert.equal(outcome.code, run(outcome.code).code);
  assert.match(outcome.code, /import \{ type FormInstance \} from 'element-plus'/);
  assert.match(outcome.code, /const \{ ElMessage: notify \} = useCompatMessages\(\)/);
  assert.match(outcome.code, /const code = '生产'/);
  assert.match(outcome.code, /<UButton color="primary" @click="submit"> {{ t\(/);
  assert.match(outcome.code, /<CompatDescriptionsItem :label="t\(/);
  assert.equal(outcome.tags['el-table'], undefined);
  assert.equal(Object.values(outcome.catalog).includes('张三 & 李四'), true);
  assert.equal(Object.values(outcome.catalog).includes('保存成功'), true);
  const descriptor = parse(outcome.code).descriptor;
  assert.deepEqual(compileTemplate({ id: 'test', filename: file, source: descriptor.template.content }).errors, []);
});

test('不拆不支持的父子组件组，报告后续迁移原因', () => {
  const outcome = run(`<script setup>const rows = [];</script>
<template>
  <el-table :data="rows">
    <el-table-column type="expand"><template #default="{row}">{{ row }}</template></el-table-column>
    <el-table-column label="编号" prop="id" />
  </el-table>
  <el-button>提交</el-button>
</template>`);
  assert.equal(outcome.tags['el-table-column'], 2);
  assert.equal(outcome.converted['el-table-column'], undefined);
  assert.equal(outcome.converted['el-button'], 1);
  assert.equal(outcome.todos.filter(todo => todo.reason === '父子组件需要整组迁移').length, 3);
});

test('选择器收集动态选项，已有翻译绑定重跑稳定', () => {
  const outcome = run(`<script setup>import { ref } from 'vue'; const value = ref(); const list = [];</script>
<template><el-select v-model="value" placeholder="选择原料"><el-option v-for="x in list" :key="x.id" :value="x.id" :label="x.label" /></el-select></template>`);
  assert.match(outcome.code, /<CompatSelect v-model="value" :placeholder="t\(/);
  assert.match(outcome.code, /<CompatOption v-for="x in list"/);
  assert.equal(outcome.code, run(outcome.code).code);
});
