<template>
  <section class="basics-handoff" aria-labelledby="basics-handoff-title" data-testid="factory-basics-handoff">
    <h3 id="basics-handoff-title">基础资料交付核对</h3>
    <p>配置已应用不等于可以直接开工。请由工厂管理员在业务页面逐项保存、刷新核对；以下项目不会因蓝图应用成功而自动标记完成。</p>
    <ol>
      <li><strong>原料与包材：</strong>在「原料类型字典」核对基本单位、原料储存类型和按包装采购的换算。包材核对含税采购参考价；包材不要求储存类型，按基本单位采购时无需包装换算。</li>
      <li><strong>成品与包装：</strong>在「SKU 管理」核对销售基本单位和每基本单位净含量；按箱销售时保存装箱换算，多种箱规须在下单时明确选择，不能只填写规格文字。</li>
      <li><strong>仓库与用途：</strong>在「Canvas 配置编辑器 → 工厂配置 → 仓库管理」核对采购入库、生产成品及物流默认仓。销售出货和生产领料目前共用物流默认仓；不开展研发时无需配置研发仓。</li>
      <li><strong>供客档案：</strong>在「供应商管理」「客户管理」核对实际交易对象和业务所需资料。员工账号、岗位权限与审批配置另行交付，BOM 和 Workflow 仍须独立确认发布。</li>
    </ol>
    <p class="handoff-note">本清单不会修改工厂资料、创建库存或发布工艺；交付结果以业务页面和保存后的读回为准。</p>
  </section>
</template>

<style scoped>
.basics-handoff { margin-top: 16px; padding: 16px 20px; border: 1px solid var(--el-border-color-light); border-radius: 8px; text-align: left; }
h3 { margin: 0 0 8px; font-size: 16px; color: var(--el-text-color-primary); }
p, li { font-size: 14px; line-height: 1.7; color: var(--el-text-color-regular); }
p { margin: 8px 0; }
ol { margin: 12px 0; padding-left: 22px; }
li + li { margin-top: 8px; }
.handoff-note { color: var(--el-text-color-secondary); }
</style>
