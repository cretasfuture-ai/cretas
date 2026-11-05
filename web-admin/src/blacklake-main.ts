// Isolated local test entry: real Java login + the same SmartBI page/API.
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import ui from '@nuxt/ui/vue-plugin';
import './styles/blacklake-prototype.css';
import echarts from '@/utils/echarts';
import BlacklakeTestEntry from './views/smart-bi/BlacklakeTestEntry.vue';

// Prototype's chart palette; registration stays inside this isolated entry.
echarts.registerTheme('cretas',{color:['#1b65a8','#6ca5d7','#a7c6df','#d6e2ed'],
  textStyle:{fontFamily:'Segoe UI, Microsoft YaHei, sans-serif',color:'#41536a'},
  categoryAxis:{axisLine:{show:false},axisTick:{show:false}},
  valueAxis:{splitLine:{lineStyle:{color:'#eef2f6',type:'dashed'}}}});
createApp(BlacklakeTestEntry).use(createPinia()).use(ui).use(ElementPlus,{locale:zhCn}).mount('#app');
